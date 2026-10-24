package com.nabil.tvhome;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Wakes a Baseus camera: a tiny MQTT 3.1.1 client over TLS that publishes the "WatchDevice" action and waits
 * for the matching reply. The connection is kept open while the camera is being watched, because the official
 * app does the same and the camera seems to stay registered only while someone is watching.
 */
class BaseusWake {
    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private final String seq = String.valueOf(new SecureRandom().nextInt(1_000_000_000));
    private final CountDownLatch replied = new CountDownLatch(1);
    private volatile boolean wakeResult;
    private volatile boolean closed;
    private final CountDownLatch connack = new CountDownLatch(1), suback = new CountDownLatch(1);
    private volatile int connackCode = -1, subackCode = -1;
    private int nextId = 1;
    private final Object pubLock = new Object();
    private volatile int pubrecId = -1, pubcompId = -1;
    private Thread reader, pinger;

    private BaseusWake(Socket s) throws IOException {
        socket = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();
    }

    /** Connects, subscribes, publishes WatchDevice and waits for the camera service to confirm. */
    static BaseusWake wake(JSONObject cfg, BaseusCloud.Camera cam, long timeoutMs) throws IOException {
        String host = cfg.optString("host");
        if (host.isEmpty() || host.contains("/")) throw new IOException("Bad MQTT host");
        long deadline = System.currentTimeMillis() + timeoutMs;
        SSLSocket ssl = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
        ssl.connect(new InetSocketAddress(host, cfg.optInt("port", 8883)), 10_000);
        SSLParameters p = ssl.getSSLParameters();
        p.setEndpointIdentificationAlgorithm("HTTPS"); // verify the broker's certificate name
        ssl.setSSLParameters(p);
        ssl.setSoTimeout(0);
        ssl.startHandshake();
        BaseusWake w = new BaseusWake(ssl);
        try {
            w.start();
            w.connect(cfg, remaining(deadline));
            w.subscribe(cfg.optString("subscribe"), remaining(deadline));
            w.publishWake(cfg.optString("publish"), cam, remaining(deadline));
            if (!w.replied.await(remaining(deadline), TimeUnit.MILLISECONDS)) throw new IOException("No WatchDevice reply");
            if (!w.wakeResult) throw new IOException("Wake refused");
            return w;
        } catch (InterruptedException e) {
            w.close();
            throw new IOException("Interrupted");
        } catch (IOException e) {
            w.close();
            throw e;
        }
    }

    private static long remaining(long deadline) throws IOException {
        long r = deadline - System.currentTimeMillis();
        if (r <= 0) throw new IOException("Wake timed out");
        return r;
    }

    void close() {
        if (closed) return;
        closed = true;
        try { synchronized (writeLock) { out.write(new byte[]{(byte) 0xE0, 0}); out.flush(); } } catch (Exception ignored) { }
        try { socket.close(); } catch (Exception ignored) { }
    }

    boolean isOpen() { return !closed && !socket.isClosed(); }

    // ------------------------------------------------------------------ packets

    private void start() {
        reader = new Thread(this::readLoop, "mqtt-read");
        reader.setDaemon(true);
        reader.start();
    }

    private void connect(JSONObject cfg, long wait) throws IOException, InterruptedException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        str(b, "MQTT");
        b.write(4);                       // protocol level 3.1.1
        b.write(0xC2);                    // username + password + clean session
        b.write(0); b.write(60);          // keep alive 60 s
        str(b, cfg.optString("client_id"));
        str(b, cfg.optString("username"));
        str(b, cfg.optString("password"));
        send(0x10, b.toByteArray());
        if (!connack.await(wait, TimeUnit.MILLISECONDS)) throw new IOException("MQTT: no CONNACK");
        if (connackCode != 0) throw new IOException("MQTT: connection refused (" + connackCode + ")");
        pinger = new Thread(() -> {
            while (!closed) {
                try { Thread.sleep(25_000); send(0xC0, new byte[0]); } catch (Exception e) { return; }
            }
        }, "mqtt-ping");
        pinger.setDaemon(true);
        pinger.start();
    }

    private void subscribe(String topic, long wait) throws IOException, InterruptedException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int id = nextId++;
        b.write(id >> 8); b.write(id & 255);
        str(b, topic);
        b.write(1);                       // QoS 1
        send(0x82, b.toByteArray());
        if (!suback.await(wait, TimeUnit.MILLISECONDS)) throw new IOException("MQTT: no SUBACK");
        if (subackCode == 0x80) throw new IOException("MQTT: subscription refused");
    }

    private void publishWake(String topic, BaseusCloud.Camera cam, long wait) throws IOException, InterruptedException {
        String json;
        try {
            json = new JSONObject().put("method", "Action").put("actionId", "WatchDevice")
                    .put("expands", new JSONObject().put("productId", cam.productId).put("sn", cam.sn).put("wake_key", cam.wakeKey))
                    .put("payload", new JSONObject().put("timestamp", System.currentTimeMillis()).put("mode", 1))
                    .put("seq", seq).toString();
        } catch (Exception e) {
            throw new IOException("Bad wake message");
        }
        int id = nextId++;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        str(b, topic);
        b.write(id >> 8); b.write(id & 255);
        b.write(json.getBytes(StandardCharsets.UTF_8));
        send(0x34, b.toByteArray());      // PUBLISH, QoS 2
        long end = System.currentTimeMillis() + wait;
        synchronized (pubLock) {
            while (pubrecId != id) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) throw new IOException("MQTT: no PUBREC");
                pubLock.wait(left);
            }
        }
        send(0x62, new byte[]{(byte) (id >> 8), (byte) id}); // PUBREL
        synchronized (pubLock) {
            while (pubcompId != id) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;     // the wake itself is confirmed by the reply, not by PUBCOMP
                pubLock.wait(left);
            }
        }
    }

    private void readLoop() {
        try {
            while (!closed) {
                int first = in.read();
                if (first < 0) break;
                int len = 0, mult = 1, d;
                do { d = in.read(); if (d < 0) throw new IOException("closed"); len += (d & 127) * mult; mult *= 128; } while ((d & 128) != 0);
                byte[] body = new byte[len];
                in.readFully(body);
                int type = first >> 4;
                switch (type) {
                    case 2: connackCode = len >= 2 ? body[1] & 255 : 255; connack.countDown(); break;
                    case 9: subackCode = len >= 3 ? body[2] & 255 : 255; suback.countDown(); break;
                    case 5: synchronized (pubLock) { pubrecId = ((body[0] & 255) << 8) | (body[1] & 255); pubLock.notifyAll(); } break;
                    case 7: synchronized (pubLock) { pubcompId = ((body[0] & 255) << 8) | (body[1] & 255); pubLock.notifyAll(); } break;
                    case 3: onPublish(first, body); break;
                    case 6: send(0x70, new byte[]{body[0], body[1]}); break;   // incoming QoS 2 release
                    default: break; // PINGRESP, PUBACK...
                }
            }
        } catch (IOException ignored) {
        } finally {
            closed = true;
        }
    }

    private void onPublish(int first, byte[] body) throws IOException {
        int qos = (first >> 1) & 3;
        boolean retained = (first & 1) != 0;
        int tl = ((body[0] & 255) << 8) | (body[1] & 255);
        int p = 2 + tl;
        int id = 0;
        if (qos > 0) { id = ((body[p] & 255) << 8) | (body[p + 1] & 255); p += 2; }
        if (qos == 1) send(0x40, new byte[]{(byte) (id >> 8), (byte) id});
        if (qos == 2) send(0x50, new byte[]{(byte) (id >> 8), (byte) id});
        if (retained) return;
        try {
            JSONObject v = new JSONObject(new String(body, p, body.length - p, StandardCharsets.UTF_8));
            if (seq.equals(v.optString("seq")) && "WatchDevice".equals(v.optString("actionId")) && v.has("result")
                    && v.get("result") instanceof Boolean) {
                wakeResult = v.getBoolean("result");
                replied.countDown();
            }
        } catch (Exception ignored) { }
    }

    private void send(int header, byte[] body) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(header);
        int n = body.length;
        do { int d = n % 128; n /= 128; if (n > 0) d |= 128; f.write(d); } while (n > 0);
        f.write(body);
        synchronized (writeLock) { out.write(f.toByteArray()); out.flush(); }
    }

    private static void str(ByteArrayOutputStream b, String s) throws IOException {
        byte[] x = s.getBytes(StandardCharsets.UTF_8);
        b.write(x.length >> 8); b.write(x.length & 255); b.write(x);
    }
}
