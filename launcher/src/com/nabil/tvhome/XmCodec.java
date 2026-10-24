package com.nabil.tvhome;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * XM application layer carried inside the PPPP channels: 24-byte little-endian frame headers, AES-encrypted
 * command payloads, and reassembly of fragmented video frames.
 */
final class XmCodec {
    private XmCodec() { }

    static final long MAGIC = 0x55AA55AAL;
    static final int CMD_LOGIN = 1, CMD_OPEN_VIDEO = 2, CMD_HEARTBEAT = 2001;

    static byte[] md5(byte[] b) {
        try { return MessageDigest.getInstance("MD5").digest(b); } catch (Exception e) { throw new RuntimeException(e); }
    }

    static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static byte[] key(String p2pPassword) { return md5((p2pPassword + "xmitech").getBytes(StandardCharsets.UTF_8)); }

    static byte[] loginPayload(String username, String p2pPassword) { return loginPayload(username, p2pPassword, 1); }

    static byte[] loginPayload(String username, String p2pPassword, int res) {
        SecureRandom r = new SecureRandom();
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < 6; i++) digits.append(r.nextInt(10));
        String auth = hex(md5((username + ":" + digits + ":" + p2pPassword).getBytes(StandardCharsets.UTF_8)));
        try {
            return new JSONObject().put("username", username).put("timestamp", System.currentTimeMillis())
                    .put("random", digits.toString()).put("user_id", "").put("auth", auth)
                    .put("video", 0).put("mic", 0).put("res", res).toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static byte[] openVideoPayload(String cameraSn, int streamType) {
        try {
            return new JSONObject().put("channel", 0).put("camera_sn", cameraSn).put("streamType", streamType)
                    .put("videoKeepAlive_2", 1).toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    static byte[] encrypt(byte[] payload, String p2pPassword) throws IOException {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key(p2pPassword), "AES"));
            return c.doFinal(payload);
        } catch (Exception e) { throw new IOException("AES failed"); }
    }

    static byte[] decrypt(byte[] data, String p2pPassword, int type) throws IOException {
        if (type == 0) return data;
        if ((type != 1 && type != 4) || data.length == 0 || data.length % 16 != 0) throw new IOException("Unsupported XM encryption");
        try {
            SecretKeySpec k = new SecretKeySpec(key(p2pPassword), "AES");
            if (type == 1) {
                Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding");
                c.init(Cipher.DECRYPT_MODE, k);
                return c.doFinal(data);
            }
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, k, new IvParameterSpec(md5("xmitech".getBytes(StandardCharsets.UTF_8))));
            return c.doFinal(data);
        } catch (Exception e) { throw new IOException("XM payload would not decrypt"); }
    }

    /** 24-byte signal frame for PPPP channel 0. */
    static byte[] signal(int command, byte[] payload, boolean encrypted, int model) {
        byte[] h = new byte[24 + payload.length];
        h[0] = (byte) 0xAA; h[1] = 0x55; h[2] = (byte) 0xAA; h[3] = 0x55;
        h[4] = (byte) (0x10 | (encrypted ? 1 : 0));
        h[5] = (byte) model;
        h[8] = 1; h[10] = 1;                                   // fragment 1 of 1
        h[12] = (byte) payload.length; h[13] = (byte) (payload.length >> 8);
        h[14] = (byte) command; h[15] = (byte) (command >> 8);
        System.arraycopy(payload, 0, h, 24, payload.length);
        return h;
    }

    static byte[] command(int command, byte[] payload, String p2pPassword) throws IOException {
        return signal(command, encrypt(payload, p2pPassword), true, 0xD2);
    }

    static byte[] heartbeat() { return signal(CMD_HEARTBEAT, new byte[0], false, 0xE0); }

    // ------------------------------------------------------------------ frame reassembly

    static final class Frame {
        final int model, encryption, command;
        final byte[] payload;
        long stamp;                                           // 8-byte header timestamp (camera clock, ms?)
        Frame(int model, int encryption, int command, byte[] payload) {
            this.model = model; this.encryption = encryption; this.command = command; this.payload = payload;
        }
    }

    /** Reassembles XM frames from the byte stream of one PPPP channel. */
    static final class Stream {
        private final String password;
        private byte[] buf = new byte[1 << 16];
        private int len;
        private final java.io.ByteArrayOutputStream parts = new java.io.ByteArrayOutputStream();
        private long identity = -1;
        private int nextPart = 1;
        private long stamp;

        Stream(String p2pPassword) { password = p2pPassword; }

        List<Frame> feed(byte[] data) throws IOException {
            if (len + data.length > 8_000_000) throw new IOException("XM buffer overflow");
            if (len + data.length > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + data.length));
            System.arraycopy(data, 0, buf, len, data.length);
            len += data.length;
            List<Frame> out = new ArrayList<>();
            int pos = 0;
            while (len - pos >= 24) {
                long magic = u32(buf, pos);
                int flags = buf[pos + 4] & 255, model = buf[pos + 5] & 255, number = u16(buf, pos + 6),
                        count = u16(buf, pos + 8), part = u16(buf, pos + 10), size = u16(buf, pos + 12),
                        command = u16(buf, pos + 14);
                if (magic != MAGIC || (flags >> 4) > 1) throw new IOException("Bad XM frame header");
                boolean heartbeat = count == 0 && part == 0 && size == 0 && command == CMD_HEARTBEAT;
                if (!heartbeat && !(part >= 1 && part <= count && count <= 1024)) throw new IOException("Bad XM fragmentation");
                if (len - pos < 24 + size) break;
                byte[] payload = Arrays.copyOfRange(buf, pos + 24, pos + 24 + size);
                pos += 24 + size;
                long id = ((long) model << 48) ^ ((long) number << 32) ^ ((long) count << 16) ^ command ^ ((long) flags << 40);
                if (part == 1 || count == 0) { identity = id; nextPart = 1; parts.reset(); stamp = u32(buf, pos + 16) | (u32(buf, pos + 20) << 32); }
                if (identity != id || (count != 0 && part != nextPart)) throw new IOException("XM fragment lost");
                parts.write(payload, 0, payload.length);
                nextPart++;
                if (part == count) {
                    byte[] all = parts.toByteArray();
                    Frame fr = new Frame(model, flags & 15, command, all.length == 0 ? all : decrypt(all, password, flags & 15));
                    fr.stamp = stamp;
                    out.add(fr);
                    parts.reset();
                    identity = -1;
                }
            }
            if (pos > 0) { System.arraycopy(buf, pos, buf, 0, len - pos); len -= pos; }
            return out;
        }

        private static int u16(byte[] b, int o) { return (b[o] & 255) | ((b[o + 1] & 255) << 8); }
        private static long u32(byte[] b, int o) { return (u16(b, o) | ((long) u16(b, o + 2) << 16)) & 0xFFFFFFFFL; }
    }
}
