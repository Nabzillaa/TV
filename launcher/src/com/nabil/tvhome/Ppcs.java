package com.nabil.tvhome;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PPPP/PPCS peer-to-peer transport used by Baseus (XM) cameras: rendezvous through the vendor's servers, a
 * hole punch to the camera, then reliable "DRW" channels over UDP. The wire format comes from the official
 * app's traffic and the community project baseus-security-ha.
 *
 * Deliberate difference from that project: the camera may answer the punch from its LAN address (the router
 * loops the traffic back) instead of the public address the server announced. Any private-network sender is
 * accepted as long as the 20-byte device ID inside the packet matches ours.
 */
final class Ppcs {
    private Ppcs() { }

    // Proprietary lookup tables from the vendor library (copied from the reverse-engineered reference).
    static final byte[] XOR_TABLE = {
        (byte) 0x7c, (byte) 0x9c, (byte) 0xe8, (byte) 0x4a, (byte) 0x13, (byte) 0xde, (byte) 0xdc, (byte) 0xb2, (byte) 0x2f, (byte) 0x21, (byte) 0x23, (byte) 0xe4, (byte) 0x30, (byte) 0x7b, (byte) 0x3d, (byte) 0x8c,
        (byte) 0xbc, (byte) 0x0b, (byte) 0x27, (byte) 0x0c, (byte) 0x3c, (byte) 0xf7, (byte) 0x9a, (byte) 0xe7, (byte) 0x08, (byte) 0x71, (byte) 0x96, (byte) 0x00, (byte) 0x97, (byte) 0x85, (byte) 0xef, (byte) 0xc1,
        (byte) 0x1f, (byte) 0xc4, (byte) 0xdb, (byte) 0xa1, (byte) 0xc2, (byte) 0xeb, (byte) 0xd9, (byte) 0x01, (byte) 0xfa, (byte) 0xba, (byte) 0x3b, (byte) 0x05, (byte) 0xb8, (byte) 0x15, (byte) 0x87, (byte) 0x83,
        (byte) 0x28, (byte) 0x72, (byte) 0xd1, (byte) 0x8b, (byte) 0x5a, (byte) 0xd6, (byte) 0xda, (byte) 0x93, (byte) 0x58, (byte) 0xfe, (byte) 0xaa, (byte) 0xcc, (byte) 0x6e, (byte) 0x1b, (byte) 0xf0, (byte) 0xa3,
        (byte) 0x88, (byte) 0xab, (byte) 0x43, (byte) 0xc0, (byte) 0x0d, (byte) 0xb5, (byte) 0x45, (byte) 0x38, (byte) 0x4f, (byte) 0x50, (byte) 0x22, (byte) 0x66, (byte) 0x20, (byte) 0x7f, (byte) 0x07, (byte) 0x5b,
        (byte) 0x14, (byte) 0x98, (byte) 0x1d, (byte) 0x9b, (byte) 0xa7, (byte) 0x2a, (byte) 0xb9, (byte) 0xa8, (byte) 0xcb, (byte) 0xf1, (byte) 0xfc, (byte) 0x49, (byte) 0x47, (byte) 0x06, (byte) 0x3e, (byte) 0xb1,
        (byte) 0x0e, (byte) 0x04, (byte) 0x3a, (byte) 0x94, (byte) 0x5e, (byte) 0xee, (byte) 0x54, (byte) 0x11, (byte) 0x34, (byte) 0xdd, (byte) 0x4d, (byte) 0xf9, (byte) 0xec, (byte) 0xc7, (byte) 0xc9, (byte) 0xe3,
        (byte) 0x78, (byte) 0x1a, (byte) 0x6f, (byte) 0x70, (byte) 0x6b, (byte) 0xa4, (byte) 0xbd, (byte) 0xa9, (byte) 0x5d, (byte) 0xd5, (byte) 0xf8, (byte) 0xe5, (byte) 0xbb, (byte) 0x26, (byte) 0xaf, (byte) 0x42,
        (byte) 0x37, (byte) 0xd8, (byte) 0xe1, (byte) 0x02, (byte) 0x0a, (byte) 0xae, (byte) 0x5f, (byte) 0x1c, (byte) 0xc5, (byte) 0x73, (byte) 0x09, (byte) 0x4e, (byte) 0x69, (byte) 0x24, (byte) 0x90, (byte) 0x6d,
        (byte) 0x12, (byte) 0xb3, (byte) 0x19, (byte) 0xad, (byte) 0x74, (byte) 0x8a, (byte) 0x29, (byte) 0x40, (byte) 0xf5, (byte) 0x2d, (byte) 0xbe, (byte) 0xa5, (byte) 0x59, (byte) 0xe0, (byte) 0xf4, (byte) 0x79,
        (byte) 0xd2, (byte) 0x4b, (byte) 0xce, (byte) 0x89, (byte) 0x82, (byte) 0x48, (byte) 0x84, (byte) 0x25, (byte) 0xc6, (byte) 0x91, (byte) 0x2b, (byte) 0xa2, (byte) 0xfb, (byte) 0x8f, (byte) 0xe9, (byte) 0xa6,
        (byte) 0xb0, (byte) 0x9e, (byte) 0x3f, (byte) 0x65, (byte) 0xf6, (byte) 0x03, (byte) 0x31, (byte) 0x2e, (byte) 0xac, (byte) 0x0f, (byte) 0x95, (byte) 0x2c, (byte) 0x5c, (byte) 0xed, (byte) 0x39, (byte) 0xb7,
        (byte) 0x33, (byte) 0x6c, (byte) 0x56, (byte) 0x7e, (byte) 0xb4, (byte) 0xa0, (byte) 0xfd, (byte) 0x7a, (byte) 0x81, (byte) 0x53, (byte) 0x51, (byte) 0x86, (byte) 0x8d, (byte) 0x9f, (byte) 0x77, (byte) 0xff,
        (byte) 0x6a, (byte) 0x80, (byte) 0xdf, (byte) 0xe2, (byte) 0xbf, (byte) 0x10, (byte) 0xd7, (byte) 0x75, (byte) 0x64, (byte) 0x57, (byte) 0x76, (byte) 0xf3, (byte) 0x55, (byte) 0xcd, (byte) 0xd0, (byte) 0xc8,
        (byte) 0x18, (byte) 0xe6, (byte) 0x36, (byte) 0x41, (byte) 0x62, (byte) 0xcf, (byte) 0x99, (byte) 0xf2, (byte) 0x32, (byte) 0x4c, (byte) 0x67, (byte) 0x60, (byte) 0x61, (byte) 0x92, (byte) 0xca, (byte) 0xd3,
        (byte) 0xea, (byte) 0x63, (byte) 0x7d, (byte) 0x16, (byte) 0xb6, (byte) 0x8e, (byte) 0xd4, (byte) 0x68, (byte) 0x35, (byte) 0xc3, (byte) 0x52, (byte) 0x9d, (byte) 0x46, (byte) 0x44, (byte) 0x1e, (byte) 0x17
    };

    static final byte[] STRING_TABLE = {
        (byte) 0x49, (byte) 0x59, (byte) 0x43, (byte) 0x3d, (byte) 0xb5, (byte) 0xbf, (byte) 0x6d, (byte) 0xa3, (byte) 0x47, (byte) 0x53, (byte) 0x4f, (byte) 0x61, (byte) 0x65, (byte) 0xe3, (byte) 0x71, (byte) 0xe9,
        (byte) 0x67, (byte) 0x7f, (byte) 0x02, (byte) 0x03, (byte) 0x0b, (byte) 0xad, (byte) 0xb3, (byte) 0x89, (byte) 0x2b, (byte) 0x2f, (byte) 0x35, (byte) 0xc1, (byte) 0x6b, (byte) 0x8b, (byte) 0x95, (byte) 0x97,
        (byte) 0x11, (byte) 0xe5, (byte) 0xa7, (byte) 0x0d, (byte) 0xef, (byte) 0xf1, (byte) 0x05, (byte) 0x07, (byte) 0x83, (byte) 0xfb, (byte) 0x9d, (byte) 0x3b, (byte) 0xc5, (byte) 0xc7, (byte) 0x13, (byte) 0x17,
        (byte) 0x1d, (byte) 0x1f, (byte) 0x25, (byte) 0x29, (byte) 0xd3, (byte) 0xdf
    };

    static final int HELLO = 0x00, HELLO_ACK = 0x01, P2P_REQ = 0x20, P2P_REQ_ACK = 0x21, PUNCH_TO = 0x40,
            PUNCH = 0x41, P2P_READY = 0x42, DRW = 0xD0, DRW_ACK = 0xD1, ALIVE = 0xE0, ALIVE_ACK = 0xE1, CLOSE = 0xF0;

    // ------------------------------------------------------------------ wire helpers

    static byte[] frame(int type, byte[] payload) {
        byte[] f = new byte[4 + payload.length];
        f[0] = (byte) 0xF1;
        f[1] = (byte) type;
        f[2] = (byte) (payload.length >> 8);
        f[3] = (byte) payload.length;
        System.arraycopy(payload, 0, f, 4, payload.length);
        return f;
    }

    static final class Packet {
        final int type;
        final byte[] payload;
        Packet(int type, byte[] payload) { this.type = type; this.payload = payload; }
    }

    /** Null if the datagram is not a well-formed PPPP frame. */
    static Packet parse(byte[] d, int len) {
        if (len < 4 || d[0] != (byte) 0xF1) return null;
        int size = ((d[2] & 255) << 8) | (d[3] & 255);
        if (len < 4 + size) return null;
        return new Packet(d[1] & 255, Arrays.copyOfRange(d, 4, 4 + size));
    }

    /** Optional scrambling keyed by the service string's suffix (empty on the cameras seen so far). */
    static byte[] protect(byte[] data, byte[] key, boolean decrypt) {
        if (key == null || key.length == 0 || data.length == 0) return data;
        int total = 0, xor = 0, thirds = 0;
        for (byte b : key) { total += b & 255; xor ^= b & 255; thirds += (b & 255) / 3; }
        int[] state = {total & 255, (-total) & 255, thirds & 255, xor};
        byte[] out = new byte[data.length];
        out[0] = (byte) ((data[0] & 255) ^ (XOR_TABLE[total & 255] & 255));
        for (int i = 1; i < data.length; i++) {
            int prev = decrypt ? data[i - 1] & 255 : out[i - 1] & 255;
            out[i] = (byte) ((data[i] & 255) ^ (XOR_TABLE[(prev + state[prev & 3]) & 255] & 255));
        }
        return out;
    }

    static final class Service {
        final List<String> hosts;
        final byte[] key;
        Service(List<String> hosts, byte[] key) { this.hosts = hosts; this.key = key; }
    }

    /** The cloud's "service string": three discovery host names, obfuscated, with an optional transport key. */
    static Service parseServiceString(String value) throws IOException {
        int colon = value.indexOf(':');
        String enc = colon >= 0 ? value.substring(0, colon) : value;
        String suffix = colon >= 0 ? value.substring(colon + 1) : "";
        if (enc.isEmpty() || enc.length() % 2 != 0) throw new IOException("Bad service string");
        byte[] out = new byte[enc.length() / 2];
        int acc = 0x39;
        for (int i = 0; i < out.length; i++) {
            char a = enc.charAt(2 * i), b = enc.charAt(2 * i + 1);
            if (a < 'A' || a > 'P' || b < 'A' || b > 'P') throw new IOException("Bad service string");
            int pair = (((a - 'A') << 4) | (b - 'A')) & 255;
            int v = (acc ^ pair ^ (STRING_TABLE[i % 54] & 255)) & 255;
            out[i] = (byte) v;
            acc ^= v;
        }
        String text = new String(out, java.nio.charset.StandardCharsets.US_ASCII);
        List<String> hosts = new ArrayList<>(Arrays.asList(text.split(",")));
        if (!hosts.isEmpty() && hosts.get(hosts.size() - 1).isEmpty()) hosts.remove(hosts.size() - 1);
        if (hosts.size() != 3) throw new IOException("Unexpected service string");
        for (String h : hosts) if (!h.matches("[A-Za-z0-9.-]{1,253}")) throw new IOException("Bad discovery host");
        return new Service(hosts, suffix.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    /** "ABCDEFG-123456-VWXYZ" -> the 20-byte device ID: 8-byte prefix, 32-bit number, 8-byte suffix. */
    static byte[] deviceId(String did) throws IOException {
        Matcher m = Pattern.compile("([A-Za-z]{1,7})-?(\\d{1,10})-?([A-Za-z]{1,7})").matcher(did);
        if (!m.matches()) throw new IOException("Bad device ID");
        byte[] b = new byte[20];
        byte[] p = m.group(1).getBytes(java.nio.charset.StandardCharsets.US_ASCII), s = m.group(3).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(p, 0, b, 0, p.length);
        long n = Long.parseLong(m.group(2));
        b[8] = (byte) (n >> 24); b[9] = (byte) (n >> 16); b[10] = (byte) (n >> 8); b[11] = (byte) n;
        System.arraycopy(s, 0, b, 12, s.length);
        return b;
    }

    /** The vendor's 16-byte socket address: 00 02, port (little-endian), IPv4 octets reversed, 8 zero bytes. */
    static byte[] address(byte[] ip4, int port) {
        byte[] b = new byte[16];
        b[1] = 2;
        b[2] = (byte) port; b[3] = (byte) (port >> 8);
        for (int i = 0; i < 4; i++) b[4 + i] = ip4[3 - i];
        return b;
    }

    static InetSocketAddress parseAddress(byte[] d) {
        if (d.length < 16 || d[0] != 0 || d[1] != 2) return null;
        int port = (d[2] & 255) | ((d[3] & 255) << 8);
        byte[] ip = {d[7], d[6], d[5], d[4]};
        try { return new InetSocketAddress(InetAddress.getByAddress(ip), port); } catch (Exception e) { return null; }
    }

    // ------------------------------------------------------------------ reliable channels

    private static final class Pending {
        final byte[] wire;
        long sentAt;
        int attempts = 1;
        Pending(byte[] w, long t) { wire = w; sentAt = t; }
    }

    /** One channel, sending side: numbered 1 KB chunks, retransmitted until acknowledged. */
    private static final class SendWindow {
        final int channel;
        int next;
        final Map<Integer, Pending> pending = new HashMap<>();
        SendWindow(int channel) { this.channel = channel; }

        List<byte[]> queue(byte[] data, long now) throws IOException {
            List<byte[]> wires = new ArrayList<>();
            for (int off = 0; off < data.length || (off == 0 && data.length == 0); off += 1024) {
                int n = Math.min(1024, data.length - off);
                if (pending.size() >= 128) throw new IOException("Send window full");
                byte[] p = new byte[4 + n];
                p[0] = (byte) 0xD1; p[1] = (byte) channel; p[2] = (byte) (next >> 8); p[3] = (byte) next;
                System.arraycopy(data, off, p, 4, n);
                byte[] w = frame(DRW, p);
                pending.put(next, new Pending(w, now));
                wires.add(w);
                next = (next + 1) & 0xFFFF;
                if (data.length == 0) break;
            }
            return wires;
        }

        void ack(byte[] payload) {
            if (payload.length < 4) return;
            int count = ((payload[2] & 255) << 8) | (payload[3] & 255);
            for (int i = 0; i < count && 4 + 2 * i + 1 < payload.length; i++)
                pending.remove(((payload[4 + 2 * i] & 255) << 8) | (payload[5 + 2 * i] & 255));
        }

        List<byte[]> due(long now) throws IOException {
            List<byte[]> out = new ArrayList<>();
            for (Pending p : pending.values()) {
                if (now - p.sentAt >= 300) {
                    if (p.attempts >= 6) throw new IOException("Camera stopped acknowledging data");
                    p.attempts++;
                    p.sentAt = now;
                    out.add(p.wire);
                }
            }
            return out;
        }
    }

    /** One channel, receiving side: delivers contiguous data in order, buffering anything that arrives early. */
    private static final class ReceiveWindow {
        int expected;
        final Map<Integer, byte[]> pending = new HashMap<>();
        int buffered;

        byte[] feed(int seq, byte[] payload) throws IOException {
            int distance = (seq - expected) & 0xFFFF;
            if (distance >= 32768) return null;                 // already delivered; the caller re-acks it
            if (pending.containsKey(seq)) return null;
            if (distance >= 2048 || buffered + payload.length > 4_000_000) throw new IOException("Receive window overflow");
            pending.put(seq, payload);
            buffered += payload.length;
            java.io.ByteArrayOutputStream out = null;
            while (pending.containsKey(expected)) {
                byte[] c = pending.remove(expected);
                buffered -= c.length;
                if (out == null) out = new java.io.ByteArrayOutputStream();
                out.write(c, 0, c.length);
                expected = (expected + 1) & 0xFFFF;
            }
            return out == null ? null : out.toByteArray();
        }
    }

    // ------------------------------------------------------------------ session

    interface Listener {
        void onData(int channel, byte[] chunk);
        void onClosed(String why);
    }

    static final class Session {
        private final DatagramSocket sock;
        private final InetSocketAddress peer;
        private final byte[] key;
        private final SendWindow[] send = new SendWindow[8];
        private final ReceiveWindow[] recv = new ReceiveWindow[8];
        private volatile boolean closed;
        private Listener listener;
        private Thread thread;
        private long lastAlive = System.currentTimeMillis();
        // Acknowledgements are batched (like the official app) instead of one tiny packet per data packet:
        // hundreds of tiny uplink frames per second swamp Wi-Fi airtime and make the camera retransmit.
        private final List<Integer>[] ackPending = newAckLists();
        private long lastAckFlush = System.currentTimeMillis();

        @SuppressWarnings("unchecked")
        private static List<Integer>[] newAckLists() {
            List<Integer>[] a = new List[8];
            for (int i = 0; i < 8; i++) a[i] = new ArrayList<>();
            return a;
        }

        private void queueAck(int ch, int seq) throws IOException {
            ackPending[ch].add(seq);
            if (ackPending[ch].size() >= 16) flushAcks(ch);
        }

        private void flushAcks(int only) throws IOException {
            for (int ch = 0; ch < 8; ch++) {
                if (only >= 0 && ch != only) continue;
                List<Integer> l = ackPending[ch];
                if (l.isEmpty()) continue;
                byte[] p = new byte[4 + 2 * l.size()];
                p[0] = (byte) 0xD1; p[1] = (byte) ch; p[2] = (byte) (l.size() >> 8); p[3] = (byte) l.size();
                for (int i = 0; i < l.size(); i++) { p[4 + 2 * i] = (byte) (l.get(i) >> 8); p[5 + 2 * i] = (byte) (int) l.get(i); }
                write(frame(DRW_ACK, p));
                l.clear();
            }
            lastAckFlush = System.currentTimeMillis();
        }
        private int stPk, stDup, stOoo, stBytes, stAckOut; private long stT = System.currentTimeMillis(), stMaxProc;

        private Session(DatagramSocket s, InetSocketAddress peer, byte[] key) {
            this.sock = s; this.peer = peer; this.key = key;
            for (int i = 0; i < 8; i++) { send[i] = new SendWindow(i); recv[i] = new ReceiveWindow(); }
        }

        InetSocketAddress peer() { return peer; }
        boolean isOpen() { return !closed; }

        void start(Listener l) {
            listener = l;
            thread = new Thread(this::loop, "ppcs-session");
            thread.setDaemon(true);
            thread.start();
        }

        synchronized void send(int channel, byte[] data) throws IOException {
            for (byte[] w : send[channel].queue(data, System.currentTimeMillis())) write(w);
        }

        private void write(byte[] wire) throws IOException {
            if (closed) throw new IOException("Session closed");
            byte[] p = protect(wire, key, false);
            sock.send(new DatagramPacket(p, p.length, peer));
        }

        private void loop() {
            String why = "closed";
            byte[] buf = new byte[65535];
            try {
                sock.setSoTimeout(10);
                while (!closed) {
                    long now = System.currentTimeMillis();
                    synchronized (this) {
                        if (now - lastAckFlush >= 10) flushAcks(-1);
                        for (SendWindow w : send) for (byte[] wire : w.due(now)) write(wire);
                        if (now - lastAlive >= 2000) { write(frame(ALIVE, new byte[0])); lastAlive = now; }
                    }
                    DatagramPacket dp = new DatagramPacket(buf, buf.length);
                    try {
                        sock.receive(dp);
                    } catch (SocketTimeoutException e) {
                        continue;
                    }
                    if (!peer.equals(new InetSocketAddress(dp.getAddress(), dp.getPort()))) continue;
                    byte[] raw = protect(Arrays.copyOf(dp.getData(), dp.getLength()), key, true);
                    Packet p = parse(raw, raw.length);
                    if (p == null) continue;
                    if (p.type == DRW_ACK && p.payload.length >= 4 && (p.payload[0] & 255) == 0xD1) {
                        int ch = p.payload[1] & 255;
                        if (ch < 8) synchronized (this) { send[ch].ack(p.payload); }
                    } else if (p.type == DRW && p.payload.length >= 4 && (p.payload[0] & 255) == 0xD1) {
                        int ch = p.payload[1] & 255, seq = ((p.payload[2] & 255) << 8) | (p.payload[3] & 255);
                        if (ch >= 8) continue;
                        long tp = System.nanoTime();
                        if (ch == 1) {
                            stPk++; stBytes += p.payload.length;
                            int dist = (seq - recv[1].expected) & 0xFFFF;
                            if (dist >= 32768 || recv[1].pending.containsKey(seq)) stDup++; else if (dist > 0) stOoo++;
                        }
                        byte[] chunk = recv[ch].feed(seq, Arrays.copyOfRange(p.payload, 4, p.payload.length));
                        synchronized (this) { queueAck(ch, seq); }
                        if (chunk != null) listener.onData(ch, chunk);
                        if (ch == 1) {
                            long us = (System.nanoTime() - tp) / 1000; if (us > stMaxProc) stMaxProc = us;
                            long now2 = System.currentTimeMillis();
                            if (now2 - stT >= 5000) {
                                android.util.Log.i("PpcsFlow", "5s: videoPkts=" + stPk + " (" + stPk / 5 + "/s, " + (stBytes * 8L / 5 / 1000) + " kbit/s) dup=" + stDup + " outOfOrder=" + stOoo + " pendingNow=" + recv[1].pending.size() + " maxHandlingMicros=" + stMaxProc);
                                stPk = stDup = stOoo = stBytes = 0; stMaxProc = 0; stT = now2;
                            }
                        }
                    } else if (p.type == ALIVE) {
                        synchronized (this) { write(frame(ALIVE_ACK, new byte[0])); }
                    } else if (p.type == CLOSE) {
                        why = "camera closed the connection";
                        break;
                    }
                }
            } catch (IOException e) {
                why = e.getMessage() == null ? e.toString() : e.getMessage();
            } finally {
                boolean was = closed;
                closed = true;
                sock.close();
                if (!was && listener != null) listener.onClosed(why);
            }
        }

        void close() {
            if (closed) return;
            try { synchronized (this) { write(frame(CLOSE, new byte[0])); } } catch (Exception ignored) { }
            closed = true;
            sock.close();
        }
    }

    // ------------------------------------------------------------------ rendezvous and punch

    interface Log { void i(String m); }

    /**
     * Finds the camera through the vendor's servers and punches a path to it.
     * The camera must have been woken first; until it registers, the servers answer "not online" (status 254).
     */
    static Session connect(String did, String serviceString, long timeoutMs, Log log) throws IOException {
        byte[] id = deviceId(did);
        Service svc = parseServiceString(serviceString);
        Set<InetSocketAddress> servers = new LinkedHashSet<>();
        for (String host : svc.hosts) {
            Inet4Address v4 = null;
            for (InetAddress a : InetAddress.getAllByName(host)) if (a instanceof Inet4Address) { v4 = (Inet4Address) a; break; }
            if (v4 == null) throw new IOException("No IPv4 for discovery host");
            for (int port = 32100; port <= 32102; port++) servers.add(new InetSocketAddress(v4, port));
        }
        DatagramSocket sock = new DatagramSocket();
        try {
            // The default receive buffer overflows during video bursts (thousands of dropped packets), which
            // makes the camera retransmit and slow down. Ask for a large one.
            sock.setReceiveBufferSize(4 << 20);
            if (log != null) log.i("udp receive buffer " + sock.getReceiveBufferSize() + " bytes");
            byte[] localIp;
            try (DatagramSocket probe = new DatagramSocket()) {
                probe.connect(servers.iterator().next());
                localIp = probe.getLocalAddress().getAddress();
            }
            byte[] req = frame(P2P_REQ, concat(id, address(localIp, sock.getLocalPort())));
            byte[] punch = frame(PUNCH, id);
            Set<InetSocketAddress> candidates = new LinkedHashSet<>();
            Set<String> announced = new HashSet<>();
            long start = System.currentTimeMillis(), deadline = start + timeoutMs, nextReq = 0, nextPunch = 0;
            int rounds = 0, maxRounds = (int) Math.max(1, Math.ceil(timeoutMs / 1000.0));
            int firstStatus = -1;
            byte[] buf = new byte[65535];
            for (InetSocketAddress s : servers) sendTo(sock, protect(frame(HELLO, new byte[0]), svc.key, false), s);
            sock.setSoTimeout(100);
            while (System.currentTimeMillis() < deadline) {
                long now = System.currentTimeMillis();
                if (now >= nextReq && rounds < maxRounds) {
                    for (InetSocketAddress s : servers) sendTo(sock, protect(req, svc.key, false), s);
                    rounds++;
                    nextReq = now + 1000;
                }
                if (!candidates.isEmpty() && now >= nextPunch) {
                    for (InetSocketAddress c : candidates) sendTo(sock, protect(punch, svc.key, false), c);
                    nextPunch = now + 500;
                }
                DatagramPacket dp = new DatagramPacket(buf, buf.length);
                try { sock.receive(dp); } catch (SocketTimeoutException e) { continue; }
                InetSocketAddress src = new InetSocketAddress(dp.getAddress(), dp.getPort());
                boolean fromServer = servers.contains(src);
                boolean lan = dp.getAddress().isSiteLocalAddress();
                if (!fromServer && !candidates.contains(src) && !lan) continue;
                byte[] raw = protect(Arrays.copyOf(dp.getData(), dp.getLength()), svc.key, true);
                Packet p = parse(raw, raw.length);
                if (p == null) continue;
                if (p.type == P2P_REQ_ACK && fromServer) {
                    int status = p.payload.length > 0 ? p.payload[0] & 255 : -1;
                    if (status != firstStatus) { firstStatus = status; if (log != null) log.i("rendezvous status " + status + (status == 254 ? " (camera not online yet)" : "")); }
                } else if (p.type == PUNCH_TO && fromServer) {
                    InetSocketAddress a = parseAddress(p.payload);
                    if (a == null || a.getPort() == 0 || a.getAddress().isAnyLocalAddress()) continue;
                    if (!announced.add(a.toString()) || announced.size() > 6) continue;
                    for (int port = Math.max(1, a.getPort() - 3); port <= Math.min(65535, a.getPort() + 3); port++)
                        if (candidates.size() < 42) candidates.add(new InetSocketAddress(a.getAddress(), port));
                    if (log != null) log.i("camera address announced; " + candidates.size() + " candidates");
                    for (InetSocketAddress c : candidates) sendTo(sock, protect(punch, svc.key, false), c);
                    nextPunch = System.currentTimeMillis() + 500;
                } else if ((p.type == PUNCH || p.type == P2P_READY) && (candidates.contains(src) || lan)) {
                    if (!Arrays.equals(p.payload, id)) continue;      // someone else's device ID
                    if (p.type == PUNCH) sendTo(sock, protect(punch, svc.key, false), src);
                    else {
                        if (log != null) log.i("peer ready at " + (lan ? "LAN address" : "public address") + " after " + (System.currentTimeMillis() - start) + "ms");
                        return new Session(sock, src, svc.key);
                    }
                }
            }
            throw new IOException("Camera did not answer (rounds=" + rounds + ", candidates=" + candidates.size() + ", status=" + firstStatus + ")");
        } catch (IOException | RuntimeException e) {
            sock.close();
            throw e;
        }
    }

    /**
     * LAN-only connection to a device whose ID we know (Eufy HomeBase): broadcast a "local lookup" on UDP 32108,
     * take the address of whoever answers with our device ID, send it a "cam check" (also to the 3 ports either
     * side) and wait for its "cam ID" reply. No cloud servers are involved.
     */
    static Session connectLan(String did, long timeoutMs, Log log) throws IOException {
        byte[] id = deviceId(did);
        DatagramSocket sock = new DatagramSocket();
        try {
            sock.setBroadcast(true);
            try { sock.setReceiveBufferSize(4 << 20); } catch (Exception ignored) { }
            byte[] ipBytes;
            try (DatagramSocket probe = new DatagramSocket()) {
                probe.connect(InetAddress.getByAddress(new byte[]{8, 8, 8, 8}), 53);
                ipBytes = probe.getLocalAddress().getAddress();
            }
            InetAddress subnetBroadcast = InetAddress.getByAddress(new byte[]{ipBytes[0], ipBytes[1], ipBytes[2], (byte) 255});
            byte[] lookup = frame(0x30, new byte[]{0, 0});
            byte[] check = frame(PUNCH, concat(id, new byte[]{0, 0, 0}));
            Set<InetSocketAddress> found = new LinkedHashSet<>();
            long start = System.currentTimeMillis(), deadline = start + timeoutMs, nextLookup = 0, nextCheck = 0;
            byte[] buf = new byte[65535];
            sock.setSoTimeout(100);
            while (System.currentTimeMillis() < deadline) {
                long now = System.currentTimeMillis();
                if (now >= nextLookup && found.isEmpty()) {
                    sendTo(sock, lookup, new InetSocketAddress(subnetBroadcast, 32108));
                    sendTo(sock, lookup, new InetSocketAddress(InetAddress.getByName("255.255.255.255"), 32108));
                    nextLookup = now + 1000;
                }
                if (now >= nextCheck && !found.isEmpty()) {
                    for (InetSocketAddress a : new ArrayList<>(found))
                        for (int p = Math.max(1, a.getPort() - 3); p <= Math.min(65535, a.getPort() + 3); p++)
                            sendTo(sock, check, new InetSocketAddress(a.getAddress(), p));
                    nextCheck = now + 500;
                }
                DatagramPacket dp = new DatagramPacket(buf, buf.length);
                try { sock.receive(dp); } catch (SocketTimeoutException e) { continue; }
                if (!dp.getAddress().isSiteLocalAddress()) continue;
                Packet p = parse(Arrays.copyOf(dp.getData(), dp.getLength()), dp.getLength());
                if (p == null || p.payload.length < 20 || !Arrays.equals(Arrays.copyOf(p.payload, 20), id)) continue;
                InetSocketAddress src = new InetSocketAddress(dp.getAddress(), dp.getPort());
                if (p.type == PUNCH) {
                    if (found.add(src) && log != null) log.i("device answered the local lookup from " + src.getAddress().getHostAddress());
                    nextCheck = 0;
                } else if (p.type == P2P_READY) {
                    if (log != null) log.i("connected to " + src.getAddress().getHostAddress() + ":" + src.getPort() + " after " + (System.currentTimeMillis() - start) + "ms");
                    return new Session(sock, src, new byte[0]);
                }
            }
            throw new IOException("HomeBase not found on this network (is it plugged in and on the same LAN?)");
        } catch (IOException | RuntimeException e) {
            sock.close();
            throw e;
        }
    }

    private static void sendTo(DatagramSocket s, byte[] data, InetSocketAddress to) throws IOException {
        s.send(new DatagramPacket(data, data.length, to));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
