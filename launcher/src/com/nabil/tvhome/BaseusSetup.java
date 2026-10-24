package com.nabil.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Receives the Baseus login from ./baseus-setup.sh (base64 of "email|password|COUNTRY", so no shell quoting
 * problems) and runs staged self-tests. Logs go to logcat tag BaseusTest and never contain secrets.
 *
 *   am start -n com.nabil.tvhome/.HomeActivity --es baseus_b64 <b64>      store the login
 *   am start -n com.nabil.tvhome/.HomeActivity --es baseus_b64 clear      forget it
 *   am start -n com.nabil.tvhome/.HomeActivity --es baseus_test cloud     stage 1: sign in + list cameras
 */
final class BaseusSetup {
    private static final String TAG = "BaseusTest";

    private BaseusSetup() { }

    /** @return true if the intent carried a Baseus command. */
    static boolean handle(final Context ctx, final SharedPreferences prefs, Intent i) {
        if (i == null) return false;
        String b64 = i.getStringExtra("baseus_b64");
        String test = i.getStringExtra("baseus_test");
        int res = i.getIntExtra("baseus_res", -1);
        if (res >= 0) { prefs.edit().putInt("bs_res", res).apply(); i.removeExtra("baseus_res"); Log.i(TAG, "login res set to " + res); }
        if (b64 != null) {
            i.removeExtra("baseus_b64");
            if (b64.equals("clear")) {
                prefs.edit().remove("bs_user").remove("bs_pass").remove("bs_country").remove("bs_token")
                        .remove("bs_xm").remove("bs_tel").apply();
                toast(ctx, "Baseus login removed");
            } else {
                try {
                    String[] f = new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8).split("\\|", -1);
                    if (f.length < 2 || f[0].isEmpty() || f[1].isEmpty()) throw new IllegalArgumentException();
                    prefs.edit().putString("bs_user", f[0]).putString("bs_pass", f[1])
                            .putString("bs_country", f.length > 2 && !f[2].isEmpty() ? f[2] : "AU")
                            .remove("bs_token").remove("bs_xm").remove("bs_tel").apply();
                    toast(ctx, "Baseus login saved");
                } catch (Exception e) {
                    toast(ctx, "Baseus login not understood");
                }
            }
        }
        if (test != null) {
            i.removeExtra("baseus_test");
            final String which = test;
            new Thread(() -> runTest(ctx, prefs, which), "baseus-test").start();
        }
        return b64 != null || test != null;
    }

    private static void runTest(Context ctx, SharedPreferences prefs, String which) {
        BaseusCloud cloud = new BaseusCloud(prefs);
        if (!cloud.configured()) { Log.w(TAG, "no Baseus login saved"); return; }
        long t0 = System.currentTimeMillis();
        try {
            if (which.equals("cloud")) {
                List<BaseusCloud.Camera> cams = cloud.cameras();
                Log.i(TAG, "cloud OK in " + (System.currentTimeMillis() - t0) + "ms; cameras=" + cams.size());
                for (BaseusCloud.Camera c : cams) {
                    Log.i(TAG, "camera '" + c.name + "' lenses=" + c.lenses + " p2pConnect=" + c.p2pConnect
                            + " haveDid=" + !c.did.isEmpty() + " haveService=" + !c.serviceString.isEmpty()
                            + " havePassword=" + !c.p2pPassword.isEmpty() + " haveWakeKey=" + !c.wakeKey.isEmpty());
                }
                org.json.JSONObject mq = cloud.mqttConfig();
                Log.i(TAG, "mqtt config OK: port=" + mq.optInt("port") + " haveHost=" + !mq.optString("host").isEmpty()
                        + " haveTopics=" + (!mq.optString("publish").isEmpty() && !mq.optString("subscribe").isEmpty()));
                Log.i(TAG, "STAGE 1 PASSED");
            } else if (which.equals("wake")) {
                List<BaseusCloud.Camera> cams = cloud.cameras();
                org.json.JSONObject mq = cloud.mqttConfig();
                long w0 = System.currentTimeMillis();
                BaseusWake w = BaseusWake.wake(mq, cams.get(0), 30_000);
                Log.i(TAG, "wake confirmed in " + (System.currentTimeMillis() - w0) + "ms; connection open=" + w.isOpen());
                Thread.sleep(15_000);
                Log.i(TAG, "after 15s still open=" + w.isOpen());
                w.close();
                Log.i(TAG, "STAGE 2 PASSED");
            } else if (which.equals("connect") || which.equals("video") || which.equals("video1")) {
                streamTest(prefs, !which.equals("connect"), which.equals("video1") ? 1 : 0);
            } else {
                Log.w(TAG, "unknown test: " + which);
            }
        } catch (BaseusCloud.AuthException e) {
            Log.e(TAG, "STAGE FAILED: sign-in rejected: " + e.getMessage());
        } catch (Exception e) {
            Log.e(TAG, "STAGE FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Stage 3 ("connect"): reach the camera and log in. Stage 4 ("video"): count frames per lens for 20 s. */
    private static void streamTest(SharedPreferences prefs, final boolean video, final int streamType) throws InterruptedException {
        final long t0 = System.currentTimeMillis();
        final int[] frames = new int[2], keys = new int[2];
        final long[] bytes = new long[2];
        final String[] state = {""};
        final boolean[] opened = {false};
        final boolean[] sized = new boolean[2];
        BaseusStream bs = new BaseusStream(prefs, streamType, new BaseusStream.Listener() {
            @Override public void onFrame(int lens, byte[] d, boolean key) {
                synchronized (frames) { frames[lens]++; bytes[lens] += d.length; if (key) keys[lens]++; }
                if (key && !sized[lens]) { int[] wh = BaseusStream.spsSize(d); if (wh[0] > 0) { sized[lens] = true; Log.i(TAG, "streamType " + streamType + " lens " + lens + " picture " + wh[0] + "x" + wh[1]); } }
            }
            @Override public void onState(String st) {
                state[0] = st;
                Log.i(TAG, "state: " + st + " (+" + (System.currentTimeMillis() - t0) + "ms)");
                if (st.equals("Opening video")) opened[0] = true;
            }
            @Override public void onError(String m) { Log.e(TAG, "error: " + m); }
        });
        bs.start();
        long limit = t0 + (video ? 90_000 : 60_000);
        while (System.currentTimeMillis() < limit && bs.isRunning()) {
            Thread.sleep(500);
            if (!video && opened[0]) break;
            if (video && state[0].equals("Live")) break;
        }
        if (!video) {
            bs.stop();
            Log.i(TAG, opened[0] ? "STAGE 3 PASSED (camera connected and accepted the login)" : "STAGE FAILED: never reached the camera login (last state: " + state[0] + ")");
            return;
        }
        if (!state[0].equals("Live")) { bs.stop(); Log.e(TAG, "STAGE FAILED: no video (last state: " + state[0] + ")"); return; }
        long watch = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < watch && bs.isRunning()) Thread.sleep(500);
        bs.stop();
        synchronized (frames) {
            for (int l = 0; l < 2; l++)
                Log.i(TAG, "lens " + l + ": frames=" + frames[l] + " keyframes=" + keys[l] + " bytes=" + bytes[l]);
            Log.i(TAG, frames[0] > 20 && frames[1] > 20 ? "STAGE 4 PASSED (both lenses streaming)" : "STAGE FAILED: lens frames too few");
        }
    }

    private static void toast(final Context ctx, final String m) {
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        h.post(() -> Toast.makeText(ctx, m, Toast.LENGTH_SHORT).show());
    }
}
