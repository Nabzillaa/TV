package com.nabil.tvhome;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal RTSP (over TCP, interleaved) + RTP H.265 (RFC 7798) client that feeds the hardware
 * HEVC decoder and renders to a Surface. Built for Dahua-family NVRs: digest auth is recomputed
 * per request on the same connection (the nonce is bound to the connection).
 */
class RtspHevcClient {
    interface Listener {
        void onVideoSize(int w, int h);
        void onFirstFrame();
        void onError(String message);
    }

    private static final String TAG = "RtspHevc";

    private final String host, user, pass;
    private final int channel, subtype;
    private final Listener listener;
    private volatile boolean running;
    private Thread thread;
    private Socket socket;

    // RTSP state
    private DataInputStream in;
    private OutputStream out;
    private int cseq = 1;
    private String realm, nonce, session;

    // decoder state
    private volatile MediaCodec codec;   // read by the render thread
    private volatile Surface surface;
    /** Decode in software (for small streams) so the TV's few hardware decoders stay free for bigger pictures. */
    volatile boolean preferSoftware;
    private Surface codecSurface;
    private final Object lock = new Object();
    private final ArrayList<byte[]> gop = new ArrayList<>();
    private final ArrayList<Long> gopPts = new ArrayList<>();
    private int gopBytes;
    private boolean gopValid, auHasKey;
    private byte[] vps, sps, pps;
    private boolean sawKey;
    private volatile boolean firstFrame;
    private final ByteArrayOutputStream au = new ByteArrayOutputStream(1 << 16);
    private long auTs = -1, auPtsUs;
    private ByteArrayOutputStream fu;
    private int videoW, videoH;
    /** Frames before this one (a replayed GOP) are decoded but not shown, so a new decoder starts on the newest picture. */
    private volatile long showFromUs;
    private long t0;
    private void mark(String what) { Log.d("RtspTime", "ch" + channel + "/" + subtype + " +" + (System.currentTimeMillis() - t0) + "ms " + what); }

    RtspHevcClient(String host, String user, String pass, int channel, int subtype, Listener l) {
        this.host = host;
        this.user = user;
        this.pass = pass;
        this.channel = channel;
        this.subtype = subtype;
        this.listener = l;
    }

    private String path;      // null: the live stream
    private String base;
    private final Object writeLock = new Object();
    private long lastTs = -1, mediaTicks;
    private volatile long mediaMs;

    /** Play the NVR's recording between two of its clock times (yyyy_MM_dd_HH_mm_ss) instead of the live stream. */
    RtspHevcClient playback(String from, String to) {
        path = "/cam/playback?channel=" + channel + "&subtype=" + subtype + "&starttime=" + from + "&endtime=" + to;
        return this;
    }

    /** Video time received so far, from the RTP clock: how far into a recording playback has got. */
    long mediaMs() { return mediaMs; }

    /** Blocking (network): pause or resume a recording. The answer is read by the stream's own thread. */
    void pause(boolean paused) {
        try { send(paused ? "PAUSE" : "PLAY", null); } catch (Exception e) { Log.w(TAG, "pause: " + e); }
    }

    void start(Surface s) {
        surface = s;
        t0 = System.currentTimeMillis();
        running = true;
        thread = new Thread(this::run, "rtsp-" + channel + "-" + subtype);
        thread.start();
    }

    void stop() {
        running = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            try { t.join(1500); } catch (InterruptedException ignored) { }
        }
        synchronized (lock) { releaseCodec(); }
    }

    /** Start (or resume) rendering to this surface; shows the buffered GOP immediately. */
    void attach(Surface s) { surface = s; }

    boolean hasGop() { synchronized (lock) { return gopValid; } }

    /** Stop rendering and free the hardware decoder; the network stream and GOP buffer stay alive. */
    void detach() {
        surface = null;                     // lets a feed waiting for an input buffer give up before we take the lock
        synchronized (lock) {
            releaseCodec();
            firstFrame = false;
        }
    }

    private void releaseCodec() {
        MediaCodec c = codec;
        codec = null;
        if (c != null) {
            try { c.stop(); } catch (Exception ignored) { }
            try { c.release(); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------ session

    private void run() {
        try {
            base = "rtsp://" + host + ":554" + (path != null ? path : "/cam/realmonitor?channel=" + channel + "&subtype=" + subtype);
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, 554), 5000);
            socket.setSoTimeout(5000);
            socket.setTcpNoDelay(true);
            mark("connected");
            in = new DataInputStream(new java.io.BufferedInputStream(socket.getInputStream(), 1 << 16));
            out = socket.getOutputStream();

            // DESCRIBE (challenge, then authenticated)
            Resp r = request("DESCRIBE", base, "Accept: application/sdp\r\n", false);
            mark("describe-1");
            if (r.code == 401) {
                realm = param(r.header("WWW-Authenticate"), "realm");
                nonce = param(r.header("WWW-Authenticate"), "nonce");
                r = request("DESCRIBE", base, "Accept: application/sdp\r\n", true);
            }
            if (r.code == 401) throw new IOException(NvrClient.ERR_LOGIN);
            if (r.code != 200) throw new IOException("DESCRIBE " + r.code);
            mark("describe-auth");
            String sdp = new String(r.body, "UTF-8");
            Log.d(TAG, "ch" + channel + "/" + subtype + " SDP:\n" + sdp.replace("\r", ""));
            parseSdp(sdp, base);

            r = request("SETUP", control, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n", true);
            if (r.code != 200) throw new IOException("SETUP " + r.code);
            mark("setup");
            String sess = r.header("Session");
            if (sess != null) session = sess.split(";")[0].trim();

            r = request("PLAY", base, "Range: npt=0.000-\r\n", true);
            if (r.code != 200) throw new IOException("PLAY " + r.code);
            mark("play");

            readLoop(base);
        } catch (Exception e) {
            if (running) {
                Log.w(TAG, "ch" + channel + "/" + subtype + " failed: " + e);
                listener.onError(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        } finally {
            running = false;
            releaseCodec();
            try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        }
    }

    private String control;

    private void parseSdp(String sdp, String base) {
        String[] lines = sdp.replace("\r", "").split("\n");
        boolean inVideo = false;
        control = base;
        for (String l : lines) {
            if (l.startsWith("m=")) inVideo = l.startsWith("m=video");
            if (!inVideo) continue;
            if (l.startsWith("a=control:")) {
                String c = l.substring(10).trim();
                if (c.startsWith("rtsp://")) control = c;
                else if (!c.equals("*")) control = base + (base.endsWith("/") ? "" : "/") + c;
            } else if (l.startsWith("a=fmtp:")) {
                vps = sprop(l, "sprop-vps");
                sps = sprop(l, "sprop-sps");
                pps = sprop(l, "sprop-pps");
            }
        }
    }

    private static byte[] sprop(String line, String key) {
        Matcher m = Pattern.compile(key + "=([A-Za-z0-9+/=]+)").matcher(line);
        return m.find() ? Base64.decode(m.group(1), Base64.DEFAULT) : null;
    }

    // ------------------------------------------------------------------ RTSP plumbing

    private static class Resp {
        int code;
        List<String> headers = new ArrayList<>();
        byte[] body = new byte[0];

        String header(String name) {
            for (String h : headers) {
                int i = h.indexOf(':');
                if (i > 0 && h.substring(0, i).equalsIgnoreCase(name)) return h.substring(i + 1).trim();
            }
            return null;
        }
    }

    private Resp request(String method, String uri, String extra, boolean auth) throws IOException {
        write(build(method, uri, extra, auth));
        return readResponse(null);
    }

    /** A request sent while the read loop owns the input (it reads the answer). */
    private void send(String method, String extra) throws IOException {
        if (out == null || session == null) throw new IOException("Not playing");
        write(build(method, base, extra, true));
    }

    private void write(String req) throws IOException {
        synchronized (writeLock) {
            out.write(req.getBytes("UTF-8"));
            out.flush();
        }
    }

    private String build(String method, String uri, String extra, boolean auth) {
        StringBuilder sb = new StringBuilder(method + " " + uri + " RTSP/1.0\r\nCSeq: " + nextSeq() + "\r\nUser-Agent: tvhome\r\n");
        if (session != null && !method.equals("DESCRIBE")) sb.append("Session: ").append(session).append("\r\n");
        if (auth && nonce != null) {
            String ha1 = md5(user + ":" + realm + ":" + pass), ha2 = md5(method + ":" + uri);
            sb.append("Authorization: Digest username=\"").append(user).append("\", realm=\"").append(realm)
                    .append("\", nonce=\"").append(nonce).append("\", uri=\"").append(uri)
                    .append("\", response=\"").append(md5(ha1 + ":" + nonce + ":" + ha2)).append("\"\r\n");
        }
        if (extra != null) sb.append(extra);
        sb.append("\r\n");
        return sb.toString();
    }

    private synchronized int nextSeq() { return cseq++; }

    /** Reads an RTSP response; if firstByte is non-null it was already consumed from the stream. */
    private Resp readResponse(Integer firstByte) throws IOException {
        Resp r = new Resp();
        String status = readLine(firstByte);
        String[] p = status.split(" ");
        if (p.length < 2 || !p[0].startsWith("RTSP")) throw new IOException("Bad response: " + status);
        r.code = Integer.parseInt(p[1]);
        String l;
        while (!(l = readLine(null)).isEmpty()) r.headers.add(l);
        String len = r.header("Content-Length");
        if (len != null) {
            r.body = new byte[Integer.parseInt(len.trim())];
            in.readFully(r.body);
        }
        return r;
    }

    private String readLine(Integer first) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b = first != null ? first : in.read();
        while (b != -1 && b != '\n') {
            if (b != '\r') sb.append((char) b);
            b = in.read();
        }
        if (b == -1) throw new IOException("Connection closed");
        return sb.toString();
    }

    // ------------------------------------------------------------------ RTP read loop

    private void readLoop(String base) throws IOException {
        long lastKeepalive = System.currentTimeMillis();
        byte[] buf = new byte[1 << 17];
        while (running) {
            int b;
            try {
                b = in.read();
            } catch (SocketTimeoutException e) {
                b = -2;
            }
            if (b == -1) throw new IOException("Stream ended");
            long now = System.currentTimeMillis();
            if (now - lastKeepalive > 20_000) {
                write("GET_PARAMETER " + base + " RTSP/1.0\r\nCSeq: " + nextSeq() + "\r\nSession: " + session + "\r\n\r\n");
                lastKeepalive = now;
            }
            if (b == -2) continue;
            if (b == '$') {
                int ch = in.read();
                int len = (in.read() << 8) | in.read();
                if (len > buf.length) buf = new byte[len];
                in.readFully(buf, 0, len);
                if (ch == 0) handleRtp(buf, len);
            } else if (b == 'R') {
                readResponse(b);
            }
        }
    }

    private void handleRtp(byte[] d, int len) {
        if (len < 14) return;
        int cc = d[0] & 0x0f;
        boolean ext = (d[0] & 0x10) != 0;
        boolean marker = (d[1] & 0x80) != 0;
        long ts = ((d[4] & 0xffL) << 24) | ((d[5] & 0xff) << 16) | ((d[6] & 0xff) << 8) | (d[7] & 0xff);
        int off = 12 + 4 * cc;
        if (ext && len >= off + 4) off += 4 + (((d[off + 2] & 0xff) << 8) | (d[off + 3] & 0xff)) * 4;
        if (off >= len) return;

        if (lastTs >= 0) {
            long dt = (int) (ts - lastTs);           // signed: the 32-bit clock wraps
            if (dt > 0 && dt < 90_000 * 10) mediaTicks += dt;
            else if (dt != 0 && path != null) Log.d(TAG, "ch" + channel + " RTP clock jumped " + dt / 90 + " ms");
        }
        lastTs = ts;
        mediaMs = mediaTicks / 90;

        if (auTs != -1 && ts != auTs) flushAu(); // new frame began without a marker on the last packet
        if (auTs == -1) auPtsUs = mediaTicks * 1000 / 90;
        auTs = ts;

        int type = (d[off] >> 1) & 0x3f;
        if (type == 48) { // aggregation packet
            int p = off + 2;
            while (p + 2 <= len) {
                int size = ((d[p] & 0xff) << 8) | (d[p + 1] & 0xff);
                p += 2;
                if (size <= 0 || p + size > len) break;
                addNal(d, p, size);
                p += size;
            }
        } else if (type == 49) { // fragmentation unit
            if (len < off + 4) return;
            int fuHeader = d[off + 2] & 0xff;
            boolean start = (fuHeader & 0x80) != 0, end = (fuHeader & 0x40) != 0;
            int fuType = fuHeader & 0x3f;
            if (start) {
                fu = new ByteArrayOutputStream(1 << 15);
                fu.write((d[off] & 0x81) | (fuType << 1));
                fu.write(d[off + 1]);
            }
            if (fu != null) {
                fu.write(d, off + 3, len - off - 3);
                if (end) {
                    byte[] nal = fu.toByteArray();
                    fu = null;
                    addNal(nal, 0, nal.length);
                }
            }
        } else {
            addNal(d, off, len - off);
        }
        if (marker) flushAu();
    }

    private void addNal(byte[] d, int off, int size) {
        if (size < 3) return;
        int type = (d[off] >> 1) & 0x3f;
        byte[] copy = null;
        if (type == 32) { copy = java.util.Arrays.copyOfRange(d, off, off + size); vps = copy; }
        else if (type == 33) { copy = java.util.Arrays.copyOfRange(d, off, off + size); sps = copy; }
        else if (type == 34) { copy = java.util.Arrays.copyOfRange(d, off, off + size); pps = copy; }
        if (type >= 16 && type <= 21) { auHasKey = true; if (!sawKey) { sawKey = true; mark("first-keyframe"); } }
        if (!sawKey) return; // wait for the first keyframe
        au.write(0); au.write(0); au.write(0); au.write(1);
        au.write(d, off, size);
    }

    private void flushAu() {
        auTs = -1;
        if (au.size() == 0) return;
        byte[] data = au.toByteArray();
        au.reset();
        boolean key = auHasKey;
        auHasKey = false;
        long pts = auPtsUs;
        synchronized (lock) {
            // keep the latest GOP (from the last keyframe) so a decoder can start instantly on attach
            if (key) { gop.clear(); gopPts.clear(); gopBytes = 0; gopValid = true; }
            if (gopValid) {
                gop.add(data);
                gopPts.add(pts);
                gopBytes += data.length;
                if (gopBytes > 8 << 20) { gop.clear(); gopPts.clear(); gopBytes = 0; gopValid = false; }
            }
            Surface s = surface;
            if (s == null) return;
            try {
                if (codec != null && codecSurface != s) { releaseCodec(); firstFrame = false; } // moved to another surface
                if (codec == null) {
                    if (!gopValid) return;
                    showFromUs = pts;
                    if (!createCodec(s)) return;
                    for (int i = 0; i < gop.size(); i++) feed(gop.get(i), gopPts.get(i), s);
                } else {
                    feed(data, pts, s);
                }
            } catch (Exception e) {
                Log.w(TAG, "decode error ch" + channel + ": " + e);
                if (running) { running = false; listener.onError("Decoder: " + e.getMessage()); }
            }
        }
    }

    /**
     * Queues one access unit, waiting while the decoder is full (the render thread holds frames until they are due,
     * so this is the jitter buffer pushing back). Dropping a frame here would smear the picture until the next keyframe.
     */
    private void feed(byte[] data, long ptsUs, Surface s) {
        int idx = -1;
        for (int i = 0; i < 100 && idx < 0 && surface == s && running; i++) idx = codec.dequeueInputBuffer(10_000);
        if (idx < 0) {
            if (surface == s && running) Log.w(TAG, "ch" + channel + "/" + subtype + " decoder full, frame dropped");
            return;
        }
        ByteBuffer b = codec.getInputBuffer(idx);
        if (b != null && data.length <= b.capacity()) {
            b.clear();
            b.put(data);
            codec.queueInputBuffer(idx, 0, data.length, ptsUs, 0);
        } else {
            codec.queueInputBuffer(idx, 0, 0, ptsUs, 0);
        }
    }

    private boolean createCodec(Surface target) throws IOException {
        if (vps == null || sps == null || pps == null) return false;
        int[] wh = parseSpsSize(sps);
        videoW = wh[0] > 0 ? wh[0] : 1920;
        videoH = wh[1] > 0 ? wh[1] : 1080;
        MediaFormat f = MediaFormat.createVideoFormat("video/hevc", videoW, videoH);
        ByteArrayOutputStream csd = new ByteArrayOutputStream();
        for (byte[] ps : new byte[][]{vps, sps, pps}) { csd.write(0); csd.write(0); csd.write(0); csd.write(1); csd.write(ps, 0, ps.length); }
        f.setByteBuffer("csd-0", ByteBuffer.wrap(csd.toByteArray()));
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 << 20);
        try { f.setInteger("low-latency", 1); } catch (Exception ignored) { }
        codec = preferSoftware ? MediaCodec.createByCodecName("c2.android.hevc.decoder") : MediaCodec.createDecoderByType("video/hevc");
        codec.configure(f, target, null, 0);
        codecSurface = target;
        codec.start();
        Log.d(TAG, "ch" + channel + "/" + subtype + " decoder " + codec.getName() + " " + videoW + "x" + videoH);
        mark("decoder-ready");
        listener.onVideoSize(videoW, videoH);
        final MediaCodec c = codec;
        Thread r = new Thread(() -> render(c), "render-" + channel + "-" + subtype);
        r.setPriority(Thread.MAX_PRIORITY);
        r.start();
        return true;
    }

    // ------------------------------------------------------------------ paced rendering

    private static final long MARGIN_NS = 80_000_000L;    // jitter cushion: frames are shown this long after arrival
    private static final long RESYNC_NS = 2_000_000_000L; // a frame due this far off means the clock restarted

    /**
     * Shows each decoded frame at its RTP time instead of the moment it is decoded. The NVR's frames arrive over TCP in
     * bursts (a big keyframe holds up the frames behind it), and a TextureView ignores render timestamps, so showing
     * them on arrival made the picture stall and then fast-forward. A frame that turns up late pushes the clock back;
     * when there is more cushion than needed the clock creeps forward again, at most 1/16 faster than real time.
     */
    private void render(MediaCodec c) {
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        long offsetNs = 0, lastPts = -1, statT = System.nanoTime(), maxLate = 0;
        int shown = 0, late = 0;
        boolean clock = false;
        try {
            while (codec == c) {
                int o = c.dequeueOutputBuffer(bi, 20_000);
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat nf = c.getOutputFormat();
                    int w = nf.getInteger(MediaFormat.KEY_WIDTH), h = nf.getInteger(MediaFormat.KEY_HEIGHT);
                    if (nf.containsKey("crop-right") && nf.containsKey("crop-left")) w = nf.getInteger("crop-right") - nf.getInteger("crop-left") + 1;
                    if (nf.containsKey("crop-bottom") && nf.containsKey("crop-top")) h = nf.getInteger("crop-bottom") - nf.getInteger("crop-top") + 1;
                    if (w != videoW || h != videoH) { videoW = w; videoH = h; listener.onVideoSize(w, h); }
                }
                if (o < 0) continue;
                long pts = bi.presentationTimeUs;
                if (pts < showFromUs) { c.releaseOutputBuffer(o, false); continue; }   // catching up through the GOP
                long now = System.nanoTime(), ptsNs = pts * 1000;
                if (!clock) { offsetNs = now - ptsNs + MARGIN_NS; clock = true; }
                long due = ptsNs + offsetNs, slack = due - now;
                if (slack < 0) {
                    late++;
                    maxLate = Math.max(maxLate, -slack);
                    offsetNs -= slack;                 // late: wait longer from now on rather than rushing to catch up
                    due = now;
                } else if (slack > MARGIN_NS + RESYNC_NS) {
                    offsetNs = now - ptsNs + MARGIN_NS;
                    due = now + MARGIN_NS;
                } else if (slack > MARGIN_NS && lastPts >= 0 && pts > lastPts) {
                    long step = Math.min((slack - MARGIN_NS) / 16, (pts - lastPts) * 1000 / 16);
                    offsetNs -= step;
                    due -= step;
                }
                lastPts = pts;
                while (codec == c) {
                    long wait = due - System.nanoTime();
                    if (wait <= 0) break;
                    Thread.sleep(Math.min(wait / 1_000_000, 50), (int) (Math.min(wait, 50_000_000L) % 1_000_000));
                }
                if (codec != c) break;
                c.releaseOutputBuffer(o, true);
                if (!firstFrame) { firstFrame = true; mark("FIRST-FRAME"); listener.onFirstFrame(); }
                shown++;
                if (now - statT >= 5_000_000_000L) {
                    Log.d(TAG, "ch" + channel + "/" + subtype + " 5s: shown=" + shown + " late=" + late + " maxLate=" + maxLate / 1_000_000
                            + "ms delay=" + (offsetNs - (now - ptsNs)) / 1_000_000 + "ms");
                    statT = now; shown = late = 0; maxLate = 0;
                }
            }
        } catch (Exception ignored) {
            // the codec was released under us (detach/stop)
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Width/height from an H.265 SPS NAL (with 2-byte header); {0,0} if it can't be parsed. */
    static int[] parseSpsSize(byte[] nal) {
        try {
            ByteArrayOutputStream rbsp = new ByteArrayOutputStream();
            for (int i = 2, zeros = 0; i < nal.length; i++) {
                if (zeros >= 2 && nal[i] == 3) { zeros = 0; continue; }
                zeros = nal[i] == 0 ? zeros + 1 : 0;
                rbsp.write(nal[i]);
            }
            Bits b = new Bits(rbsp.toByteArray());
            b.skip(4);
            int maxSubLayersMinus1 = b.bits(3);
            b.skip(1);
            if (maxSubLayersMinus1 != 0) return new int[]{0, 0};
            b.skip(96); // profile_tier_level (no sub-layers)
            b.ue();
            int chroma = b.ue();
            if (chroma == 3) b.skip(1);
            int w = b.ue(), h = b.ue();
            if (b.bits(1) == 1) {
                int l = b.ue(), r = b.ue(), t = b.ue(), bt = b.ue();
                int subW = (chroma == 1 || chroma == 2) ? 2 : 1, subH = chroma == 1 ? 2 : 1;
                w -= (l + r) * subW;
                h -= (t + bt) * subH;
            }
            return new int[]{w, h};
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }

    private static class Bits {
        final byte[] d;
        int pos;
        Bits(byte[] d) { this.d = d; }
        int bits(int n) { int v = 0; for (int i = 0; i < n; i++) v = (v << 1) | bit(); return v; }
        int bit() { int v = (d[pos >> 3] >> (7 - (pos & 7))) & 1; pos++; return v; }
        void skip(int n) { pos += n; }
        int ue() {
            int zeros = 0;
            while (bit() == 0 && zeros < 32) zeros++;
            return zeros == 0 ? 0 : (1 << zeros) - 1 + bits(zeros);
        }
    }

    private static String param(String header, String name) {
        if (header == null) return null;
        Matcher m = Pattern.compile("[\\s,]" + name + "=\"?([^\",]*)\"?").matcher(" " + header);
        return m.find() ? m.group(1) : null;
    }

    private static String md5(String s) {
        try {
            byte[] dg = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : dg) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
