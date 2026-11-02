package com.nabil.tvhome;

import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Amber Electric (the retailer) through its public API (https://api.amber.com.au/v1, "Authorization: Bearer token";
 * the token is made in the Amber app: Settings > Amber for Developers). Gives live and today's 30-minute prices for
 * buying ("general") and selling ("feedIn"), and billed usage (a day or so behind). Amber quotes feed-in as the price
 * you pay, so a negative feed-in price is money earned. Prefs: am_token, am_site. Log tag Amber (no token).
 */
final class AmberClient {
    private static final String TAG = "Amber", BASE = "https://api.amber.com.au/v1";

    /** A 30-minute price: minutes since midnight (start), c/kWh to buy, c/kWh earned selling, Amber's descriptor. */
    static final class Price { int minute; double buy, sell; String level = ""; }

    static final class Money {
        double buyNow = Double.NaN, sellNow = Double.NaN;     // c/kWh (sell: what you earn)
        String levelNow = "";
        List<Price> today = new ArrayList<>();
        double monthEarned = Double.NaN, monthSpent = Double.NaN;   // $ from billed usage, month to the latest data
        String monthTo = "";                                        // last day the usage covers
    }

    private final SharedPreferences prefs;

    AmberClient(SharedPreferences prefs) { this.prefs = prefs; }

    boolean configured() { return prefs.getString("am_token", null) != null; }

    private long monthAt;
    private double cachedEarned = Double.NaN, cachedSpent = Double.NaN;
    private String cachedTo = "";

    /** Blocking: prices now and today; the month's billed usage refreshes hourly. */
    Money fetch() throws Exception {
        Money m = new Money();
        String site = site();
        JSONArray cur = get("/sites/" + site + "/prices/current?next=0&previous=0&resolution=30");
        for (int i = 0; i < cur.length(); i++) {
            JSONObject o = cur.getJSONObject(i);
            if ("general".equals(o.optString("channelType"))) { m.buyNow = o.optDouble("perKwh"); m.levelNow = o.optString("descriptor", ""); }
            else if ("feedIn".equals(o.optString("channelType"))) m.sellNow = -o.optDouble("perKwh");
        }
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        JSONArray pr = get("/sites/" + site + "/prices?startDate=" + day + "&endDate=" + day + "&resolution=30");
        java.util.Map<Integer, Price> byMinute = new java.util.TreeMap<>();
        for (int i = 0; i < pr.length(); i++) {
            JSONObject o = pr.getJSONObject(i);
            int minute = minuteOf(o.optString("startTime"));
            if (minute < 0) continue;
            Price p = byMinute.get(minute);
            if (p == null) byMinute.put(minute, p = new Price());
            p.minute = minute;
            if ("general".equals(o.optString("channelType"))) { p.buy = o.optDouble("perKwh"); p.level = o.optString("descriptor", ""); }
            else if ("feedIn".equals(o.optString("channelType"))) p.sell = -o.optDouble("perKwh");
        }
        m.today.addAll(byMinute.values());
        if (System.currentTimeMillis() - monthAt > 3_600_000L) {
            try { month(site); monthAt = System.currentTimeMillis(); }
            catch (Exception e) { Log.w(TAG, "usage: " + e.getMessage()); }
        }
        m.monthEarned = cachedEarned;
        m.monthSpent = cachedSpent;
        m.monthTo = cachedTo;
        return m;
    }

    /** Billed usage from the 1st of the month, a week per request: feed-in cost is negative (earned). */
    private void month(String site) throws Exception {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        Calendar from = Calendar.getInstance();
        from.set(Calendar.DAY_OF_MONTH, 1);
        Calendar end = Calendar.getInstance();
        end.add(Calendar.DAY_OF_MONTH, -1);                       // usage arrives a day or so late
        double earned = 0, spent = 0;
        String last = "";
        while (!from.after(end)) {
            Calendar to = (Calendar) from.clone();
            to.add(Calendar.DAY_OF_MONTH, 6);
            if (to.after(end)) to = (Calendar) end.clone();
            JSONArray u = get("/sites/" + site + "/usage?startDate=" + f.format(from.getTime()) + "&endDate=" + f.format(to.getTime()) + "&resolution=30");
            for (int i = 0; i < u.length(); i++) {
                JSONObject o = u.getJSONObject(i);
                double cost = o.optDouble("cost", 0);
                if ("feedIn".equals(o.optString("channelType"))) earned -= cost; else spent += cost;
                String d = o.optString("date", "");
                if (d.compareTo(last) > 0) last = d;
            }
            from = (Calendar) to.clone();
            from.add(Calendar.DAY_OF_MONTH, 1);
        }
        cachedEarned = earned / 100;
        cachedSpent = spent / 100;
        cachedTo = last;
        Log.i(TAG, String.format(Locale.US, "month to %s: earned $%.2f, spent $%.2f", last, cachedEarned, cachedSpent));
    }

    private String site() throws Exception {
        String s = prefs.getString("am_site", null);
        if (s != null) return s;
        JSONArray sites = get("/sites");
        for (int i = 0; i < sites.length(); i++) {
            JSONObject o = sites.getJSONObject(i);
            if (!"closed".equals(o.optString("status"))) { s = o.getString("id"); break; }
        }
        if (s == null) throw new java.io.IOException("No Amber site");
        prefs.edit().putString("am_site", s).apply();
        return s;
    }

    /** Minutes since local midnight of an ISO time like 2026-11-02T13:00:01Z. */
    private static int minuteOf(String iso) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(iso.length() >= 19 ? iso.substring(0, 19) : iso);
            Calendar c = Calendar.getInstance();
            c.setTime(d);
            return c.get(Calendar.HOUR_OF_DAY) * 60 + (c.get(Calendar.MINUTE) / 30) * 30;
        } catch (Exception e) {
            return -1;
        }
    }

    private JSONArray get(String path) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("Authorization", "Bearer " + prefs.getString("am_token", ""));
        c.setRequestProperty("Accept", "application/json");
        try {
            int code = c.getResponseCode();
            if (code == 401 || code == 403) throw new java.io.IOException("Amber refused the token");
            if (code != 200) throw new java.io.IOException("Amber HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new JSONArray(out.toString("UTF-8"));
            }
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------ setup (./amber-setup.sh)

    /** --es amber <token> | clear;  --es amber_test now */
    static void handle(SharedPreferences prefs, Intent i) {
        if (i == null) return;
        String v = i.getStringExtra("amber"), test = i.getStringExtra("amber_test");
        if (v != null) {
            i.removeExtra("amber");
            SharedPreferences.Editor e = prefs.edit().remove("am_token").remove("am_site");
            if (!v.equals("clear")) e.putString("am_token", v.trim());
            e.apply();
            Log.i(TAG, v.equals("clear") ? "Amber removed" : "Amber token saved");
        }
        if (test != null) {
            i.removeExtra("amber_test");
            new Thread(() -> {
                try {
                    Money m = new AmberClient(prefs).fetch();
                    Log.i(TAG, String.format(Locale.US, "now: buy %.1fc, sell %.1fc (%s); today %d prices; month earned $%.2f spent $%.2f to %s",
                            m.buyNow, m.sellNow, m.levelNow, m.today.size(), m.monthEarned, m.monthSpent, m.monthTo));
                } catch (Exception x) {
                    Log.w(TAG, "test: " + x.getMessage());
                }
            }, "amber-test").start();
        }
    }
}
