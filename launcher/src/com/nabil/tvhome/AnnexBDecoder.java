package com.nabil.tvhome;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hardware H.265 decoder fed with Annex-B access units (as delivered by the Baseus camera). Keeps the latest
 * keyframe and the frames since, so a surface can be attached at any moment and shows a picture on the next frame.
 * The codec exists only while a surface is attached; detaching frees the hardware decoder.
 */
final class AnnexBDecoder {
    interface Listener {
        void onSize(int w, int h);
        void onFirstFrame();
    }

    private static final String TAG = "AnnexBDecoder";

    private final Listener listener;
    private final String name;
    private final boolean snapshots;                  // software decoder fed keyframes only (grid thumbnails)
    private final ExecutorService exec;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Object lock = new Object();
    private final ArrayList<byte[]> gop = new ArrayList<>();
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private int gopBytes;
    private boolean gopValid, firstFrame;
    private Surface surface, codecSurface;
    private MediaCodec codec;
    private int width, height;
    private boolean hevc = true;
    private volatile boolean keyframesOnly;
    private int hintW, hintH;
    private long pts;
    private int stIn, stFed, stDropped, stOut, gaps200, gaps500;
    private long lastIn, maxGap, latSum, latN, maxLat;
    private final java.util.ArrayDeque<Long> inTimes = new java.util.ArrayDeque<>();
    private long stT = System.currentTimeMillis();

    private void stats() {
        long now = System.currentTimeMillis();
        if (now - stT >= 5000) {
            Log.i(TAG, name + " 5s: received=" + stIn + " fed=" + stFed + " noInputBuffer=" + stDropped + " shown=" + stOut + " gaps>200ms=" + gaps200 + " gaps>500ms=" + gaps500 + " maxGap=" + maxGap + "ms latency(avg/max)=" + (latN > 0 ? latSum / latN : 0) + "/" + maxLat + "ms" + (codec != null ? " [decoding]" : " [idle]"));
            stIn = stFed = stDropped = stOut = gaps200 = gaps500 = 0; maxGap = 0; latSum = latN = maxLat = 0;
            stT = now;
        }
    }

    AnnexBDecoder(String name, boolean snapshots, Listener l) {
        this.name = name;
        this.snapshots = snapshots;
        this.listener = l;
        this.exec = snapshots ? Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "snap-" + name); t.setDaemon(true); return t; }) : null;
    }

    /** Must be called before the first frame: codec and the picture size the stream reports. */
    void setStreamInfo(boolean isHevc, int w, int h) { synchronized (lock) { hevc = isHevc; hintW = w; hintH = h; } }

    /** Thumbnail mode: drop every frame that is not a keyframe (cheap, but only updates once per GOP). */
    void setKeyframesOnly(boolean v) { keyframesOnly = v; }

    int width() { return width; }
    int height() { return height; }

    void attach(Surface s) {
        synchronized (lock) { surface = s; }   // the codec is (re)built on the next frame
    }

    void detach() {
        synchronized (lock) {
            surface = null;
            release();
            firstFrame = false;
        }
    }

    /** Drop everything, including the buffered keyframe. */
    void reset() {
        synchronized (lock) {
            surface = null;
            release();
            gop.clear();
            gopBytes = 0;
            gopValid = false;
            firstFrame = false;
        }
    }

    private final java.util.concurrent.ArrayBlockingQueue<Object[]> queue = new java.util.concurrent.ArrayBlockingQueue<>(10);
    private boolean needKey;
    private Thread worker;

    void feed(final byte[] au, final boolean key) {
        if (!snapshots) { feedNow(au, key); return; }
        // Thumbnails: software decoding of EVERY frame on a worker thread. If the worker falls behind, drop the
        // backlog and wait for the next keyframe (dropping single frames would smear the picture).
        if (surface == null) return;
        if (keyframesOnly && !key) return;
        synchronized (queue) {
            if (needKey && !key) return;
            if (key) needKey = false;
            if (!queue.offer(new Object[]{au, key})) { queue.clear(); needKey = !key; if (key) queue.offer(new Object[]{au, true}); return; }
            if (worker == null) {
                worker = new Thread(() -> {
                    try {
                        while (true) { Object[] it = queue.take(); feedNow((byte[]) it[0], (Boolean) it[1]); }
                    } catch (InterruptedException ignored) { }
                }, "snap-" + name);
                worker.setDaemon(true);
                worker.start();
            }
        }
    }

    private void feedNow(byte[] au, boolean key) {
        synchronized (lock) {
            stIn++;
            { long t = System.currentTimeMillis(); if (lastIn != 0) { long g = t - lastIn; if (g > maxGap) maxGap = g; if (g > 200) gaps200++; if (g > 500) gaps500++; } lastIn = t; inTimes.addLast(t); while (inTimes.size() > 64) inTimes.removeFirst(); }
            stats();
            if (key) { gop.clear(); gopBytes = 0; gopValid = true; }
            if (gopValid) {
                gop.add(au);
                gopBytes += au.length;
                if (gopBytes > 12_000_000 || gop.size() > 600) { gop.clear(); gopBytes = 0; gopValid = false; }
            }
            Surface s = surface;
            if (s == null) return;
            try {
                if (codec != null && codecSurface != s) { release(); firstFrame = false; }
                if (codec == null) {
                    if (!gopValid || !create(s)) return;
                    for (byte[] f : gop) queue(f);        // replay from the keyframe; includes this frame
                } else {
                    queue(au);
                }
            } catch (Exception e) {
                Log.w(TAG, name + " decode error: " + e);
                release();
                gopValid = false;                          // wait for the next keyframe
                firstFrame = false;
            }
        }
    }

    // ------------------------------------------------------------------ internals

    private boolean create(Surface s) throws Exception {
        byte[] vps = null, sps = null, pps = null;
        byte[] key = gop.get(0);
        for (int i = 0; i + 4 < key.length; i++) {
            if (key[i] == 0 && key[i + 1] == 0 && (key[i + 2] == 1 || (key[i + 2] == 0 && key[i + 3] == 1))) {
                int p = i + (key[i + 2] == 1 ? 3 : 4);
                if (p >= key.length) break;
                int type = hevc ? (key[p] >> 1) & 63 : key[p] & 31;
                boolean wanted = hevc ? (type >= 32 && type <= 34) : (type == 7 || type == 8);
                if (!wanted) { i = p; continue; }
                int end = key.length;
                for (int j = p + 1; j + 3 < key.length; j++) {
                    if (key[j] == 0 && key[j + 1] == 0 && (key[j + 2] == 1 || (key[j + 2] == 0 && key[j + 3] == 1))) { end = j; break; }
                }
                byte[] nal = java.util.Arrays.copyOfRange(key, p, end);
                if (hevc) { if (type == 32 && vps == null) vps = nal; else if (type == 33 && sps == null) sps = nal; else if (type == 34 && pps == null) pps = nal; }
                else { if (type == 7 && sps == null) sps = nal; else if (type == 8 && pps == null) pps = nal; }
                i = end - 1;
            }
        }
        if (sps == null || pps == null || (hevc && vps == null)) { gopValid = false; return false; }
        int[] wh = hevc ? RtspHevcClient.parseSpsSize(sps) : parseH264SpsSize(sps);
        Log.i(TAG, name + " stream size: SPS says " + wh[0] + "x" + wh[1] + ", header says " + hintW + "x" + hintH);
        width = wh[0] > 0 ? wh[0] : (hintW > 0 ? hintW : 1920);
        height = wh[1] > 0 ? wh[1] : (hintH > 0 ? hintH : 1080);
        String mime = hevc ? "video/hevc" : "video/avc";
        MediaFormat f = MediaFormat.createVideoFormat(mime, width, height);
        byte[] sc = {0, 0, 0, 1};
        if (hevc) {
            ByteArrayOutputStream csd = new ByteArrayOutputStream();
            for (byte[] n : new byte[][]{vps, sps, pps}) { csd.write(sc, 0, 4); csd.write(n, 0, n.length); }
            f.setByteBuffer("csd-0", ByteBuffer.wrap(csd.toByteArray()));
        } else {
            ByteArrayOutputStream a = new ByteArrayOutputStream(), b = new ByteArrayOutputStream();
            a.write(sc, 0, 4); a.write(sps, 0, sps.length);
            b.write(sc, 0, 4); b.write(pps, 0, pps.length);
            f.setByteBuffer("csd-0", ByteBuffer.wrap(a.toByteArray()));
            f.setByteBuffer("csd-1", ByteBuffer.wrap(b.toByteArray()));
        }
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 << 20);
        try { f.setInteger("low-latency", 1); } catch (Exception ignored) { }
        String sw = hevc ? "c2.android.hevc.decoder" : "c2.android.avc.decoder";
        MediaCodec c = snapshots ? MediaCodec.createByCodecName(sw) : MediaCodec.createDecoderByType(mime);
        try {
            c.configure(f, s, null, 0);
            c.start();
        } catch (Exception e) {
            c.release();
            throw e;
        }
        codec = c;
        codecSurface = s;
        Log.d(TAG, name + " decoder " + c.getName() + " " + width + "x" + height);
        listener.onSize(width, height);
        return true;
    }

    /** Picture size from an H.264 SPS NAL (including its 1-byte header); {0,0} if it cannot be parsed. */
    static int[] parseH264SpsSize(byte[] nal) {
        try {
            ByteArrayOutputStream rbsp = new ByteArrayOutputStream();
            for (int i = 1, zeros = 0; i < nal.length; i++) {
                if (zeros >= 2 && nal[i] == 3) { zeros = 0; continue; }
                zeros = nal[i] == 0 ? zeros + 1 : 0;
                rbsp.write(nal[i]);
            }
            byte[] d = rbsp.toByteArray();
            int[] pos = {0};
            int profile = bits(d, pos, 8);
            bits(d, pos, 8);                       // constraint flags
            bits(d, pos, 8);                       // level
            ue(d, pos);                            // sps id
            int chroma = 1;
            if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44 || profile == 83
                    || profile == 86 || profile == 118 || profile == 128 || profile == 138 || profile == 139 || profile == 134 || profile == 135) {
                chroma = ue(d, pos);
                if (chroma == 3) bits(d, pos, 1);
                ue(d, pos); ue(d, pos);            // bit depths
                bits(d, pos, 1);                   // transform bypass
                if (bits(d, pos, 1) == 1) {        // scaling matrices
                    int n = chroma != 3 ? 8 : 12;
                    for (int i = 0; i < n; i++) {
                        if (bits(d, pos, 1) == 1) {
                            int size = i < 6 ? 16 : 64, last = 8, next = 8;
                            for (int j = 0; j < size; j++) {
                                if (next != 0) { int delta = se(d, pos); next = (last + delta + 256) % 256; }
                                last = next == 0 ? last : next;
                            }
                        }
                    }
                }
            }
            ue(d, pos);                            // log2_max_frame_num - 4
            int pocType = ue(d, pos);
            if (pocType == 0) ue(d, pos);
            else if (pocType == 1) {
                bits(d, pos, 1); se(d, pos); se(d, pos);
                int n = ue(d, pos);
                for (int i = 0; i < n; i++) se(d, pos);
            }
            ue(d, pos);                            // max ref frames
            bits(d, pos, 1);                       // gaps
            int wMbs = ue(d, pos) + 1, hMapUnits = ue(d, pos) + 1;
            int frameMbsOnly = bits(d, pos, 1);
            if (frameMbsOnly == 0) bits(d, pos, 1);
            bits(d, pos, 1);                       // direct 8x8
            int w = wMbs * 16, h = (2 - frameMbsOnly) * hMapUnits * 16;
            if (bits(d, pos, 1) == 1) {            // cropping
                int l = ue(d, pos), r = ue(d, pos), t = ue(d, pos), b = ue(d, pos);
                int cx = (chroma == 1 || chroma == 2) ? 2 : 1, cy = (chroma == 1 ? 2 : 1) * (2 - frameMbsOnly);
                w -= (l + r) * cx;
                h -= (t + b) * cy;
            }
            return new int[]{w, h};
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }

    private static int bits(byte[] d, int[] pos, int n) {
        int v = 0;
        for (int i = 0; i < n; i++) { int p = pos[0]++; v = (v << 1) | ((d[p >> 3] >> (7 - (p & 7))) & 1); }
        return v;
    }

    private static int ue(byte[] d, int[] pos) {
        int zeros = 0;
        while (bits(d, pos, 1) == 0 && zeros < 32) zeros++;
        return zeros == 0 ? 0 : (1 << zeros) - 1 + bits(d, pos, zeros);
    }

    private static int se(byte[] d, int[] pos) {
        int k = ue(d, pos);
        return (k & 1) == 1 ? (k + 1) / 2 : -(k / 2);
    }

    private void queue(byte[] data) {
        int idx = codec.dequeueInputBuffer(15_000);
        if (idx < 0) stDropped++;
        if (idx >= 0) {
            stFed++;
            ByteBuffer b = codec.getInputBuffer(idx);
            if (b != null && data.length <= b.capacity()) {
                b.clear();
                b.put(data);
                codec.queueInputBuffer(idx, 0, data.length, pts, 0);
                pts += 50_000;
            } else {
                codec.queueInputBuffer(idx, 0, 0, 0, 0);
            }
        }
        drain();
    }

    private void drain() {
        for (int guard = 0; guard < 8; guard++) {
            int o = codec.dequeueOutputBuffer(info, 0);
            if (o >= 0) {
                codec.releaseOutputBuffer(o, true);
                stOut++;
                { Long t0 = inTimes.pollFirst(); if (t0 != null) { long l = System.currentTimeMillis() - t0; latSum += l; latN++; if (l > maxLat) maxLat = l; } }
                if (!firstFrame) { firstFrame = true; listener.onFirstFrame(); }
            } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat nf = codec.getOutputFormat();
                int w = nf.getInteger(MediaFormat.KEY_WIDTH), h = nf.getInteger(MediaFormat.KEY_HEIGHT);
                if (nf.containsKey("crop-right") && nf.containsKey("crop-left")) w = nf.getInteger("crop-right") - nf.getInteger("crop-left") + 1;
                if (nf.containsKey("crop-bottom") && nf.containsKey("crop-top")) h = nf.getInteger("crop-bottom") - nf.getInteger("crop-top") + 1;
                if (w != width || h != height) { width = w; height = h; listener.onSize(w, h); }
            } else {
                break;
            }
        }
    }

    private void release() {
        MediaCodec c = codec;
        codec = null;
        codecSurface = null;
        if (c != null) {
            try { c.stop(); } catch (Exception ignored) { }
            try { c.release(); } catch (Exception ignored) { }
        }
    }
}
