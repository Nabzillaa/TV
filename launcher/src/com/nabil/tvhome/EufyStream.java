package com.nabil.tvhome;

import android.content.SharedPreferences;
import android.util.Log;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Streams cameras behind a Eufy HomeBase over the LAN: cloud lookup, local P2P connection, command key setup,
 * start-livestream per camera, and decoding of the video frame container (including its RSA-wrapped AES key).
 * Log tag EufyStream (no secrets).
 */
final class EufyStream {
    private static final String TAG = "EufyStream";

    interface Listener {
        void onFrame(int cam, byte[] annexB, boolean key, boolean hevc, int w, int h);
        /** Return code of the start command for a camera: 0 = streaming, -201 = camera unavailable. */
        void onCameraResult(int cam, int returnCode);
        void onState(String state);
        void onError(String message);
    }

    private final SharedPreferences prefs;
    private volatile int wanted = -1;       // camera channel that should be streaming (-1 = none)
    private volatile int active = -1;       // camera channel currently streaming
    private final Listener listener;
    private volatile boolean running;
    private Thread thread;
    private Ppcs.Session session;
    private volatile byte[] liveKey;
    private volatile int liveEnc;
    private volatile boolean liveStarted;
    // Frames above ~64 KB arrive as several video messages: the first starts with a NAL start code, the rest are
    // continuations. A frame is only delivered once the next one begins.
    private Frame pending;
    private java.io.ByteArrayOutputStream pendingBuf;
    private int pendingCam;

    private static boolean startsWithStartCode(byte[] d) {
        return d.length >= 4 && d[0] == 0 && d[1] == 0 && (d[2] == 1 || (d[2] == 0 && d[3] == 1));
    }

    private void assemble(int cam, Frame f) {
        if (startsWithStartCode(f.data)) {
            flushPending();
            pending = f;
            pendingCam = cam;
            pendingBuf = new java.io.ByteArrayOutputStream(Math.max(65536, f.data.length * 2));
            pendingBuf.write(f.data, 0, f.data.length);
        } else if (pending != null && cam == pendingCam) {
            pendingBuf.write(f.data, 0, f.data.length);
        }
    }

    private void flushPending() {
        Frame f = pending;
        if (f == null) return;
        pending = null;
        byte[] all = pendingBuf.toByteArray();
        pendingBuf = null;
        listener.onFrame(pendingCam, all, f.key, f.hevc, f.w, f.h);
    }

    EufyStream(SharedPreferences prefs, int initialCamera, Listener l) {
        this.prefs = prefs;
        this.wanted = initialCamera;
        this.listener = l;
    }

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, "eufy-stream");
        thread.start();
    }

    void stop() {
        running = false;
        Ppcs.Session s = session;
        if (s != null && liveStarted && liveKey != null) {
            // tell the cameras to stop streaming so battery cameras go back to sleep
            int cam = active;
            if (cam >= 0) {
                try { s.send(0, EufyXzyh.intCommandEnc(EufyXzyh.CMD_STOP_REALTIME_MEDIA, cam, cam, liveEnc, liveKey)); } catch (Exception ignored) { }
            }
            try { Thread.sleep(150); } catch (InterruptedException ignored) { }
        }
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) t.interrupt();
    }

    boolean isRunning() { return running; }

    /** Choose which camera streams. The HomeBase streams one camera at a time, so this stops the old one first. */
    void watch(int camChannel) { wanted = camChannel; }

    int watching() { return wanted; }

    private void run() {
        long backoff = 3000;
        while (running) {
            try {
                runOnce();
                backoff = 3000;
            } catch (Exception e) {
                if (!running) break;
                Log.w(TAG, "stream failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                listener.onError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            } finally {
                try { if (session != null) session.close(); } catch (Exception ignored) { }
                session = null;
            }
            if (!running) break;
            try { Thread.sleep(backoff); } catch (InterruptedException e) { break; }
            backoff = Math.min(30_000, backoff * 2);
        }
        running = false;
    }

    // ------------------------------------------------------------------ one streaming session

    private void runOnce() throws Exception {
        final long t0 = System.currentTimeMillis();
        listener.onState("Signing in");
        EufyCloud cloud = new EufyCloud(prefs);
        if (!cloud.configured()) throw new IOException("Eufy login not set up");
        if (!cloud.hasToken()) {
            EufyCloud.LoginResult lr = cloud.login(null, null, null);
            if (lr.status != EufyCloud.Status.OK) throw new IOException("Eufy needs a captcha or email code: run ./eufy-setup.sh test");
        }
        List<EufyCloud.Station> stations = cloud.stations();
        if (stations.isEmpty()) throw new IOException("No Eufy HomeBase on this account");
        EufyCloud.Station st = stations.get(0);
        StringBuilder list = new StringBuilder();
        for (EufyCloud.Camera c : st.cameras) list.append(c.channel).append(':').append(c.name.replace(";", ",").replace(":", "-")).append(';');
        prefs.edit().putString("eu_cams", list.toString()).apply();
        Log.i(TAG, "cloud ready +" + (System.currentTimeMillis() - t0) + "ms");

        listener.onState("Connecting");
        session = Ppcs.connectLan(st.did, 20_000, m -> Log.i(TAG, m + " +" + (System.currentTimeMillis() - t0) + "ms"));
        if (!running) return;

        final BlockingQueue<Object[]> inbox = new LinkedBlockingQueue<>(2048);
        final String[] closed = {null};
        session.start(new Ppcs.Listener() {
            @Override public void onData(int ch, byte[] chunk) { inbox.offer(new Object[]{ch, chunk}); }
            @Override public void onClosed(String why) { closed[0] = why; inbox.offer(new Object[]{-1, null}); }
        });
        EufyXzyh.Reader[] readers = new EufyXzyh.Reader[8];
        for (int i = 0; i < 8; i++) readers[i] = new EufyXzyh.Reader();

        // RSA key pair for this session: its public half goes into the start command, the camera wraps its AES key with it.
        KeyPairGenerator kg = KeyPairGenerator.getInstance("RSA");
        kg.initialize(1024);
        KeyPair rsa = kg.generateKeyPair();
        String pubHex = modulusHex((RSAPublicKey) rsa.getPublic());

        session.send(0, EufyXzyh.voidCommand(EufyXzyh.CMD_GATEWAYINFO, 255));
        listener.onState("Securing");
        byte[] p2pKey = null;
        String startJson = null;
        active = -1;
        int encType = 0;
        boolean started = false;
        int framesTotal = 0;
        long lastFrame = System.currentTimeMillis(), nextPing = System.currentTimeMillis() + 5000;
        long statT = System.currentTimeMillis();
        int stFrames = 0, stKeys = 0; long stBytes = 0; int[] stChan = new int[8];

        while (running) {
            Object[] item = inbox.poll(500, TimeUnit.MILLISECONDS);
            long now = System.currentTimeMillis();
            if (started && p2pKey != null && wanted != active) {
                int old = active, next = wanted;
                if (old >= 0) {
                    session.send(0, EufyXzyh.intCommandEnc(EufyXzyh.CMD_STOP_REALTIME_MEDIA, old, old, encType, p2pKey));
                    Log.i(TAG, "stop-livestream sent for camera channel " + old);
                }
                if (next >= 0) {
                    session.send(0, EufyXzyh.stringCommand(EufyXzyh.CMD_SET_PAYLOAD, next, startJson, encType, p2pKey));
                    Log.i(TAG, "start-livestream sent for camera channel " + next);
                    lastFrame = now;
                }
                active = next;
                framesTotal = 0;
                pending = null;
                pendingBuf = null;
            }
            if (now >= nextPing && started) { nextPing = now + 5000; }
            if (now - statT >= 5000) {
                Log.i(TAG, "flow 5s: videoFrames=" + stFrames + " keyframes=" + stKeys + " frameBytes=" + stBytes + " bytesPerChannel=" + Arrays.toString(stChan) + " inbox=" + inbox.size());
                statT = now; stFrames = stKeys = 0; stBytes = 0; stChan = new int[8];
            }
            if (item == null) {
                if (started && active >= 0 && now - lastFrame > 20_000) throw new IOException("Video stalled");
                if (!started && now - t0 > 40_000) throw new IOException("HomeBase did not accept the stream request");
                continue;
            }
            int ch = (Integer) item[0];
            if (ch < 0) throw new IOException("Connection lost: " + closed[0]);
            stChan[ch] += ((byte[]) item[1]).length;
            for (EufyXzyh.Message m : readers[ch].feed((byte[]) item[1])) {
                if (m.cmd == EufyXzyh.CMD_GATEWAYINFO && p2pKey == null) {
                    Object[] k = deriveKey(cloud, st, m.body, (RSAPrivateKey) null);
                    p2pKey = (byte[]) k[0];
                    encType = (Integer) k[1];
                    int ov = prefs.getInt("eu_enc_override", -1);
                    if (ov >= 0) encType = ov;
                    Log.i(TAG, "command key ready (level " + encType + ") +" + (System.currentTimeMillis() - t0) + "ms");
                    startJson = "{\"account_id\":\"" + st.adminUserId + "\",\"cmd\":1003,\"mValue3\":1003,\"payload\":{\"ClientOS\":\"Android\",\"key\":\""
                            + pubHex + "\",\"streamtype\":1}}";
                    started = true;
                    liveKey = p2pKey; liveEnc = encType; liveStarted = true;
                    listener.onState("Starting video");
                } else if (m.cmd == EufyXzyh.CMD_SET_PAYLOAD && m.type == 1 && m.body.length >= 4) {
                    int rc = (m.body[0] & 255) | ((m.body[1] & 255) << 8) | ((m.body[2] & 255) << 16) | ((m.body[3] & 255) << 24);
                    Log.i(TAG, "camera channel " + m.channel + " start result " + rc);
                    listener.onCameraResult(m.channel, rc);
                } else if (m.cmd == EufyXzyh.CMD_VIDEO_FRAME) {
                    Frame f = decodeFrame(m, (RSAPrivateKey) rsa.getPrivate());
                    if (f == null) continue;
                    if (framesTotal++ == 0) { Log.i(TAG, "first video frame (" + f.w + "x" + f.h + ", " + (f.hevc ? "H.265" : "H.264") + ") +" + (System.currentTimeMillis() - t0) + "ms"); listener.onState("Live"); }
                    lastFrame = System.currentTimeMillis();
                    stFrames++; stBytes += f.data.length; if (f.key) stKeys++;
                    assemble(m.channel, f);
                } else if (m.cmd != EufyXzyh.CMD_AUDIO_FRAME && m.cmd != EufyXzyh.CMD_PING) {
                    String detail = "";
                    if (m.body.length >= 4) {
                        byte[] b = m.body;
                        if (m.sign > 0 && p2pKey != null && b.length % 16 == 0) {
                            try {
                                Cipher ac = Cipher.getInstance("AES/ECB/NoPadding");
                                ac.init(Cipher.DECRYPT_MODE, new SecretKeySpec(p2pKey, "AES"));
                                b = ac.doFinal(b);
                            } catch (Exception ignored) { }
                        }
                        int rc = (b[0] & 255) | ((b[1] & 255) << 8) | ((b[2] & 255) << 16) | ((b[3] & 255) << 24);
                        StringBuilder hx = new StringBuilder(); for (int q = 4; q < Math.min(b.length, 28); q++) hx.append(String.format("%02x", b[q])); detail = ", returnCode=" + rc + " (decrypted=" + (b != m.body) + ") then " + hx;
                    }
                    Log.i(TAG, "message on channel " + ch + ": command " + m.cmd + ", " + m.body.length + " bytes, sign=" + m.sign + ", type=" + m.type + detail);
                }
            }
        }
    }

    // ------------------------------------------------------------------ keys

    private static String modulusHex(RSAPublicKey k) {
        byte[] n = k.getModulus().toByteArray();
        if (n.length == 129 && n[0] == 0) n = Arrays.copyOfRange(n, 1, 129);
        StringBuilder sb = new StringBuilder();
        for (byte b : n) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Level 2: key = RSA-decrypted blob from the HomeBase (cipher from the cloud). Level 1 fallback: derived from the serial and ID. */
    private Object[] deriveKey(EufyCloud cloud, EufyCloud.Station st, byte[] body, RSAPrivateKey unused) {
        try {
            int cipherId = (body[0] & 255) | ((body[1] & 255) << 8);
            String pk = cloud.cipherPrivateKey(cipherId, st.adminUserId);
            if (pk != null && !pk.isEmpty()) {
                String clean = pk.replace("-----BEGIN RSA PRIVATE KEY-----", "").replace("-----END RSA PRIVATE KEY-----", "").replace("\n", "");
                java.security.PrivateKey key = java.security.KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(android.util.Base64.decode(clean, android.util.Base64.DEFAULT)));
                byte[] raw = Arrays.copyOfRange(body, 4, body.length);
                int nul = 0;
                while (nul < raw.length && raw[nul] != 0) nul++;
                byte[][] candidates = {Arrays.copyOf(raw, nul), Arrays.copyOf(raw, Math.min(128, raw.length))};
                for (byte[] c : candidates) {
                    try {
                        Cipher rc = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                        rc.init(Cipher.DECRYPT_MODE, key);
                        byte[] k = rc.doFinal(c);
                        if (k.length == 16) return new Object[]{k, 2};
                    } catch (Exception ignored) { }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "cipher step failed (" + e.getClass().getSimpleName() + ": " + e.getMessage() + "), using level 1");
        }
        String did = st.did;
        int dash = did.indexOf('-');
        String k1 = st.sn.substring(st.sn.length() - 7) + did.substring(dash, dash + 9);
        return new Object[]{k1.getBytes(StandardCharsets.US_ASCII), 1};
    }

    // ------------------------------------------------------------------ video frame container

    private static final class Frame {
        byte[] data;
        boolean key, hevc;
        int w, h;
    }

    private static int diagCount;

    private static Frame decodeFrame(EufyXzyh.Message m, RSAPrivateKey rsa) {
        byte[] d = m.body;
        if (d.length < 23) return null;
        long dataLen = (d[0] & 255L) | ((d[1] & 255L) << 8) | ((d[2] & 255L) << 16) | ((d[3] & 255L) << 24);
        Frame f = new Frame();
        f.key = d[4] == 1;
        int streamType = d[5] & 255;
        f.w = (d[10] & 255) | ((d[11] & 255) << 8);
        f.h = (d[12] & 255) | ((d[13] & 255) << 8);
        f.hevc = streamType == 2;
        int start = 22;
        byte[] aes = null;
        try {
            if (m.sign > 0 && dataLen >= 128) {
                Cipher rc = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                rc.init(Cipher.DECRYPT_MODE, rsa);
                aes = rc.doFinal(Arrays.copyOfRange(d, 22, 150));
                start = 151;
            }
            int len = (int) Math.min(dataLen, d.length - start);
            if (len <= 0) return null;
            if (aes != null && aes.length == 16) {
                Cipher ac = Cipher.getInstance("AES/ECB/NoPadding");
                ac.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aes, "AES"));
                int head = Math.min(128, len);
                head -= head % 16;
                byte[] out = new byte[len];
                System.arraycopy(ac.doFinal(d, start, head), 0, out, 0, head);
                System.arraycopy(d, start + head, out, head, len - head);
                f.data = out;
            } else {
                f.data = Arrays.copyOfRange(d, start, start + len);
            }
        } catch (Exception e) {
            return null;
        }
        if (f.key && diagCount < 4) {
            diagCount++;
            StringBuilder nals = new StringBuilder();
            byte[] x = f.data;
            int last = -1, lastType = -1, n = 0;
            for (int i = 0; i + 3 < x.length; i++) {
                if (x[i] == 0 && x[i + 1] == 0 && (x[i + 2] == 1 || (x[i + 2] == 0 && x[i + 3] == 1))) {
                    int p = i + (x[i + 2] == 1 ? 3 : 4);
                    if (last >= 0 && n < 16) nals.append(lastType).append(':').append(i - last).append(' ');
                    if (p < x.length) { lastType = f.hevc ? (x[p] >> 1) & 63 : x[p] & 31; last = p; n++; }
                    i = p;
                }
            }
            if (last >= 0 && n < 17) nals.append(lastType).append(':').append(x.length - last).append(" (last)");
            StringBuilder tail = new StringBuilder();
            for (int q = Math.max(0, x.length - 8); q < x.length; q++) tail.append(String.format("%02x", x[q]));
            android.util.Log.i("EufyStream", "keyframe diag: dataLen=" + dataLen + " bodyLen=" + d.length + " start=" + start + " sign=" + m.sign
                    + " usedLen=" + x.length + " nals=[" + nals + "] tail=" + tail);
        }
        // an H.264/H.265 hint of 0 is possible: detect from the first NAL
        if (streamType != 1 && streamType != 2) f.hevc = looksHevc(f.data);
        return f;
    }

    private static boolean looksHevc(byte[] d) {
        for (int i = 0; i + 4 < d.length && i < 200; i++) {
            if (d[i] == 0 && d[i + 1] == 0 && (d[i + 2] == 1 || (d[i + 2] == 0 && d[i + 3] == 1))) {
                int p = i + (d[i + 2] == 1 ? 3 : 4);
                int t = (d[p] >> 1) & 63;
                return t >= 32 && t <= 34;
            }
        }
        return false;
    }
}
