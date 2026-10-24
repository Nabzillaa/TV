package com.nabil.tvhome;

import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Cipher;

/**
 * Baseus Security cloud client: sign in, find the XM API host, list cameras and fetch the MQTT wake settings.
 * The protocol was worked out by the community project baseus-security-ha and checked against the official
 * app's traffic. A new password login signs other Baseus sessions out, so the token is cached and the password
 * is only used again when the cloud rejects it.
 */
class BaseusCloud {
    static final String AREA_URL = "https://ipc-public.baseussecurity.com/doc/doc/area.json";
    static final String ROOT_URL = "https://root.xmitech.net/v1/api/app/host";
    private static final String APP_KEY = "YGnzWsirKf55MxrjG5";
    private static final String APP_SECRET = "Teyi6OmXJZxVDiZY4k";
    private static final String PUBLIC_KEY =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDACE9CZ0ZLsrlF0/QRxnhqufcbAR2Y8CJXK"
            + "VgGBHL8XyPuSPcUhqJGCO9UE7FlDsq1BFyuqx9iLs786SEAg5BskkAm6BttV5uXQSIFOxFjuz6"
            + "PRueq++TiP9KCuPOspvWhVuZFJrajeyTVJ65sViiwmnjOUTt/60qJr8Gk4ZqCPwIDAQAB";

    static class AuthException extends IOException {
        AuthException(String m) { super(m); }
    }

    /** What the cloud knows about one camera; secrets stay in memory. */
    static class Camera {
        String sn, productId, name, did, serviceString, p2pPassword, wakeKey;
        int p2pConnect = -1, lenses = 1;
        JSONObject raw;
    }

    private final SharedPreferences prefs;
    private final String country;

    BaseusCloud(SharedPreferences prefs) {
        this.prefs = prefs;
        String c = prefs.getString("bs_country", "AU");
        this.country = c == null || c.isEmpty() ? "AU" : c.toUpperCase();
    }

    boolean configured() { return prefs.getString("bs_user", null) != null && prefs.getString("bs_pass", null) != null; }

    // ------------------------------------------------------------------ public API

    /** Camera list. Uses the cached token; signs in again (once) only if the cloud rejects it. */
    List<Camera> cameras() throws IOException {
        JSONObject payload;
        try {
            payload = rpc("GetUserDeviceList", "{}");
        } catch (AuthException e) {
            login();
            payload = rpc("GetUserDeviceList", "{}");
        }
        JSONArray rows = payload.optJSONArray("device_list");
        if (rows == null) throw new IOException("Unexpected device list");
        List<Camera> out = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            Camera c = new Camera();
            c.raw = row;
            c.sn = row.optString("device_sn");
            c.productId = row.optString("product_id");
            c.name = row.optString("device_name", c.sn);
            JSONObject ext = row.optJSONObject("device_extend");
            JSONObject plat = ext == null ? null : ext.optJSONObject("platform");
            if (plat != null) {
                c.did = plat.optString("device_did");
                c.serviceString = plat.optString("service_string");
                c.p2pPassword = plat.optString("p2p_password");
                c.wakeKey = plat.optString("wake_key");
                c.p2pConnect = plat.optInt("p2p_connect", -1);
            }
            JSONObject codec = ext == null ? null : ext.optJSONObject("codec");
            if (codec != null) c.lenses = Math.max(1, codec.optInt("camera", 1));
            if (!c.sn.isEmpty() && c.did != null && !c.did.isEmpty()) out.add(c);
        }
        return out;
    }

    /** MQTT wake settings: host, port, client_id, username, password, publish, subscribe. */
    JSONObject mqttConfig() throws IOException {
        try {
            return rpc("GetUserMQTTS", "{}");
        } catch (AuthException e) {
            login();
            return rpc("GetUserMQTTS", "{}");
        }
    }

    // ------------------------------------------------------------------ sign-in

    synchronized void login() throws IOException {
        String account = prefs.getString("bs_user", null), password = prefs.getString("bs_pass", null);
        if (account == null || password == null) throw new IOException("Baseus login not configured");
        JSONObject areas = getJson(AREA_URL);
        JSONObject area = null;
        JSONArray list = areas.optJSONArray("data");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject a = list.optJSONObject(i);
            if (a != null && (country.equals(a.optString("shortName")) || country.equals(a.optString("tel")))) { area = a; break; }
        }
        if (area == null) throw new IOException("Country " + country + " not found");
        JSONObject region = null;
        JSONArray servers = areas.optJSONArray("serverList");
        for (int i = 0; servers != null && i < servers.length(); i++) {
            JSONObject s = servers.optJSONObject(i);
            if (s != null && area.optString("server").equals(s.optString("label"))) { region = s; break; }
        }
        if (region == null) throw new IOException("Region not found");
        JSONObject srv = region.optJSONObject("servers");
        String base = srv == null ? "" : srv.optString("bsServer");
        if (base.isEmpty()) throw new IOException("Login server not found");
        String tel = area.optString("tel");

        long ts = System.currentTimeMillis();
        // Sign exactly the bytes we send.
        String body = "{\"type\":0,\"account\":" + JSONObject.quote(account) + ",\"password\":"
                + JSONObject.quote(encryptPassword(password)) + "}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json; charset=utf-8");
        h.put("auth", "");
        h.put("platform", "1");
        h.put("appType", "40");
        h.put("region", area.optString("shortName", country));
        h.put("appVersion", "1.11.0");
        h.put("versionCode", "52");
        h.put("lang", "en-US");
        h.put("osVersion", "13");
        h.put("brand", "Android");
        h.put("model", "TVHome");
        h.put("channel", "");
        h.put("timestamp", String.valueOf(ts));
        h.put("sign", "ipc#CElYvAkK#" + sha1("GSiPpcmX" + md5(bytes).toUpperCase() + ts));
        String url = trimSlash(base) + "/api/auth/account/login?countryCode=" + URLEncoder.encode("91".equals(tel) ? "1" : tel, "UTF-8");
        JSONObject resp = http("POST", url, bytes, h);
        JSONObject data = resp.optJSONObject("data");
        String token = data == null ? "" : data.optString("auth");
        if (token.isEmpty()) throw new AuthException("Baseus rejected the login (code " + resp.optInt("code", -1) + ")");
        prefs.edit().putString("bs_token", token).putString("bs_tel", tel).remove("bs_xm").apply();
    }

    // ------------------------------------------------------------------ XM RPC

    private JSONObject rpc(String action, String body) throws IOException {
        String token = prefs.getString("bs_token", null);
        if (token == null) { login(); token = prefs.getString("bs_token", null); }
        JSONObject r = http("POST", xmHost(token), body.getBytes(StandardCharsets.UTF_8), xmHeaders(action, token, System.currentTimeMillis()));
        return requireXm(r);
    }

    private String xmHost(String token) throws IOException {
        String cached = prefs.getString("bs_xm", null);
        if (cached != null) return cached;
        long ts = System.currentTimeMillis();
        String body;
        try {
            body = new JSONObject().put("code", Integer.parseInt(prefs.getString("bs_tel", "61")))
                    .put("app_key", APP_KEY).put("sign", xmSignature(ts)).put("sign_ts", ts).toString();
        } catch (Exception e) {
            throw new IOException("Bad host request");
        }
        JSONObject payload = requireXm(http("POST", ROOT_URL, body.getBytes(StandardCharsets.UTF_8), xmHeaders(null, null, ts)));
        String host = payload.optString("host");
        if (host.isEmpty()) throw new IOException("XM host missing");
        if (!host.startsWith("http")) host = "https://" + host;
        prefs.edit().putString("bs_xm", host).apply();
        return host;
    }

    private static Map<String, String> xmHeaders(String action, String token, long ts) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", "Android/1.11.0/tvhome/13");
        h.put("Timestamp", String.valueOf(ts));
        h.put("Version", "3.0");
        h.put("SecretId", APP_KEY);
        h.put("Signature", xmSignature(ts));
        h.put("RequestId", UUID.randomUUID().toString());
        h.put("Lang", "en-US");
        h.put("Content-Type", "application/json; charset=utf-8");
        h.put("platform", "1");
        if (action != null) h.put("Action", action);
        if (token != null) h.put("Authorization", token);
        return h;
    }

    private static JSONObject requireXm(JSONObject r) throws IOException {
        String code = String.valueOf(r.opt("code"));
        if (code.equals("401") || code.equals("100401")) throw new AuthException("Session expired");
        if (!r.optBoolean("result", false)) throw new IOException("XM request refused (code " + code + ")");
        JSONObject p = r.optJSONObject("payload");
        if (p == null) throw new IOException("XM reply has no payload");
        return p;
    }

    // ------------------------------------------------------------------ HTTP and crypto helpers

    private JSONObject getJson(String url) throws IOException { return http("GET", url, null, null); }

    private JSONObject http(String method, String url, byte[] body, Map<String, String> headers) throws IOException {
        if (!url.startsWith("https://")) throw new IOException("Refusing non-HTTPS URL");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method);
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        try {
            if (body != null) {
                c.setDoOutput(true);
                try (OutputStream o = c.getOutputStream()) { o.write(body); }
            }
            int status = c.getResponseCode();
            String text = read(status >= 400 ? c.getErrorStream() : c.getInputStream());
            JSONObject json = null;
            try { json = new JSONObject(text); } catch (Exception ignored) { }
            if (status == 401 || status == 403) throw new AuthException("HTTP " + status);
            if (status < 200 || status >= 300) {
                // the XM API reports an expired session as HTTP 500 with code 100401
                String code = json == null ? "" : String.valueOf(json.opt("code"));
                if (code.equals("401") || code.equals("100401")) throw new AuthException("Session expired");
                throw new IOException("HTTP " + status);
            }
            if (json == null) throw new IOException("Reply was not JSON");
            return json;
        } finally {
            c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = i.read(b)) > 0) { out.write(b, 0, n); if (out.size() > 4_000_000) throw new IOException("Reply too large"); }
            return out.toString("UTF-8");
        }
    }

    private static String encryptPassword(String password) throws IOException {
        try {
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.decode(PUBLIC_KEY, Base64.DEFAULT)));
            Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            c.init(Cipher.ENCRYPT_MODE, key);
            return Base64.encodeToString(c.doFinal(password.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IOException("Password encryption failed");
        }
    }

    private static String xmSignature(long ts) { return md5((ts + APP_KEY + APP_SECRET).getBytes(StandardCharsets.UTF_8)); }
    private static String md5(byte[] b) { return hex("MD5", b); }
    private static String sha1(String s) { return hex("SHA-1", s.getBytes(StandardCharsets.UTF_8)); }
    private static String trimSlash(String s) { return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }

    private static String hex(String alg, byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance(alg).digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
