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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Solis inverter (and the battery behind it) through the SolisCloud platform API: POST https://www.soliscloud.com:13333
 * with "Authorization: API keyId:base64(HmacSHA1(secret, POST\nContent-MD5\nContent-Type\nDate\npath))". The key is
 * requested once in SolisCloud (Account > Basic settings > API management) and sent to the TV by ./solis-setup.sh.
 * The cloud refreshes every 5 minutes. Prefs: sc_id, sc_secret, sc_station, sc_name (sc_id "demo": made-up data).
 * Battery and grid directions are worked out from the energy balance (solar + bought = home + charging + sold) because
 * SolisCloud's signs differ between firmware versions. Log tag Solis (no secrets).
 */
final class SolisCloud {
    private static final String TAG = "Solis", BASE = "https://www.soliscloud.com:13333", TYPE = "application/json;charset=UTF-8";

    /** Power in W (battery + charging, grid + buying), energy in kWh. */
    static final class Reading {
        double solar, home, battery, grid, soc = -1;
        double genToday, homeToday, boughtToday, soldToday, chargedToday, dischargedToday;
        long time;                         // when the inverter reported it
        boolean demo;
    }

    /** One point of today's history: minutes since midnight, solar W, home W, battery %. */
    static final class Point { int minute; double solar, home, soc = -1; }

    private final SharedPreferences prefs;

    SolisCloud(SharedPreferences prefs) { this.prefs = prefs; }

    boolean configured() { return prefs.getString("sc_id", null) != null; }

    String name() { return prefs.getString("sc_name", "Solar"); }

    private boolean demo() { return "demo".equals(prefs.getString("sc_id", null)); }

    // ------------------------------------------------------------------ data

    /** Blocking: the station right now. */
    Reading now() throws Exception {
        if (demo()) return demoReading();
        JSONObject d = call("/v1/api/stationDetail", new JSONObject().put("id", prefs.getString("sc_station", ""))).getJSONObject("data");
        Reading r = new Reading();
        r.solar = watts(d, "power");
        r.home = watts(d, "familyLoadPower");
        double b = watts(d, "batteryPower"), g = watts(d, "psum");
        r.soc = d.optDouble("batteryPercent", d.optDouble("batteryCapacitySoc", -1));
        r.genToday = kwh(d, "dayEnergy");
        r.homeToday = kwh(d, "homeLoadEnergy");
        r.boughtToday = kwh(d, "gridPurchasedDayEnergy");
        r.soldToday = kwh(d, "gridSellDayEnergy");
        r.chargedToday = kwh(d, "batteryChargeEnergy");
        r.dischargedToday = kwh(d, "batteryDischargeEnergy");
        r.time = d.optLong("dataTimestamp", System.currentTimeMillis());
        int[] sign = signs(r.solar, r.home, b, g);
        r.battery = sign[0] * b;
        r.grid = sign[1] * g;
        return r;
    }

    /** Blocking: today's history, every 5 minutes. */
    List<Point> today() throws Exception {
        if (demo()) return demoDay();
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        int tz = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3_600_000;
        JSONObject body = new JSONObject().put("id", prefs.getString("sc_station", "")).put("money", "AUD").put("time", day).put("timeZone", tz);
        JSONArray a = call("/v1/api/stationDay", body).optJSONArray("data");
        List<Point> list = new ArrayList<>();
        if (a == null) return list;
        Calendar c = Calendar.getInstance();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.getJSONObject(i);
            long ts = o.optLong("dataTimestamp", 0);
            if (ts == 0) continue;
            c.setTimeInMillis(ts);
            Point p = new Point();
            p.minute = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
            p.solar = watts(o, "power");
            p.home = watts(o, "familyLoadPower");
            p.soc = o.optDouble("batteryCapacitySoc", -1);
            list.add(p);
        }
        return list;
    }

    /** Which way the battery and grid powers point: the combination that balances solar + bought = home + charging. */
    private int[] signs(double solar, double home, double b, double g) {
        int sb = prefs.getInt("sc_sb", 1), sg = prefs.getInt("sc_sg", 1);
        if (Math.abs(b) < 100 && Math.abs(g) < 100) return new int[]{sb, sg};
        double best = Double.MAX_VALUE;
        int bb = sb, bg = sg;
        for (int x : new int[]{1, -1}) for (int y : new int[]{1, -1}) {
            double err = Math.abs(solar + y * g - home - x * b);
            if (err < best) { best = err; bb = x; bg = y; }
        }
        // only trust a clear fit (the parts are sampled together, so a good one balances within ~15%)
        if (best < 0.15 * Math.max(Math.max(solar, home), Math.max(Math.abs(b), Math.abs(g))) && (bb != sb || bg != sg)) {
            Log.i(TAG, "power directions: battery " + (bb > 0 ? "+" : "-") + " grid " + (bg > 0 ? "+" : "-"));
            prefs.edit().putInt("sc_sb", bb).putInt("sc_sg", bg).apply();
            return new int[]{bb, bg};
        }
        return new int[]{sb, sg};
    }

    /** A power field in W, whatever unit the cloud sent it in (fieldStr: W, kW, MW). */
    private static double watts(JSONObject o, String k) {
        double v = o.optDouble(k, 0);
        if (Double.isNaN(v)) return 0;
        String u = o.optString(k + "Str", "W");
        return u.equalsIgnoreCase("kW") ? v * 1000 : u.equalsIgnoreCase("MW") ? v * 1_000_000 : v;
    }

    /** An energy field in kWh (fieldStr: Wh, kWh, MWh). */
    private static double kwh(JSONObject o, String k) {
        double v = o.optDouble(k, 0);
        if (Double.isNaN(v)) return 0;
        String u = o.optString(k + "Str", "kWh");
        return u.equalsIgnoreCase("Wh") ? v / 1000 : u.equalsIgnoreCase("MWh") ? v * 1000 : v;
    }

    // ------------------------------------------------------------------ API

    private JSONObject call(String path, JSONObject body) throws Exception {
        return call(prefs.getString("sc_id", ""), prefs.getString("sc_secret", ""), path, body);
    }

    static JSONObject call(String id, String secret, String path, JSONObject body) throws Exception {
        byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
        String md5 = Base64.encodeToString(MessageDigest.getInstance("MD5").digest(b), Base64.NO_WRAP);
        SimpleDateFormat f = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        String date = f.format(new Date());
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        String sign = Base64.encodeToString(mac.doFinal(("POST\n" + md5 + "\n" + TYPE + "\n" + date + "\n" + path)
                .getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", TYPE);
        c.setRequestProperty("Content-MD5", md5);
        c.setRequestProperty("Date", date);
        c.setRequestProperty("Authorization", "API " + id + ":" + sign);
        try (OutputStream o = c.getOutputStream()) { o.write(b); }
        int code = c.getResponseCode();
        try (InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
            String s = out.toString("UTF-8");
            if (code != 200) throw new java.io.IOException("HTTP " + code + " " + s);
            JSONObject r = new JSONObject(s);
            if (!r.optBoolean("success", false) && !"0".equals(r.optString("code")))
                throw new java.io.IOException("SolisCloud: " + r.optString("code") + " " + r.optString("msg"));
            return r;
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------ demo (no key yet)

    private static double sun(int minute) {
        double x = (minute - 6 * 60) / (13.5 * 60.0);              // daylight 06:00 - 19:30
        return x <= 0 || x >= 1 ? 0 : 6200 * Math.pow(Math.sin(Math.PI * x), 1.6);
    }

    private static double load(int minute) {
        double h = minute / 60.0;
        return 380 + 900 * Math.exp(-Math.pow(h - 7.5, 2) / 2) + 1500 * Math.exp(-Math.pow(h - 18.5, 2) / 3);
    }

    private static int minuteNow() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
    }

    private static List<Point> demoDay() {
        List<Point> l = new ArrayList<>();
        double soc = 38;
        for (int m = 0; m <= minuteNow(); m += 5) {
            Point p = new Point();
            p.minute = m;
            p.solar = sun(m);
            p.home = load(m);
            soc = Math.max(10, Math.min(100, soc + (p.solar - p.home) * 5 / 60 / 100));   // ~10 kWh battery
            p.soc = soc;
            l.add(p);
        }
        return l;
    }

    private static Reading demoReading() {
        List<Point> day = demoDay();
        Point last = day.get(day.size() - 1);
        Reading r = new Reading();
        r.demo = true;
        r.solar = last.solar;
        r.home = last.home;
        r.soc = last.soc;
        double spare = r.solar - r.home;
        r.battery = last.soc >= 100 ? 0 : last.soc <= 10 ? 0 : spare;
        r.grid = r.home + r.battery - r.solar;
        for (Point p : day) {
            r.genToday += p.solar / 12000;
            r.homeToday += p.home / 12000;
        }
        r.boughtToday = 3.4; r.soldToday = Math.max(0, r.genToday - r.homeToday - 4);
        r.chargedToday = 6.1; r.dischargedToday = 4.2;
        r.time = System.currentTimeMillis();
        return r;
    }

    // ------------------------------------------------------------------ setup (./solis-setup.sh)

    /** --es solis <base64 "keyId|secret|stationId|name"> | demo | clear;  --es solis_test now (logs the raw fields) */
    static boolean handle(SharedPreferences prefs, Intent i) {
        if (i == null) return false;
        String v = i.getStringExtra("solis"), test = i.getStringExtra("solis_test");
        if (v != null) {
            i.removeExtra("solis");
            SharedPreferences.Editor e = prefs.edit().remove("sc_id").remove("sc_secret").remove("sc_station").remove("sc_name")
                    .remove("sc_sb").remove("sc_sg");
            if (v.equals("demo")) {
                e.putString("sc_id", "demo").putString("sc_name", "Solar (demo data)");
            } else if (!v.equals("clear")) {
                try {
                    String[] f = new String(Base64.decode(v, Base64.DEFAULT), StandardCharsets.UTF_8).split("\\|", -1);
                    e.putString("sc_id", f[0]).putString("sc_secret", f[1]).putString("sc_station", f[2]).putString("sc_name", f[3]);
                    Log.i(TAG, "solar saved: " + f[3]);
                } catch (Exception x) {
                    Log.e(TAG, "FAILED: setup not understood");
                }
            }
            e.apply();
        }
        if (test != null) {
            i.removeExtra("solis_test");
            new Thread(() -> {
                SolisCloud s = new SolisCloud(prefs);
                try {
                    if (!s.demo()) {
                        JSONObject d = s.call("/v1/api/stationDetail", new JSONObject().put("id", prefs.getString("sc_station", ""))).getJSONObject("data");
                        for (Iterator<String> it = d.keys(); it.hasNext(); ) {
                            String k = it.next();
                            Object o = d.opt(k);
                            if (!(o instanceof JSONObject) && !(o instanceof JSONArray)) Log.i(TAG, "  " + k + " = " + o);
                        }
                    }
                    Reading r = s.now();
                    Log.i(TAG, String.format(Locale.US, "now: solar %.0f W, home %.0f W, battery %+.0f W (%.0f%%), grid %+.0f W; today %.1f kWh",
                            r.solar, r.home, r.battery, r.soc, r.grid, r.genToday));
                    Log.i(TAG, "today: " + s.today().size() + " points");
                } catch (Exception x) {
                    Log.w(TAG, "test: " + x.getMessage());
                }
            }, "solis-test").start();
        }
        return v != null;
    }
}
