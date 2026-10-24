package com.nabil.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Receives the Eufy login from ./eufy-setup.sh and runs staged self-tests; log tag EufyTest (no secrets).
 *   --es eufy_b64 <base64 "email|password|COUNTRY">   store the login        (or: clear)
 *   --es eufy_test cloud                               sign in (or report the challenge) and list cameras
 *   --es eufy_test captcha:<text>                      answer the captcha shown in the saved image
 *   --es eufy_test code:<digits>                       enter the emailed verification code
 */
final class EufySetup {
    private static final String TAG = "EufyTest";

    private EufySetup() { }

    static boolean handle(final Context ctx, final SharedPreferences prefs, Intent i) {
        if (i == null) return false;
        String b64 = i.getStringExtra("eufy_b64");
        String test = i.getStringExtra("eufy_test");
        String hide = i.getStringExtra("eufy_hide");
        if (hide != null) {
            i.removeExtra("eufy_hide");
            prefs.edit().putString("eu_hide", hide.equals("none") ? "" : hide).apply();
            Log.i(TAG, "hidden Eufy camera channels: " + (hide.equals("none") ? "(none)" : hide));
            toast(ctx, "Eufy camera list updated");
        }
        int enc = i.getIntExtra("eufy_enc", -9);
        if (enc != -9) { prefs.edit().putInt("eu_enc_override", enc).apply(); i.removeExtra("eufy_enc"); Log.i(TAG, "command encryption override " + enc); }
        if (b64 != null) {
            i.removeExtra("eufy_b64");
            if (b64.equals("clear")) {
                SharedPreferences.Editor e = prefs.edit();
                for (String k : new String[]{"eu_user", "eu_pass", "eu_country", "eu_priv", "eu_token", "eu_expires", "eu_uid", "eu_server_pub", "eu_base", "eu_captcha_id", "eu_wait", "eu_cams", "eu_bad", "eu_enc_override"}) e.remove(k);
                e.apply();
                toast(ctx, "Eufy login removed");
            } else {
                try {
                    String[] f = new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8).split("\\|", -1);
                    if (f.length < 2 || f[0].isEmpty() || f[1].isEmpty()) throw new IllegalArgumentException();
                    prefs.edit().putString("eu_user", f[0]).putString("eu_pass", f[1])
                            .putString("eu_country", f.length > 2 && !f[2].isEmpty() ? f[2] : "AU")
                            .remove("eu_token").remove("eu_expires").remove("eu_uid").remove("eu_base").remove("eu_server_pub").remove("eu_captcha_id").remove("eu_wait").apply();
                    toast(ctx, "Eufy login saved");
                } catch (Exception e) {
                    toast(ctx, "Eufy login not understood");
                }
            }
        }
        if (test != null) {
            i.removeExtra("eufy_test");
            final String which = test;
            new Thread(() -> run(ctx, prefs, which), "eufy-test").start();
        }
        return b64 != null || test != null;
    }

    private static void run(Context ctx, SharedPreferences prefs, String which) {
        EufyCloud cloud = new EufyCloud(prefs);
        if (!cloud.configured()) { Log.w(TAG, "no Eufy login saved"); return; }
        long t0 = System.currentTimeMillis();
        try {
            EufyCloud.LoginResult lr = null;
            if (which.startsWith("captcha:")) {
                lr = cloud.login(null, prefs.getString("eu_captcha_id", ""), which.substring(8).trim());
            } else if (which.startsWith("code:")) {
                lr = cloud.login(which.substring(5).trim(), null, null);
            } else if (!cloud.hasToken() || "code".equals(prefs.getString("eu_wait", null))) {   // also restart a half-finished sign-in
                lr = cloud.login(null, null, null);
            }
            if (lr != null && lr.status == EufyCloud.Status.NEED_CAPTCHA) {
                File dir = ctx.getExternalFilesDir(null);
                String data = lr.captchaImageB64 == null ? "" : lr.captchaImageB64;
                int comma = data.indexOf(',');
                if (data.startsWith("data:") && comma > 0) data = data.substring(comma + 1);
                File f = new File(dir, "eufy-captcha.png");
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(Base64.decode(data, Base64.DEFAULT)); }
                Log.i(TAG, "CAPTCHA REQUIRED: image saved (" + f.length() + " bytes). Run ./eufy-setup.sh captcha");
                return;
            }
            if (lr != null && lr.status == EufyCloud.Status.NEED_CODE) {
                Log.i(TAG, "VERIFICATION CODE REQUIRED: Eufy has emailed you a code. Run ./eufy-setup.sh code <digits>");
                return;
            }
            Log.i(TAG, "signed in (+" + (System.currentTimeMillis() - t0) + "ms)");
            List<EufyCloud.Station> st = cloud.stations();
            Log.i(TAG, "stations=" + st.size());
            for (EufyCloud.Station s : st) {
                Log.i(TAG, "station '" + s.name + "' model=" + s.model + " lanIp=" + s.ip + " haveDid=" + !s.did.isEmpty()
                        + " haveAppConn=" + !s.appConn.isEmpty() + " cameras=" + s.cameras.size());
                for (EufyCloud.Camera c : s.cameras) Log.i(TAG, "  camera '" + c.name + "' model=" + c.model + " channel=" + c.channel);
                String[] k = cloud.dskKey(s.sn);
                Log.i(TAG, "  dsk key received (len " + k[0].length() + "), expires in " + (Long.parseLong(k[1]) - System.currentTimeMillis() / 1000) + "s");
            }
            if (which.equals("connect")) { connectTest(cloud, st); return; }
            if (which.startsWith("video")) { videoTest(prefs, which.length() > 5 ? Integer.parseInt(which.substring(5)) : 0); return; }
            Log.i(TAG, "STAGE 1 PASSED");
        } catch (EufyCloud.AuthException e) {
            Log.e(TAG, "STAGE FAILED: sign-in rejected: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "STAGE FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Stage 2: find the HomeBase on the LAN, connect, send GATEWAYINFO and wait for any reply. */
    private static void connectTest(EufyCloud cloud, List<EufyCloud.Station> stations) throws Exception {
        EufyCloud.Station st = stations.get(0);
        Log.i(TAG, "station firmware " + st.raw.optString("main_sw_version") + ", did present=" + !st.did.isEmpty()
                + ", adminUser present=" + !st.adminUserId.isEmpty());
        final long t0 = System.currentTimeMillis();
        Ppcs.Session s = Ppcs.connectLan(st.did, 20_000, m -> Log.i(TAG, m + " +" + (System.currentTimeMillis() - t0) + "ms"));
        final java.util.concurrent.BlockingQueue<String> events = new java.util.concurrent.LinkedBlockingQueue<>();
        final EufyXzyh.Reader[] readers = new EufyXzyh.Reader[8];
        for (int i = 0; i < 8; i++) readers[i] = new EufyXzyh.Reader();
        s.start(new Ppcs.Listener() {
            @Override public void onData(int ch, byte[] chunk) {
                for (EufyXzyh.Message m : readers[ch].feed(chunk))
                    events.offer("reply on channel " + ch + ": command " + m.cmd + ", body " + m.body.length + " bytes, sign=" + m.sign);
            }
            @Override public void onClosed(String why) { events.offer("closed: " + why); }
        });
        s.send(0, EufyXzyh.voidCommand(EufyXzyh.CMD_GATEWAYINFO, 255));
        Log.i(TAG, "sent GATEWAYINFO +" + (System.currentTimeMillis() - t0) + "ms");
        long end = System.currentTimeMillis() + 10_000;
        int got = 0;
        while (System.currentTimeMillis() < end) {
            String e = events.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (e != null) { Log.i(TAG, e); got++; }
        }
        s.close();
        Log.i(TAG, got > 0 ? "STAGE 2 PASSED (HomeBase answered)" : "STAGE FAILED: connected but no reply to GATEWAYINFO");
    }

    /** Stage 3: stream one camera channel for 20 s and count frames. */
    private static void videoTest(SharedPreferences prefs, final int channel) throws InterruptedException {
        final long t0 = System.currentTimeMillis();
        final int[] frames = {0}, keys = {0}, wh = {0, 0};
        final long[] bytes = {0};
        final boolean[] hevc = {false};
        final String[] state = {""};
        EufyStream es = new EufyStream(prefs, channel, new EufyStream.Listener() {
            @Override public void onFrame(int cam, byte[] d, boolean key, boolean h, int w, int ht) {
                synchronized (frames) { frames[0]++; bytes[0] += d.length; if (key) keys[0]++; wh[0] = w; wh[1] = ht; hevc[0] = h; }
            }
            @Override public void onCameraResult(int cam, int rc) { Log.i(TAG, "camera " + cam + " start result " + rc); }
            @Override public void onState(String st) { state[0] = st; Log.i(TAG, "state: " + st + " (+" + (System.currentTimeMillis() - t0) + "ms)"); }
            @Override public void onError(String m) { Log.e(TAG, "error: " + m); }
        });
        es.start();
        long limit = t0 + 60_000;
        while (System.currentTimeMillis() < limit && es.isRunning() && !state[0].equals("Live")) Thread.sleep(500);
        if (!state[0].equals("Live")) { es.stop(); Log.e(TAG, "STAGE FAILED: no video (last state: " + state[0] + ")"); return; }
        long watch = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < watch && es.isRunning()) Thread.sleep(500);
        es.stop();
        synchronized (frames) {
            Log.i(TAG, "camera channel " + channel + ": frames=" + frames[0] + " keyframes=" + keys[0] + " bytes=" + bytes[0]
                    + " picture=" + wh[0] + "x" + wh[1] + " codec=" + (hevc[0] ? "H.265" : "H.264"));
            Log.i(TAG, frames[0] > 60 ? "STAGE 3 PASSED (video flowing)" : "STAGE FAILED: too few frames");
        }
    }

    private static void toast(final Context ctx, final String m) {
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(() -> Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show());
    }
}
