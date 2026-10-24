package com.nabil.tvhome;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Eufy's "XZYH" message layer carried in the reliable channels. Header (16 bytes): "XZYH", command (u16 LE),
 * body length (u32 LE), 01 00, channel, sign/encryption byte, type byte, pad. Channel 0 = commands/data,
 * 1 = video, 2 = control, 3 = binary.
 */
final class EufyXzyh {
    private EufyXzyh() { }

    static final int CMD_START_REALTIME_MEDIA = 1003, CMD_STOP_REALTIME_MEDIA = 1004, CMD_GATEWAYINFO = 1100,
            CMD_PING = 1139, CMD_SET_PAYLOAD = 1350, CMD_VIDEO_FRAME = 1300, CMD_AUDIO_FRAME = 1301;

    static final class Message {
        int cmd, channel, sign, type;
        byte[] body;
    }

    /** Command with an empty body (channel 255 = "any"), e.g. GATEWAYINFO. */
    static byte[] voidCommand(int cmd, int channel) {
        return header(cmd, 0, channel, 0, new byte[0]);
    }

    /** Unencrypted command whose body is `value` (u32 LE) followed by an optional 128-byte-padded string. */
    static byte[] intCommand(int cmd, int channel, long value, String str) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write((int) value); b.write((int) (value >> 8)); b.write((int) (value >> 16)); b.write((int) (value >> 24));
        if (str != null && !str.isEmpty()) {
            byte[] s = str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            int padded = Math.max(128, ((s.length + 127) / 128) * 128);
            byte[] p = new byte[padded];
            System.arraycopy(s, 0, p, 0, s.length);
            b.write(p, 0, p.length);
        }
        return header(cmd, b.size(), channel, 0, b.toByteArray());
    }

    /** Command whose body is a string, AES-128-ECB encrypted (zero padded to 16) when `encType` > 0. */
    static byte[] stringCommand(int cmd, int channel, String value, int encType, byte[] key) throws java.io.IOException {
        byte[] v = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (encType > 0 && key != null && key.length == 16) {
            try {
                byte[] padded = new byte[Math.max(16, ((v.length + 15) / 16) * 16)];
                System.arraycopy(v, 0, padded, 0, v.length);
                javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding");
                c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"));
                byte[] enc = c.doFinal(padded);
                return header(cmd, enc.length, channel, encType, enc);
            } catch (Exception e) {
                throw new java.io.IOException("Command encryption failed");
            }
        }
        return header(cmd, v.length, channel, 0, v);
    }

    /** Command whose body is a u32 LE value, AES-128-ECB encrypted (zero padded to 16) when `encType` > 0. */
    static byte[] intCommandEnc(int cmd, int channel, long value, int encType, byte[] key) throws java.io.IOException {
        byte[] v = {(byte) value, (byte) (value >> 8), (byte) (value >> 16), (byte) (value >> 24)};
        if (encType > 0 && key != null && key.length == 16) {
            try {
                byte[] padded = new byte[16];
                System.arraycopy(v, 0, padded, 0, 4);
                javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding");
                c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"));
                byte[] enc = c.doFinal(padded);
                return header(cmd, enc.length, channel, encType, enc);
            } catch (Exception e) {
                throw new java.io.IOException("Command encryption failed");
            }
        }
        return header(cmd, v.length, channel, 0, v);
    }

    private static byte[] header(int cmd, int len, int channel, int enc, byte[] body) {
        byte[] h = new byte[16 + body.length];
        h[0] = 'X'; h[1] = 'Z'; h[2] = 'Y'; h[3] = 'H';
        h[4] = (byte) cmd; h[5] = (byte) (cmd >> 8);
        h[6] = (byte) len; h[7] = (byte) (len >> 8); h[8] = (byte) (len >> 16); h[9] = (byte) (len >> 24);
        h[10] = 1; h[11] = 0;
        h[12] = (byte) channel; h[13] = (byte) enc;
        System.arraycopy(body, 0, h, 16, body.length);
        return h;
    }

    /** Reassembles XZYH messages from the ordered byte stream of one channel. */
    static final class Reader {
        private byte[] buf = new byte[1 << 16];
        private int len;

        List<Message> feed(byte[] data) {
            if (len + data.length > 16_000_000) { len = 0; return new ArrayList<>(); }
            if (len + data.length > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + data.length));
            System.arraycopy(data, 0, buf, len, data.length);
            len += data.length;
            List<Message> out = new ArrayList<>();
            int pos = 0;
            while (len - pos >= 16) {
                if (!(buf[pos] == 'X' && buf[pos + 1] == 'Z' && buf[pos + 2] == 'Y' && buf[pos + 3] == 'H')) {
                    pos++;                                             // resynchronise on the next marker
                    continue;
                }
                long body = (buf[pos + 6] & 255L) | ((buf[pos + 7] & 255L) << 8) | ((buf[pos + 8] & 255L) << 16) | ((buf[pos + 9] & 255L) << 24);
                if (body > 8_000_000) { pos += 4; continue; }
                if (len - pos < 16 + body) break;
                Message m = new Message();
                m.cmd = (buf[pos + 4] & 255) | ((buf[pos + 5] & 255) << 8);
                m.channel = buf[pos + 12] & 255;
                m.sign = buf[pos + 13] & 255;
                m.type = buf[pos + 14] & 255;
                m.body = Arrays.copyOfRange(buf, pos + 16, pos + 16 + (int) body);
                out.add(m);
                pos += 16 + (int) body;
            }
            if (pos > 0) { System.arraycopy(buf, pos, buf, 0, len - pos); len -= pos; }
            return out;
        }
    }
}
