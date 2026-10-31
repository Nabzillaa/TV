package com.nabil.tvhome;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal Dahua-family NVR snapshot client: HTTP digest auth over /cgi-bin/snapshot.cgi.
 * The NVR closes the connection after a 401, so each call reuses the cached nonce on a fresh
 * connection. Two rejected logins in a row trip a 5 minute back-off so a wrong password
 * can never lock the NVR account. Also finds recordings (/cgi-bin/mediaFileFind.cgi) for playback.
 */
class NvrClient {
    private static final String TAG = "NvrClient";
    static final String ERR_LOGIN = "Login failed";

    private final String host, user, pass;
    private String realm, nonce, qop;
    private int nc;
    private int strikes;
    private long blockedUntil;

    NvrClient(String host, String user, String pass) {
        this.host = host;
        this.user = user;
        this.pass = pass;
    }

    /** JPEG bytes of the channel's current frame. */
    synchronized byte[] snapshot(int channel) throws IOException {
        return fetch("/cgi-bin/snapshot.cgi?channel=" + channel);
    }

    /** How far the NVR's clock is ahead of this TV's, in ms (recordings are stamped with the NVR's own clock). */
    synchronized long clockOffset() throws IOException {
        String now = value(text("/cgi-bin/global.cgi?action=getCurrentTime"), "result");   // result=2026-11-01 10:12:00
        if (now == null) throw new IOException("No NVR time");
        return parseTime(now) - System.currentTimeMillis();
    }

    /** The channel's recordings overlapping [from, to) on the NVR clock, sorted: {start, end, 1 if motion/event}. */
    synchronized List<long[]> recordings(int channel, long from, long to) throws IOException {
        String base = "/cgi-bin/mediaFileFind.cgi?action=";
        String obj = value(text(base + "factory.create"), "result");
        if (obj == null) throw new IOException("Recording search unavailable");
        List<long[]> list = new ArrayList<>();
        try {
            String ok = text(base + "findFile&object=" + obj + "&condition.Channel=" + channel + "&condition.StartTime="
                    + q(from) + "&condition.EndTime=" + q(to) + "&condition.Types[0]=dav").trim();
            if (!ok.startsWith("OK")) { Log.i(TAG, "ch" + channel + " findFile: " + ok); return list; }   // no files
            for (int page = 0; page < 30; page++) {
                String r = text(base + "findNextFile&object=" + obj + "&count=100");
                Map<Integer, String[]> items = new TreeMap<>();   // index -> {start, end, flags+events}
                Matcher m = ITEM.matcher(r);
                while (m.find()) {
                    String[] it = items.get(Integer.parseInt(m.group(1)));
                    if (it == null) items.put(Integer.parseInt(m.group(1)), it = new String[]{null, null, ""});
                    String k = m.group(2), v = m.group(3).trim();
                    if (k.equals("StartTime")) it[0] = v;
                    else if (k.equals("EndTime")) it[1] = v;
                    else if (k.equals("Flags") || k.equals("Events")) it[2] += v + ",";
                }
                if (page == 0 && !items.isEmpty()) Log.i(TAG, "ch" + channel + " first recording: " + java.util.Arrays.toString(items.values().iterator().next()));
                for (String[] it : items.values()) {
                    if (it[0] == null || it[1] == null) continue;
                    String tags = it[2].replaceAll("\\b(All|Timing|Manual|Marked),", "");
                    list.add(new long[]{parseTime(it[0]), parseTime(it[1]), tags.isEmpty() ? 0 : 1});
                }
                String found = value(r, "found");
                if (found == null || Integer.parseInt(found.trim()) < 100) break;
            }
        } catch (NumberFormatException e) {
            throw new IOException("Bad recording list");
        } finally {
            try { text(base + "close&object=" + obj); text(base + "destroy&object=" + obj); } catch (IOException ignored) { }
        }
        Collections.sort(list, (a, b) -> Long.compare(a[0], b[0]));
        Log.i(TAG, "ch" + channel + " " + list.size() + " recordings");
        return list;
    }

    private static final Pattern ITEM = Pattern.compile("items\\[(\\d+)\\]\\.(\\w+)(?:\\[\\d+\\])?=([^\\r\\n]*)");

    private static long parseTime(String t) throws IOException {
        try { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(t.trim()).getTime(); }
        catch (ParseException e) { throw new IOException("Bad time " + t); }
    }

    private static String q(long t) { return new SimpleDateFormat("yyyy-MM-dd'%20'HH:mm:ss", Locale.US).format(new Date(t)); }

    private static String value(String body, String key) {
        Matcher m = Pattern.compile("(?m)^" + key + "=(.*)$").matcher(body);
        return m.find() ? m.group(1).trim() : null;
    }

    private String text(String path) throws IOException { return new String(fetch(path), "UTF-8"); }

    private byte[] fetch(String path) throws IOException {
        if (System.currentTimeMillis() < blockedUntil) throw new IOException(ERR_LOGIN);
        for (int attempt = 0; attempt < 2; attempt++) {
            if (nonce == null) challenge(path);
            Result r = get(path, authHeader("GET", path));
            if (r.code == 200) { strikes = 0; return r.body; }
            if (r.code == 401) { nonce = null; strikes++; if (strikes >= 2) { blockedUntil = System.currentTimeMillis() + 300_000; strikes = 0; throw new IOException(ERR_LOGIN); } continue; }
            throw new IOException("HTTP " + r.code);
        }
        throw new IOException("No response");
    }

    private void challenge(String path) throws IOException {
        Result r = get(path, null);
        if (r.code == 200) return; // auth not required
        if (r.code != 401 || r.wwwAuth == null) throw new IOException("HTTP " + r.code);
        realm = param(r.wwwAuth, "realm");
        nonce = param(r.wwwAuth, "nonce");
        qop = param(r.wwwAuth, "qop");
        nc = 0;
        if (realm == null || nonce == null) throw new IOException("Bad challenge");
    }

    private String authHeader(String method, String uri) {
        if (nonce == null) return null;
        nc++;
        String ha1 = md5(user + ":" + realm + ":" + pass);
        String ha2 = md5(method + ":" + uri);
        StringBuilder sb = new StringBuilder("Digest username=\"" + user + "\", realm=\"" + realm
                + "\", nonce=\"" + nonce + "\", uri=\"" + uri + "\"");
        if (qop != null && qop.contains("auth")) {
            String ncs = String.format("%08x", nc), cn = Long.toHexString(System.nanoTime());
            sb.append(", qop=auth, nc=").append(ncs).append(", cnonce=\"").append(cn).append("\", response=\"")
                    .append(md5(ha1 + ":" + nonce + ":" + ncs + ":" + cn + ":auth:" + ha2)).append("\"");
        } else {
            sb.append(", response=\"").append(md5(ha1 + ":" + nonce + ":" + ha2)).append("\"");
        }
        return sb.toString();
    }

    private static class Result { int code; String wwwAuth; byte[] body; }

    private Result get(String path, String auth) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + path).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Connection", "close");
        c.setInstanceFollowRedirects(false);
        if (auth != null) c.setRequestProperty("Authorization", auth);
        try {
            Result r = new Result();
            r.code = c.getResponseCode();
            r.wwwAuth = c.getHeaderField("WWW-Authenticate");
            if (r.code == 200) {
                try (InputStream in = c.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        if (out.size() > 8_000_000) throw new IOException("Too large");
                    }
                    r.body = out.toByteArray();
                }
            }
            return r;
        } finally {
            c.disconnect();
        }
    }

    private static String param(String header, String name) {
        Matcher m = Pattern.compile("[\\s,]" + name + "=\"?([^\",]*)\"?").matcher(" " + header);
        return m.find() ? m.group(1) : null;
    }

    private static String md5(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
