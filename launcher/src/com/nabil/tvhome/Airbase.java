package com.nabil.tvhome;

import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Daikin Airbase (BRP15B61, ducted) over its local HTTP API: GET http://ip/skyfi/aircon/... answers "key=value,..."
 * text. No account or cloud. The adapter answers 403 now and then while it's busy (its own cloud polling), so every
 * request is retried a few times. If it moved to a new address, the LAN is searched for its MAC.
 * Airbase codes: mode 0 fan, 1 heat, 2 cool, 3 auto, 7 dry; f_rate 1 low, 3 medium, 5 high, with f_auto=1 for auto.
 * Quick timer (runs on the Airbase, TV off or not): two slots t1/t2 with _ena (on/off), _pow (1 turns the aircon on,
 * 0 off; the Daikin app keeps t1 = on timer, t2 = off timer) and _time (minutes after midnight, 15-minute steps).
 * Prefs: ac_ip, ac_mac, ac_name.  Log tag Aircon (basic_info carries the Daikin login, it is never logged or kept).
 */
final class Airbase {
    private static final String TAG = "Aircon";

    /** One reading: control state, temperatures and zones (names null when the system has none set up). */
    static final class Status {
        boolean power;
        int mode = 2, temp = 24, fan = 1;                     // fan: 1 / 3 / 5
        boolean fanAuto;
        Integer inside, outside;
        String[] zoneNames;
        boolean[] zoneOn;
        Map<String, String> raw = new LinkedHashMap<>();      // control info as read, sent back unchanged on a set
        int coolLow = 16, coolHigh = 32, heatLow = 16, heatHigh = 32;
        boolean hasAuto, hasDry = true, hasFanAuto = true;
        Timer[] timers;                                       // the two quick-timer slots, null when unread
    }

    /** One quick-timer slot: turns the aircon on (power) or off at a time of day. */
    static final class Timer {
        boolean enabled, power;
        int minutes;                                          // after midnight
        Timer copy() { Timer t = new Timer(); t.enabled = enabled; t.power = power; t.minutes = minutes; return t; }
    }

    private final SharedPreferences prefs;

    Airbase(SharedPreferences prefs) { this.prefs = prefs; }

    boolean configured() { return prefs.getString("ac_ip", null) != null; }

    String name() { return prefs.getString("ac_name", "Aircon"); }

    private Status fixed;                                     // model limits and zones: read once, kept until the next open

    /** Forget the model limits and zones so the next status() reads them again (each time the screen opens). */
    void refresh() { fixed = null; }

    /**
     * Blocking: read the state, or null when the Airbase can't be reached. It locks everyone out for a while when asked
     * too often, so a poll is the control info and the sensors; limits and zones are read once per open.
     */
    Status status() {
        try {
            Status s = new Status();
            Map<String, String> c = get("aircon/get_control_info");
            s.raw = c;
            s.power = "1".equals(c.get("pow"));
            s.mode = intOr(c.get("mode"), 2);
            s.temp = intOr(c.get("stemp"), s.mode == 1 ? intOr(c.get("dt1"), 22) : intOr(c.get("dt2"), 24));
            s.fan = intOr(c.get("f_rate"), 1);
            s.fanAuto = "1".equals(c.get("f_auto"));
            try { s.timers = readTimers(); } catch (Exception ignored) { }
            try {
                Map<String, String> t = get("aircon/get_sensor_info");
                s.inside = intOrNull(t.get("htemp"));
                s.outside = intOrNull(t.get("otemp"));
            } catch (Exception ignored) { }
            if (fixed != null) {
                s.coolLow = fixed.coolLow; s.coolHigh = fixed.coolHigh; s.heatLow = fixed.heatLow; s.heatHigh = fixed.heatHigh;
                s.hasAuto = fixed.hasAuto; s.hasDry = fixed.hasDry; s.hasFanAuto = fixed.hasFanAuto;
                s.zoneNames = fixed.zoneNames;
                if (s.zoneNames != null) {
                    try { readZones(s); } catch (Exception ignored) { s.zoneOn = fixed.zoneOn; }
                }
                return s;
            }
            try {
                Map<String, String> m = get("aircon/get_model_info");
                s.coolLow = intOr(m.get("cool_l"), 16);
                s.coolHigh = intOr(m.get("cool_h"), 32);
                s.heatLow = intOr(m.get("heat_l"), 16);
                s.heatHigh = intOr(m.get("heat_h"), 32);
                s.hasAuto = "1".equals(m.get("en_auto"));
                s.hasDry = !"0".equals(m.get("en_dry"));
                s.hasFanAuto = !"0".equals(m.get("en_frate_auto"));
            } catch (Exception ignored) { }
            try { readZones(s); } catch (Exception ignored) { }
            fixed = s;
            return s;
        } catch (Exception e) {
            Log.w(TAG, "status: " + e.getMessage());
            return null;
        }
    }

    private void readZones(Status s) throws Exception {
        Map<String, String> z = get("aircon/get_zone_setting");
        String[] names = z.getOrDefault("zone_name", "").split(";", -1);
        String[] on = z.getOrDefault("zone_onoff", "").split(";", -1);
        boolean any = false;
        for (String n : names) if (!n.isEmpty() && !n.equals("-")) any = true;
        if (any && names.length == on.length) {
            s.zoneNames = names;
            s.zoneOn = new boolean[on.length];
            for (int i = 0; i < on.length; i++) s.zoneOn[i] = on[i].equals("1");
        }
    }

    private Timer[] readTimers() throws Exception {
        Map<String, String> q = get("aircon/get_quick_timer");
        Timer[] t = new Timer[2];
        for (int i = 0; i < 2; i++) {
            String k = "t" + (i + 1);
            t[i] = new Timer();
            t[i].enabled = "1".equals(q.get(k + "_ena"));
            t[i].power = !"0".equals(q.getOrDefault(k + "_pow", i == 0 ? "1" : "0"));
            t[i].minutes = Math.max(0, Math.min(1425, intOr(q.get(k + "_time"), i == 0 ? 7 * 60 : 22 * 60)));
        }
        return t;
    }

    /** Blocking: set both quick-timer slots. */
    boolean setTimers(Timer[] t) {
        try {
            StringBuilder q = new StringBuilder("aircon/set_quick_timer?");
            for (int i = 0; i < 2; i++) {
                String k = "t" + (i + 1);
                q.append(k).append("_ena=").append(t[i].enabled ? 1 : 0).append('&')
                 .append(k).append("_pow=").append(t[i].power ? 1 : 0).append('&')
                 .append(k).append("_time=").append(t[i].minutes).append(i == 0 ? "&" : "");
            }
            boolean ok = "OK".equals(get(q.toString()).get("ret"));
            Log.i(TAG, "timers " + describe(t[0]) + ", " + describe(t[1]) + (ok ? "" : " refused"));
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "timers: " + e.getMessage());
            return false;
        }
    }

    private static String describe(Timer t) {
        return (t.power ? "on" : "off") + (t.enabled ? String.format(" at %02d:%02d", t.minutes / 60, t.minutes % 60) : " unset");
    }

    /** Blocking: send power, mode, temperature and fan (everything else as last read). */
    boolean set(Status s) {
        try {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("lpw", "");
            p.put("pow", s.power ? "1" : "0");
            p.put("mode", String.valueOf(s.mode));
            p.put("stemp", String.valueOf(s.temp));
            p.put("shum", s.raw.getOrDefault("shum", "0"));
            p.put("f_rate", String.valueOf(s.fan));
            p.put("f_auto", s.fanAuto ? "1" : "0");
            p.put("f_dir", s.raw.getOrDefault("f_dir", "0"));
            p.put("f_airside", s.raw.getOrDefault("f_airside", "0"));
            StringBuilder q = new StringBuilder("aircon/set_control_info?");
            for (Map.Entry<String, String> e : p.entrySet()) q.append(e.getKey()).append('=').append(e.getValue()).append('&');
            q.setLength(q.length() - 1);
            boolean ok = "OK".equals(get(q.toString()).get("ret"));
            Log.i(TAG, "set " + (s.power ? "on" : "off") + " mode " + s.mode + " " + s.temp + "° fan " + (s.fanAuto ? "auto" : s.fan) + (ok ? "" : " refused"));
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "set: " + e.getMessage());
            return false;
        }
    }

    /** Blocking: switch zones (all of them are sent, as the Airbase expects). */
    boolean setZones(String[] names, boolean[] on) {
        try {
            StringBuilder n = new StringBuilder(), o = new StringBuilder();
            for (int i = 0; i < names.length; i++) {
                if (i > 0) { n.append("%3b"); o.append("%3b"); }
                n.append(java.net.URLEncoder.encode(names[i], "UTF-8"));
                o.append(on[i] ? '1' : '0');
            }
            return "OK".equals(get("aircon/set_zone_setting?zone_name=" + n + "&zone_onoff=" + o).get("ret"));
        } catch (Exception e) {
            Log.w(TAG, "zones: " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ local API

    private Map<String, String> get(String path) throws Exception {
        String ip = prefs.getString("ac_ip", null);
        if (ip != null) {
            try { return request(ip, path, 6000); }
            catch (Busy e) { throw e; }                           // it's there, just refusing for now: don't go searching
            catch (java.io.IOException e) { Log.i(TAG, "not at " + ip + " (" + e.getMessage() + ")"); }
        }
        String found = find();
        if (found == null) throw new java.io.IOException("Airbase not found on the network");
        prefs.edit().putString("ac_ip", found).apply();
        return request(found, path, 6000);
    }

    /** The Airbase answered, but with 403: busy, or locking us out for a while after too many requests. */
    static final class Busy extends java.io.IOException { Busy() { super("busy (403)"); } }

    /** One request, retried while the adapter answers 403 (busy). */
    private static Map<String, String> request(String ip, String path, int timeout) throws Exception {
        java.io.IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) Thread.sleep(1500L * attempt);
            HttpURLConnection c = (HttpURLConnection) new URL("http://" + ip + "/skyfi/" + path).openConnection();
            c.setConnectTimeout(timeout);
            c.setReadTimeout(timeout);
            c.setRequestProperty("Accept", "*/*");
            try {
                int code = c.getResponseCode();
                if (code == 403) { last = new Busy(); continue; }
                if (code != 200) throw new java.io.IOException("HTTP " + code);
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream b = new ByteArrayOutputStream();
                    byte[] buf = new byte[2048];
                    int n;
                    while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                    return parse(b.toString("UTF-8"));
                }
            } finally {
                c.disconnect();
            }
        }
        throw last;
    }

    private static Map<String, String> parse(String body) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String kv : body.trim().split(",")) {
            int eq = kv.indexOf('=');
            if (eq < 1) continue;
            String v = kv.substring(eq + 1);
            try { v = URLDecoder.decode(v, "UTF-8"); } catch (Exception ignored) { }   // zone lists come as "-%3b-%3b..."
            m.put(kv.substring(0, eq), v);
        }
        if (!m.containsKey("ret")) m.put("ret", "?");
        return m;
    }

    /** Ask every address in the TV's /24 for its basic info; the Airbase answers with the matching MAC. */
    private String find() throws InterruptedException {
        String self = localAddress(), mac = prefs.getString("ac_mac", null);
        if (self == null || mac == null) return null;
        final String net = self.substring(0, self.lastIndexOf('.') + 1);
        final AtomicReference<String> hit = new AtomicReference<>();
        ExecutorService ex = Executors.newFixedThreadPool(32);
        for (int i = 1; i < 255; i++) {
            final String a = net + i;
            ex.execute(() -> {
                if (hit.get() != null) return;
                try {
                    if (mac.equalsIgnoreCase(request(a, "common/basic_info", 1500).get("mac"))) hit.set(a);
                } catch (Exception ignored) { }
            });
        }
        ex.shutdown();
        ex.awaitTermination(25, TimeUnit.SECONDS);
        ex.shutdownNow();
        Log.i(TAG, hit.get() != null ? "found the Airbase again" : "Airbase not found");
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

    private static int intOr(String v, int d) { Integer i = intOrNull(v); return i != null ? i : d; }

    private static Integer intOrNull(String v) {
        if (v == null) return null;
        try { return (int) Math.round(Double.parseDouble(v)); } catch (NumberFormatException e) { return null; }
    }

    // ------------------------------------------------------------------ setup

    /** --es aircon <ip>[|name] (or clear): checks it's an Airbase, keeps its address and MAC. */
    static boolean handle(SharedPreferences prefs, Intent i, Runnable done) {
        if (i == null) return false;
        String v = i.getStringExtra("aircon");
        if (v == null) return false;
        i.removeExtra("aircon");
        if (v.equals("clear")) {
            prefs.edit().remove("ac_ip").remove("ac_mac").remove("ac_name").apply();
            Log.i(TAG, "aircon removed");
            done.run();
            return true;
        }
        String[] f = v.split("\\|", 2);
        new Thread(() -> {
            try {
                Map<String, String> b = request(f[0].trim(), "common/basic_info", 6000);
                if (!"aircon".equals(b.get("type"))) throw new java.io.IOException("not a Daikin aircon");
                prefs.edit().putString("ac_ip", f[0].trim()).putString("ac_mac", b.get("mac"))
                        .putString("ac_name", f.length > 1 ? f[1] : "Aircon").apply();
                Log.i(TAG, "aircon saved (" + b.getOrDefault("name", "") + ")");
                done.run();
            } catch (Exception e) {
                Log.e(TAG, "FAILED: " + e.getMessage());
            }
        }, "aircon-setup").start();
        return true;
    }
}
