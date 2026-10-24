package com.nabil.tvhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal Dahua-family NVR snapshot client: HTTP digest auth over /cgi-bin/snapshot.cgi.
 * The NVR closes the connection after a 401, so each call reuses the cached nonce on a fresh
 * connection. Two rejected logins in a row trip a 5 minute back-off so a wrong password
 * can never lock the NVR account.
 */
class NvrClient {
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
        if (System.currentTimeMillis() < blockedUntil) throw new IOException(ERR_LOGIN);
        String path = "/cgi-bin/snapshot.cgi?channel=" + channel;
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
