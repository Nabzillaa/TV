package com.nabil.tvhome;

import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Streams a Baseus dual-lens camera: cloud lookup, MQTT wake (kept open), PPPP connection, XM login, open video,
 * heartbeat. Each lens' H.265 access units (Annex-B) are delivered to the listener; lens 1 is marked by 0x4000 in
 * the XM command field. Every step is timed and logged to tag BaseusStream (no secrets).
 */
final class BaseusStream {
    private static final String TAG = "BaseusStream";

    interface Listener {
        /** @param lens 0 or 1; @param key true if the access unit contains an IDR/parameter sets. */
        void onFrame(int lens, byte[] annexB, boolean key);
        void onState(String state);
        void onError(String message);
    }

    private final SharedPreferences prefs;
    private final Listener listener;
    private final int streamType;
    private volatile boolean running;
    private Thread thread;
    private BaseusWake wake;
    private Ppcs.Session session;

    BaseusStream(SharedPreferences prefs, int streamType, Listener l) {
        this.prefs = prefs;
        this.streamType = streamType;
        this.listener = l;
    }

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, "baseus-stream");
        thread.start();
    }

    void stop() {
        running = false;
        closeAll();
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) t.interrupt();
    }

    boolean isRunning() { return running; }

    private void closeAll() {
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        try { if (wake != null) wake.close(); } catch (Exception ignored) { }
        session = null;
        wake = null;
    }

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
                closeAll();
            }
            if (!running) break;
            try { Thread.sleep(backoff); } catch (InterruptedException e) { break; }
            backoff = Math.min(30_000, backoff * 2);
        }
        running = false;
    }

    private void runOnce() throws Exception {
        long t0 = System.currentTimeMillis();
        listener.onState("Signing in");
        BaseusCloud cloud = new BaseusCloud(prefs);
        if (!cloud.configured()) throw new IOException("Baseus login not set up");
        List<BaseusCloud.Camera> cams = cloud.cameras();
        if (cams.isEmpty()) throw new IOException("No camera on this Baseus account");
        final BaseusCloud.Camera cam = cams.get(0);
        JSONObject mq = cloud.mqttConfig();
        Log.i(TAG, "cloud ready +" + (System.currentTimeMillis() - t0) + "ms");

        listener.onState("Waking camera");
        wake = BaseusWake.wake(mq, cam, 30_000);
        Log.i(TAG, "wake confirmed +" + (System.currentTimeMillis() - t0) + "ms");
        if (!running) return;

        listener.onState("Connecting");
        session = Ppcs.connect(cam.did, cam.serviceString, 45_000, m -> Log.i(TAG, m + " +" + (System.currentTimeMillis() - t0) + "ms"));
        if (!running) return;
        Log.i(TAG, "peer established +" + (System.currentTimeMillis() - t0) + "ms");

        final BlockingQueue<Object[]> inbox = new LinkedBlockingQueue<>(512);
        final String[] closedWhy = {null};
        session.start(new Ppcs.Listener() {
            @Override public void onData(int channel, byte[] chunk) { inbox.offer(new Object[]{channel, chunk}); }
            @Override public void onClosed(String why) { closedWhy[0] = why; inbox.offer(new Object[]{-1, null}); }
        });

        XmCodec.Stream[] streams = new XmCodec.Stream[8];
        for (int i = 0; i < 8; i++) streams[i] = new XmCodec.Stream(cam.p2pPassword);
        session.send(0, XmCodec.command(XmCodec.CMD_LOGIN, XmCodec.loginPayload(cam.sn, cam.p2pPassword, prefs.getInt("bs_res", 1)), cam.p2pPassword));
        listener.onState("Logging in to camera");
        boolean loggedIn = false;
        int frames = 0;
        long statT = System.currentTimeMillis(), firstStamp = 0, firstArrive = 0, lastStamp = 0, maxQ = 0, framesWin = 0;
        long nextBeat = System.currentTimeMillis() + 3000, lastFrame = System.currentTimeMillis();

        while (running) {
            long now = System.currentTimeMillis();
            if (now >= nextBeat) { session.send(0, XmCodec.heartbeat()); nextBeat = now + 3000; }
            if (wake != null && !wake.isOpen() && loggedIn) Log.w(TAG, "MQTT connection dropped while streaming");
            Object[] item = inbox.poll(500, TimeUnit.MILLISECONDS);
            if (item == null) {
                if (loggedIn && System.currentTimeMillis() - lastFrame > 15_000) throw new IOException("Video stalled");
                if (!loggedIn && System.currentTimeMillis() - t0 > 90_000) throw new IOException("Camera did not answer the login");
                continue;
            }
            int ch = (Integer) item[0];
            if (ch < 0) throw new IOException("Connection lost: " + closedWhy[0]);
            for (XmCodec.Frame f : streams[ch].feed((byte[]) item[1])) {
                if (ch == 0 && f.command == XmCodec.CMD_LOGIN && !loggedIn) {
                    checkReply(f.payload);
                    loggedIn = true;
                    Log.i(TAG, "camera login accepted +" + (System.currentTimeMillis() - t0) + "ms");
                    session.send(0, XmCodec.command(XmCodec.CMD_OPEN_VIDEO, XmCodec.openVideoPayload(cam.sn, streamType), cam.p2pPassword));
                    listener.onState("Opening video");
                } else if (ch == 1 && loggedIn && (f.model == 1 || f.model == 2)) {
                    if (frames++ == 0) { Log.i(TAG, "first video frame +" + (System.currentTimeMillis() - t0) + "ms"); listener.onState("Live"); }
                    lastFrame = System.currentTimeMillis();
                    if (f.stamp != 0) {
                        if (firstStamp == 0) { firstStamp = f.stamp; firstArrive = lastFrame; }
                        lastStamp = f.stamp;
                    }
                    framesWin++;
                    maxQ = Math.max(maxQ, inbox.size());
                    if (lastFrame - statT >= 5000) {
                        long camAdvance = lastStamp - firstStamp, wallAdvance = lastFrame - firstArrive;
                        Log.i(TAG, "flow 5s: frames=" + framesWin + " inboxMax=" + maxQ + " stamp=" + lastStamp
                                + " stampSinceStart=" + camAdvance + " wallSinceStart=" + wallAdvance
                                + " arrivalLagVsCamera=" + (wallAdvance - camAdvance) + " (stamp unit assumed ms)");
                        statT = lastFrame; maxQ = 0; framesWin = 0;
                    }
                    int lens = (f.command & 0x4000) != 0 ? 1 : 0;
                    listener.onFrame(lens, f.payload, isKey(f.payload));
                }
            }
        }
    }

    /** XM replies carry result "ok"/true or a zero ret/code. */
    private static void checkReply(byte[] payload) throws IOException {
        try {
            JSONObject v = new JSONObject(new String(payload, java.nio.charset.StandardCharsets.UTF_8));
            for (String k : new String[]{"ret", "code", "errno"}) if (v.has(k) && Integer.parseInt(String.valueOf(v.get(k))) != 0) throw new IOException("Camera refused the login");
            Object r = v.opt("result");
            if (Boolean.FALSE.equals(r) || (r instanceof String && !"ok".equals(r))) throw new IOException("Camera refused the login");
        } catch (org.json.JSONException | NumberFormatException e) {
            throw new IOException("Unrecognised login reply");
        }
    }

    /** Picture size from the SPS in an access unit, or {0,0}. */
    static int[] spsSize(byte[] d) {
        for (int i = 0; i + 4 < d.length; i++) {
            if (d[i] == 0 && d[i + 1] == 0 && (d[i + 2] == 1 || (d[i + 2] == 0 && d[i + 3] == 1))) {
                int p = i + (d[i + 2] == 1 ? 3 : 4);
                if (p >= d.length) break;
                if (((d[p] >> 1) & 63) == 33) {
                    int end = d.length;
                    for (int j = p + 1; j + 3 < d.length; j++) if (d[j] == 0 && d[j + 1] == 0 && (d[j + 2] == 1 || (d[j + 2] == 0 && d[j + 3] == 1))) { end = j; break; }
                    return RtspHevcClient.parseSpsSize(java.util.Arrays.copyOfRange(d, p, end));
                }
                i = p;
            }
        }
        return new int[]{0, 0};
    }

    /** True if the access unit contains parameter sets or an IDR picture (HEVC NAL types 32-34, 19-20). */
    static boolean isKey(byte[] d) {
        for (int i = 0; i + 4 < d.length && i < 400_000; i++) {
            if (d[i] == 0 && d[i + 1] == 0 && (d[i + 2] == 1 || (d[i + 2] == 0 && d[i + 3] == 1))) {
                int p = i + (d[i + 2] == 1 ? 3 : 4);
                if (p >= d.length) break;
                int t = (d[p] >> 1) & 63;
                if (t == 32 || t == 33 || t == 34 || t == 19 || t == 20) return true;
                i = p;
            }
        }
        return false;
    }
}
