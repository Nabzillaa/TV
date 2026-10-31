package com.nabil.tvhome;

import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Meross smart garage door opener (MSG100/MSG200) over its local HTTP API: POST http://ip/config with a JSON message
 * signed md5(messageId + key + timestamp), where key is the Meross account key that ./meross-setup.sh fetched once.
 * No cloud involved. If the opener moved to a new address, the LAN is searched for its uuid.
 * Prefs: gd_ip, gd_key, gd_uuid, gd_ch, gd_name.  Log tag Garage (no secrets).
 */
final class GarageDoor {
    private static final String TAG = "Garage";

    /** Door position as the opener's sensor reports it; UNKNOWN when it can't be reached. */
    enum State { OPEN, CLOSED, UNKNOWN }

    private final SharedPreferences prefs;

    GarageDoor(SharedPreferences prefs) { this.prefs = prefs; }

    boolean configured() { return prefs.getString("gd_key", null) != null && prefs.getString("gd_uuid", null) != null; }

    String name() { return prefs.getString("gd_name", "Garage door"); }

    /** Blocking: read the door state. */
    State state() {
        try {
            // System.All carries the door state in its digest; GarageDoor.State GET can hang on some firmware
            JSONObject r = call("Appliance.System.All", "GET", new JSONObject());
            JSONArray st = r.getJSONObject("payload").getJSONObject("all").getJSONObject("digest").getJSONArray("garageDoor");
            int ch = prefs.getInt("gd_ch", 0);
            for (int i = 0; i < st.length(); i++) {
                JSONObject s = st.getJSONObject(i);
                if (s.optInt("channel", 0) == ch) return s.optInt("open", 0) == 1 ? State.OPEN : State.CLOSED;
            }
        } catch (Exception e) {
            Log.w(TAG, "state: " + e.getMessage());
        }
        return State.UNKNOWN;
    }

    /** Blocking: start the door moving. Returns false when the opener couldn't be reached or refused. */
    boolean set(boolean open) {
        try {
            JSONObject state = new JSONObject().put("channel", prefs.getInt("gd_ch", 0)).put("open", open ? 1 : 0)
                    .put("uuid", prefs.getString("gd_uuid", ""));
            JSONObject r = call("Appliance.GarageDoor.State", "SET", new JSONObject().put("state", state));
            boolean ok = !"ERROR".equals(r.getJSONObject("header").optString("method"));
            Log.i(TAG, (open ? "open" : "close") + (ok ? " sent" : " refused"));
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "set: " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ local API

    private JSONObject call(String ns, String method, JSONObject payload) throws Exception {
        String ip = prefs.getString("gd_ip", null);
        if (ip != null) {
            try { return post(ip, ns, method, payload, 10_000); } catch (java.io.IOException e) { Log.i(TAG, "not at " + ip + ", searching"); }
        }
        String found = find();
        if (found == null) throw new java.io.IOException("opener not found on the network");
        prefs.edit().putString("gd_ip", found).apply();
        return post(found, ns, method, payload, 10_000);
    }

    /** Ask every address in the TV's /24 for its uuid; the opener answers with the matching one. */
    private String find() throws InterruptedException {
        String self = localAddress();
        if (self == null) return null;
        final String net = self.substring(0, self.lastIndexOf('.') + 1), uuid = prefs.getString("gd_uuid", "");
        final AtomicReference<String> hit = new AtomicReference<>();
        ExecutorService ex = Executors.newFixedThreadPool(32);
        for (int i = 1; i < 255; i++) {
            final String a = net + i;
            ex.execute(() -> {
                if (hit.get() != null) return;
                try {
                    JSONObject r = post(a, "Appliance.System.All", "GET", new JSONObject(), 1500);
                    String u = r.getJSONObject("payload").getJSONObject("all").getJSONObject("system").getJSONObject("hardware").optString("uuid");
                    if (uuid.equalsIgnoreCase(u)) hit.set(a);
                } catch (Exception ignored) { }
            });
        }
        ex.shutdown();
        ex.awaitTermination(20, TimeUnit.SECONDS);
        ex.shutdownNow();
        Log.i(TAG, hit.get() != null ? "found the opener again" : "opener not found");
        return hit.get();
    }

    private static String localAddress() {
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()))
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses()))
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress()) return a.getHostAddress();
        } catch (Exception ignored) { }
        return null;
    }

    private JSONObject post(String ip, String ns, String method, JSONObject payload, int timeout) throws Exception {
        String mid = UUID.randomUUID().toString().replace("-", "");
        long ts = System.currentTimeMillis() / 1000;
        JSONObject header = new JSONObject().put("messageId", mid).put("namespace", ns).put("method", method)
                .put("payloadVersion", 1).put("from", "/app/tv/subscribe").put("timestamp", ts)
                .put("sign", md5(mid + prefs.getString("gd_key", "") + ts));
        byte[] body = new JSONObject().put("header", header).put("payload", payload).toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + ip + "/config").openConnection();
        c.setConnectTimeout(timeout);
        c.setReadTimeout(timeout);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream o = c.getOutputStream()) { o.write(body); }
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new JSONObject(b.toString("UTF-8"));
        } finally {
            c.disconnect();
        }
    }

    private static String md5(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder h = new StringBuilder();
        for (byte x : d) h.append(String.format("%02x", x));
        return h.toString();
    }

    // ------------------------------------------------------------------ setup (./meross-setup.sh)

    /** --es garage <base64 "ip|key|uuid|channel|name"> (or clear);  --es garage_test state */
    static boolean handle(SharedPreferences prefs, Intent i) {
        if (i == null) return false;
        String v = i.getStringExtra("garage"), test = i.getStringExtra("garage_test");
        if (v != null) {
            i.removeExtra("garage");
            if (v.equals("clear")) {
                prefs.edit().remove("gd_ip").remove("gd_key").remove("gd_uuid").remove("gd_ch").remove("gd_name").apply();
                Log.i(TAG, "garage door removed");
            } else {
                try {
                    String[] f = new String(Base64.decode(v, Base64.DEFAULT), StandardCharsets.UTF_8).split("\\|", -1);
                    prefs.edit().putString("gd_ip", f[0]).putString("gd_key", f[1]).putString("gd_uuid", f[2])
                            .putInt("gd_ch", Integer.parseInt(f[3])).putString("gd_name", f[4]).apply();
                    Log.i(TAG, "garage door saved: " + f[4]);
                } catch (Exception e) {
                    Log.e(TAG, "FAILED: setup not understood");
                }
            }
        }
        if (test != null) {
            i.removeExtra("garage_test");
            new Thread(() -> Log.i(TAG, "state: " + new GarageDoor(prefs).state()), "garage-test").start();
        }
        return v != null;
    }
}
