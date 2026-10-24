package com.nabil.tvhome;

import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Eufy Security cloud client: sign in (ECDH key exchange, AES-protected password), handle the captcha and
 * email-code challenges, list the HomeBase and its cameras, and fetch the DSK key the local P2P link needs.
 * The protocol follows the community library bropat/eufy-security-client (MIT) and was checked against the
 * official app's traffic. Passwords are never logged.
 */
class EufyCloud {
    static final String SERVER_PUBLIC_KEY =
            "04c5c00c4f8d1197cc7c3167c52bf7acb054d722f0ef08dcd7e0883236e0d72a3868d9750cb47fa4619248f3d83f0f662671dadc6e2d31c2f41db0161651c7c076";
    private static final String DOMAIN_BASE = "https://extend.eufylife.com";
    private static final int CODE_OK = 0, CODE_NEED_VERIFY = 26052, CODE_NEED_CAPTCHA = 100032, CODE_CAPTCHA_ERROR = 100033;

    enum Status { OK, NEED_CAPTCHA, NEED_CODE }

    static class LoginResult {
        Status status;
        String captchaId, captchaImageB64;
    }

    static class AuthException extends IOException {
        AuthException(String m) { super(m); }
    }

    static class Camera {
        String sn, name, model;
        int channel;
    }

    static class Station {
        String sn, name, model, did, ip, appConn, p2pConn, wipnEncDecKey, wipnNdtKey, queryServerDid, prefix, adminUserId;
        JSONObject raw;
        List<Camera> cameras = new ArrayList<>();
    }

    private static final ECParameterSpec P256;
    static {
        try {
            AlgorithmParameters ap = AlgorithmParameters.getInstance("EC");
            ap.init(new ECGenParameterSpec("secp256r1"));
            P256 = ap.getParameterSpec(ECParameterSpec.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private final SharedPreferences prefs;
    private String apiBase;

    EufyCloud(SharedPreferences prefs) { this.prefs = prefs; }

    boolean configured() { return prefs.getString("eu_user", null) != null && prefs.getString("eu_pass", null) != null; }
    String country() { return prefs.getString("eu_country", "AU").toUpperCase(); }
    boolean hasToken() { return prefs.getString("eu_token", null) != null && System.currentTimeMillis() < prefs.getLong("eu_expires", 0); }

    // ------------------------------------------------------------------ keys and AES

    private KeyPair clientKeys() throws Exception {
        String priv = prefs.getString("eu_priv", null);
        KeyFactory kf = KeyFactory.getInstance("EC");
        if (priv != null) {
            BigInteger s = new BigInteger(priv, 16);
            java.security.PrivateKey pk = kf.generatePrivate(new ECPrivateKeySpec(s, P256));
            ECPublicKey pub = publicFromPrivate(s);
            return new KeyPair(pub, pk);
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        BigInteger s = ((java.security.interfaces.ECPrivateKey) kp.getPrivate()).getS();
        prefs.edit().putString("eu_priv", s.toString(16)).apply();
        return kp;
    }

    /** Public point = s * G, by double-and-add on the curve (the platform has no direct "derive public" call). */
    private static ECPublicKey publicFromPrivate(BigInteger s) throws Exception {
        ECPoint g = P256.getGenerator();
        BigInteger p = ((java.security.spec.ECFieldFp) P256.getCurve().getField()).getP(), a = P256.getCurve().getA();
        ECPoint r = ECPoint.POINT_INFINITY, q = g;
        for (int i = 0; i < s.bitLength(); i++) {
            if (s.testBit(i)) r = add(r, q, p, a);
            q = add(q, q, p, a);
        }
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(r, P256));
    }

    private static ECPoint add(ECPoint p1, ECPoint p2, BigInteger p, BigInteger a) {
        if (p1.equals(ECPoint.POINT_INFINITY)) return p2;
        if (p2.equals(ECPoint.POINT_INFINITY)) return p1;
        BigInteger x1 = p1.getAffineX(), y1 = p1.getAffineY(), x2 = p2.getAffineX(), y2 = p2.getAffineY(), l;
        if (x1.equals(x2)) {
            if (!y1.equals(y2) || y1.signum() == 0) return ECPoint.POINT_INFINITY;
            l = x1.multiply(x1).multiply(BigInteger.valueOf(3)).add(a).multiply(y1.shiftLeft(1).modInverse(p)).mod(p);
        } else {
            l = y2.subtract(y1).multiply(x2.subtract(x1).modInverse(p)).mod(p);
        }
        BigInteger x3 = l.multiply(l).subtract(x1).subtract(x2).mod(p);
        BigInteger y3 = l.multiply(x1.subtract(x3)).subtract(y1).mod(p);
        return new ECPoint(x3, y3);
    }

    private static byte[] unsigned32(BigInteger v) {
        byte[] b = v.toByteArray();
        if (b.length == 32) return b;
        byte[] o = new byte[32];
        if (b.length > 32) System.arraycopy(b, b.length - 32, o, 0, 32); else System.arraycopy(b, 0, o, 32 - b.length, b.length);
        return o;
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    private String clientPublicHex() throws Exception {
        ECPoint w = ((ECPublicKey) clientKeys().getPublic()).getW();
        return "04" + hex(unsigned32(w.getAffineX())) + hex(unsigned32(w.getAffineY()));
    }

    private byte[] sharedSecret(String serverPubHex) throws Exception {
        byte[] sp = unhex(serverPubHex);
        ECPoint pt = new ECPoint(new BigInteger(1, Arrays.copyOfRange(sp, 1, 33)), new BigInteger(1, Arrays.copyOfRange(sp, 33, 65)));
        ECPublicKey server = (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(pt, P256));
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(clientKeys().getPrivate());
        ka.doPhase(server, true);
        return ka.generateSecret();
    }

    private static String aesEncrypt(String data, byte[] key) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(Arrays.copyOf(key, 16)));
        return Base64.encodeToString(c.doFinal(data.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static byte[] aesDecrypt(String b64, byte[] key) throws Exception {
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(Arrays.copyOf(key, 16)));
        return c.doFinal(Base64.decode(b64, Base64.DEFAULT));
    }

    /** Decrypts a cloud payload with the current server key; the plaintext is NUL-terminated JSON. */
    private Object decryptData(String b64) throws Exception {
        String serverKey = prefs.getString("eu_server_pub", SERVER_PUBLIC_KEY);
        byte[] plain = aesDecrypt(b64, sharedSecret(serverKey));
        int n = 0;
        while (n < plain.length && plain[n] != 0) n++;
        String text = new String(plain, 0, n, StandardCharsets.UTF_8);
        return text.trim().startsWith("[") ? new JSONArray(text) : new JSONObject(text);
    }

    // ------------------------------------------------------------------ HTTP

    private Map<String, String> headers() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("App_version", "v4.6.0_1630");
        h.put("Os_type", "android");
        h.put("Os_version", "31");
        h.put("Phone_model", "ONEPLUS A3003");
        h.put("Country", country());
        h.put("Language", "en");
        h.put("Openudid", "5e4621b0152c0d00");
        h.put("Net_type", "wifi");
        h.put("Mnc", "02");
        h.put("Mcc", "262");
        h.put("Sn", "75814221ee75");
        h.put("Model_type", "PHONE");
        h.put("Timezone", tzString());
        h.put("Cache-Control", "no-cache");
        h.put("Content-Type", "application/json");
        String tok = prefs.getString("eu_token", null);
        if (tok != null) h.put("X-Auth-Token", tok);
        String uid = prefs.getString("eu_uid", null);
        if (uid != null) h.put("gtoken", md5hex(uid));
        return h;
    }

    private static String tzString() {
        int off = java.util.TimeZone.getDefault().getRawOffset() / 60000;
        return String.format("GMT%s%02d:%02d", off < 0 ? "-" : "+", Math.abs(off) / 60, Math.abs(off) % 60);
    }

    private static String md5hex(String s) {
        try { return hex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static long timeZoneMs() { return java.util.TimeZone.getDefault().getRawOffset(); }

    private String base() throws IOException {
        if (apiBase != null) return apiBase;
        String cached = prefs.getString("eu_base", null);
        if (cached != null) return apiBase = cached;
        JSONObject r = http("GET", DOMAIN_BASE + "/domain/" + country(), null, null);
        JSONObject d = r.optJSONObject("data");
        if (r.optInt("code", -1) != CODE_OK || d == null || d.optString("domain").isEmpty()) throw new IOException("Eufy API domain not found for " + country());
        apiBase = "https://" + d.optString("domain");
        prefs.edit().putString("eu_base", apiBase).apply();
        return apiBase;
    }

    private JSONObject http(String method, String url, JSONObject body, Map<String, String> headers) throws IOException {
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
                try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = read(in);
            if (status == 401) throw new AuthException("Eufy session expired");
            JSONObject json;
            try { json = new JSONObject(text); } catch (Exception e) { throw new IOException("HTTP " + status + " (not JSON)"); }
            if (status >= 400 && !json.has("code")) throw new IOException("HTTP " + status);
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
            while ((n = i.read(b)) > 0) { out.write(b, 0, n); if (out.size() > 8_000_000) throw new IOException("Reply too large"); }
            return out.toString("UTF-8");
        }
    }

    private JSONObject post(String endpoint, JSONObject body) throws IOException {
        return http("POST", base() + "/" + endpoint, body, headers());
    }

    // ------------------------------------------------------------------ sign-in

    /** Signs in. Returns OK, or says which challenge the cloud wants (captcha image / emailed code). */
    synchronized LoginResult login(String verifyCode, String captchaId, String captchaAnswer) throws IOException {
        String account = prefs.getString("eu_user", null), password = prefs.getString("eu_pass", null);
        if (account == null || password == null) throw new IOException("Eufy login not configured");
        LoginResult res = new LoginResult();
        try {
            JSONObject body = new JSONObject()
                    .put("ab", country())
                    .put("client_secret_info", new JSONObject().put("public_key", clientPublicHex()))
                    .put("enc", 0)
                    .put("email", account)
                    .put("password", aesEncrypt(password, sharedSecret(SERVER_PUBLIC_KEY)))
                    .put("time_zone", timeZoneMs())
                    .put("transaction", String.valueOf(System.currentTimeMillis()));
            if (verifyCode != null) body.put("verify_code", verifyCode);
            else if (captchaId != null) body.put("captcha_id", captchaId).put("answer", captchaAnswer);
            // A plain login must not carry a stale token, but the emailed-code step must send the interim one.
            if (verifyCode == null) prefs.edit().remove("eu_token").apply();
            JSONObject r = post("v2/passport/login_sec", body);
            int code = r.optInt("code", -1);
            JSONObject d = r.optJSONObject("data");
            if (code == CODE_OK && d != null) {
                JSONObject ssi = d.optJSONObject("server_secret_info");
                SharedPreferences.Editor ed = prefs.edit()
                        .putString("eu_token", d.optString("auth_token"))
                        .putLong("eu_expires", d.optLong("token_expires_at") * 1000)
                        .putString("eu_uid", d.optString("user_id"))
                        .remove("eu_captcha_id").remove("eu_wait");
                if (ssi != null && !ssi.optString("public_key").isEmpty()) ed.putString("eu_server_pub", ssi.optString("public_key"));
                ed.apply();
                res.status = Status.OK;
                return res;
            }
            if (code == CODE_NEED_CAPTCHA || code == CODE_CAPTCHA_ERROR) {
                if (d == null) throw new IOException("Captcha requested but no image sent");
                res.status = Status.NEED_CAPTCHA;
                res.captchaId = d.optString("captcha_id");
                res.captchaImageB64 = d.optString("item");
                prefs.edit().putString("eu_captcha_id", res.captchaId).apply();
                return res;
            }
            if (code == CODE_NEED_VERIFY && d != null) {
                // the cloud wants an emailed code: remember the interim token and ask for the email
                prefs.edit().putString("eu_token", d.optString("auth_token")).putLong("eu_expires", d.optLong("token_expires_at") * 1000)
                        .putString("eu_uid", d.optString("user_id")).putString("eu_wait", "code").apply();
                JSONObject s = post("v1/sms/send/verify_code", new JSONObject().put("message_type", 2).put("transaction", String.valueOf(System.currentTimeMillis())));
                if (s.optInt("code", -1) != CODE_OK) throw new IOException("Eufy would not send the verification email (code " + s.optInt("code", -1) + ")");
                res.status = Status.NEED_CODE;
                return res;
            }
            throw new AuthException("Eufy rejected the login (code " + code + ")");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Eufy login failed: " + e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ data

    /** HomeBases with their cameras. Signs in again (once) if the token was rejected. */
    List<Station> stations() throws IOException {
        if (!hasToken()) throw new AuthException("Not signed in to Eufy");
        try {
            JSONObject body = new JSONObject().put("device_sn", "").put("num", 1000).put("orderby", "").put("page", 0)
                    .put("station_sn", "").put("time_zone", timeZoneMs()).put("transaction", String.valueOf(System.currentTimeMillis()));
            JSONObject r = post("v2/house/station_list", body);
            if (r.optInt("code", -1) != CODE_OK) throw new IOException("Station list refused (code " + r.optInt("code", -1) + ")");
            Object dec = decryptData(r.optString("data"));
            JSONArray arr = dec instanceof JSONArray ? (JSONArray) dec : new JSONArray();
            List<Station> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Station s = new Station();
                s.raw = o;
                s.sn = o.optString("station_sn");
                s.name = o.optString("station_name");
                s.model = o.optString("station_model");
                s.did = o.optString("p2p_did");
                s.ip = o.optString("ip_addr");
                s.appConn = o.optString("app_conn");
                s.p2pConn = o.optString("p2p_conn");
                s.wipnEncDecKey = o.optString("wipn_enc_dec_key");
                s.wipnNdtKey = o.optString("wipn_ndt_aes128key");
                s.queryServerDid = o.optString("query_server_did");
                s.prefix = o.optString("prefix");
                JSONObject mem = o.optJSONObject("member");
                s.adminUserId = mem == null ? "" : mem.optString("admin_user_id");
                JSONArray devs = o.optJSONArray("devices");
                for (int k = 0; devs != null && k < devs.length(); k++) {
                    JSONObject d = devs.getJSONObject(k);
                    Camera c = new Camera();
                    c.sn = d.optString("device_sn");
                    c.name = d.optString("device_name");
                    c.model = d.optString("device_model");
                    c.channel = d.optInt("device_channel");
                    s.cameras.add(c);
                }
                out.add(s);
            }
            return out;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Station list failed: " + e.getClass().getSimpleName());
        }
    }

    /** The account's RSA private key (base64 PKCS#8) for a cipher ID the HomeBase announced; null if unknown. */
    String cipherPrivateKey(int cipherId, String userId) throws IOException {
        String own = prefs.getString("eu_uid", "");
        String[] tries = userId.equals(own) || own.isEmpty() ? new String[]{userId} : new String[]{userId, own};
        IOException last = null;
        for (String uid : tries) {
            try {
                JSONObject body = new JSONObject().put("cipher_ids", new JSONArray().put(cipherId)).put("user_id", uid)
                        .put("transaction", String.valueOf(System.currentTimeMillis()));
                JSONObject r = post("v2/app/cipher/get_ciphers", body);
                String data = r.optString("data");
                android.util.Log.i("EufyCloud", "get_ciphers(user=" + (uid.equals(own) ? "own" : "admin") + "): code=" + r.optInt("code", -1)
                        + " msg=" + r.optString("msg") + " dataLen=" + data.length());
                if (r.optInt("code", -1) != CODE_OK || data.isEmpty()) { last = new IOException("no cipher for user " + (uid.equals(own) ? "own" : "admin")); continue; }
                Object dec = decryptData(data);
                if (!(dec instanceof JSONArray)) continue;
                JSONArray arr = (JSONArray) dec;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.getJSONObject(i);
                    if (c.optInt("cipher_id", -1) == cipherId) return c.optString("private_key");
                }
            } catch (IOException e) {
                last = e;
            } catch (Exception e) {
                last = new IOException("Cipher request failed: " + e.getClass().getSimpleName());
            }
        }
        if (last != null) throw last;
        return null;
    }

    /** DSK key for a station (needed by the P2P server query), with its expiry in epoch seconds. */
    String[] dskKey(String stationSn) throws IOException {
        try {
            JSONObject invalid = new JSONObject().put(stationSn, "");
            JSONObject body = new JSONObject().put("invalid_dsks", invalid).put("station_sns", new JSONArray().put(stationSn))
                    .put("transaction", String.valueOf(System.currentTimeMillis()));
            JSONObject r = post("v1/app/equipment/get_dsk_keys", body);
            if (r.optInt("code", -1) != CODE_OK) throw new IOException("DSK key refused (code " + r.optInt("code", -1) + ")");
            JSONArray keys = r.getJSONObject("data").getJSONArray("dsk_keys");
            for (int i = 0; i < keys.length(); i++) {
                JSONObject k = keys.getJSONObject(i);
                if (stationSn.equals(k.optString("station_sn"))) return new String[]{k.optString("dsk_key"), String.valueOf(k.optLong("expiration"))};
            }
            throw new IOException("No DSK key for this station");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("DSK key failed: " + e.getClass().getSimpleName());
        }
    }
}
