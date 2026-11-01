package com.nabil.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Solar screen: animated power flow (solar, grid, home, battery around the inverter, dots running the way the power
 * goes, faster the more there is), the battery's charge and today's energy on the right, and today's solar, home use
 * and battery charge as a chart along the bottom. All drawn here; data from SolisCloud every minute (its own data
 * refreshes every 5). Back closes (handled by HomeActivity).
 */
class SolarLayer extends View {
    private static final int SOLAR = 0xFFFFD60A, HOME = 0xFF64D2FF, GRID = 0xFFBF5AF2, BATTERY = 0xFF30D158,
            DIM = 0x99FFFFFF, FAINT = 0x33FFFFFF, RED = 0xFFFF453A, ORANGE = 0xFFFF9F0A;
    private static final long POLL_MS = 60_000, DAY_MS = 5 * 60_000;

    private final Handler ui;
    private final SolisCloud solis;
    private final float dp;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path path = new Path();
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);

    private SolisCloud.Reading now;
    private List<SolisCloud.Point> day = new ArrayList<>();
    private String error;
    private long lastDay;
    private boolean loading;
    private int gen;

    SolarLayer(Context c, Handler ui, SolisCloud solis) {
        super(c);
        this.ui = ui;
        this.solis = solis;
        dp = c.getResources().getDisplayMetrics().density;
        setVisibility(GONE);
        setFocusable(true);
        setClickable(true);
    }

    boolean isOpen() { return getVisibility() == VISIBLE; }

    void open() {
        if (!solis.configured()) return;
        gen++;
        setAlpha(0f);
        setVisibility(VISIBLE);
        animate().alpha(1f).setDuration(180).start();
        requestFocus();
        poll.run();
    }

    void close() {
        gen++;
        ui.removeCallbacks(poll);
        setVisibility(GONE);
    }

    /** The remote's arrows stay here (the dock underneath is still focusable). */
    @Override public View focusSearch(int direction) { return this; }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            ui.removeCallbacks(this);
            if (!isOpen()) return;
            loading = now == null;
            final int g = gen;
            final boolean wantDay = SystemClock.uptimeMillis() - lastDay > DAY_MS || day.isEmpty();
            worker.execute(() -> {
                SolisCloud.Reading r = null;
                List<SolisCloud.Point> d = null;
                String err = null;
                try {
                    r = solis.now();
                    if (wantDay) d = solis.today();
                } catch (Exception e) {
                    err = e.getMessage();
                    Log.w("Solis", "poll: " + err);
                }
                final SolisCloud.Reading fr = r;
                final List<SolisCloud.Point> fd = d;
                final String fe = err;
                ui.post(() -> {
                    if (g != gen) return;
                    loading = false;
                    if (fr != null) now = fr;
                    if (fd != null) { day = fd; lastDay = SystemClock.uptimeMillis(); }
                    error = fe;
                    invalidate();
                });
            });
            ui.postDelayed(this, POLL_MS);
        }
    };

    // ------------------------------------------------------------------ drawing

    private float px(float v) { return v * dp; }

    private static String power(double w) {
        w = Math.abs(w);
        return w < 1000 ? String.format(Locale.US, "%.0f W", w) : String.format(Locale.US, "%.1f kW", w / 1000);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        long t = SystemClock.uptimeMillis();
        // background: deep blue-black with a warm glow where the sun sits
        p.setShader(new LinearGradient(0, 0, w, h, 0xFF101622, 0xFF050608, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(new RadialGradient(w * 0.30f, px(60), w * 0.35f, 0x24FFD60A, 0x00FFD60A, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        drawHeader(c, w);
        float top = px(96), bottom = h - px(196);
        drawFlow(c, px(40), top, w * 0.58f, bottom, t);
        drawCard(c, w * 0.60f, top, w - px(40), bottom);
        drawChart(c, px(40), h - px(172), w - px(40), h - px(28));

        if (isOpen()) postInvalidateOnAnimation();           // the flow dots move
    }

    private void text(Canvas c, String s, float x, float y, float sp, int color, boolean bold) {
        p.setTextSize(px(sp));
        p.setColor(color);
        p.setTypeface(bold ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.create("sans-serif-light", Typeface.NORMAL));
        c.drawText(s, x, y, p);
    }

    private float width(String s, float sp, boolean bold) {
        p.setTextSize(px(sp));
        p.setTypeface(bold ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.create("sans-serif-light", Typeface.NORMAL));
        return p.measureText(s);
    }

    private void drawHeader(Canvas c, int w) {
        text(c, solis.name(), px(44), px(52), 26, Color.WHITE, false);
        String status;
        int color = DIM;
        if (now != null && now.demo) { status = "Demo data — send your SolisCloud API key with ./solis-setup.sh"; color = ORANGE; }
        else if (error != null) { status = "Couldn't reach SolisCloud" + (now != null ? " — showing " + hm.format(new Date(now.time)) : "") + "  (" + error + ")"; color = RED; }
        else if (now == null) status = loading ? "Connecting to SolisCloud…" : "";
        else {
            long ago = (System.currentTimeMillis() - now.time) / 60_000;
            status = "SolisCloud   ·   updated " + hm.format(new Date(now.time)) + (ago >= 1 ? "  (" + ago + " min ago)" : "  (just now)");
        }
        if (status.length() > 110) status = status.substring(0, 110) + "…";
        text(c, status, px(46), px(76), 13, color, false);
        String hint = "Back: close";
        text(c, hint, w - px(44) - width(hint, 14, false), px(52), 14, DIM, false);
    }

    // ---- power flow

    private void drawFlow(Canvas c, float l, float t, float rr, float b, long time) {
        float cx = (l + rr) / 2, cy = (t + b) / 2 + px(6), dx = Math.min((rr - l) / 2 - px(70), px(190)), dy = (b - t) / 2 - px(44);
        float nr = px(34);
        float[][] at = {{cx, cy - dy}, {cx - dx, cy}, {cx + dx, cy}, {cx, cy + dy}};   // solar, grid, home, battery
        int[] col = {SOLAR, GRID, HOME, BATTERY};
        SolisCloud.Reading n = now;
        double[] watts = n == null ? new double[4] : new double[]{n.solar, n.grid, n.home, n.battery};
        // which way each line runs: +1 towards the inverter, -1 away from it
        int[] dir = {1, n != null && n.grid > 0 ? 1 : -1, -1, n != null && n.battery > 0 ? -1 : 1};

        for (int i = 0; i < 4; i++) {
            float x = at[i][0], y = at[i][1];
            double v = Math.abs(watts[i]);
            boolean on = n != null && v >= 20;
            float len = (float) Math.hypot(x - cx, y - cy), ux = (cx - x) / len, uy = (cy - y) / len;
            float x0 = x + ux * (nr + px(6)), y0 = y + uy * (nr + px(6)), x1 = cx - ux * px(20), y1 = cy - uy * px(20);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(px(3));
            p.setColor(on ? (col[i] & 0x00FFFFFF) | 0x55000000 : 0x1FFFFFFF);
            c.drawLine(x0, y0, x1, y1, p);
            p.setStyle(Paint.Style.FILL);
            if (on) {
                float seg = (float) Math.hypot(x1 - x0, y1 - y0), gap = px(22);
                float speed = px(30) + px(26) * (float) Math.log10(1 + v / 100);     // dp per second
                float shift = (time * speed / 1000f) % gap;
                for (float s = shift; s < seg; s += gap) {
                    float f = (dir[i] > 0 ? s : seg - s) / seg;
                    float fade = Math.min(1, Math.min(s, seg - s) / px(14));
                    p.setColor(col[i]);
                    p.setAlpha((int) (255 * fade));
                    c.drawCircle(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, px(3.6f), p);
                }
                p.setAlpha(255);
            }
        }
        // inverter hub
        p.setColor(0xFF1C2230);
        c.drawCircle(cx, cy, px(20), p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(px(2));
        p.setColor(0x66FFFFFF);
        c.drawCircle(cx, cy, px(20), p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xCCFFFFFF);
        path.reset();                                           // a small lightning bolt
        path.moveTo(cx + px(2), cy - px(11)); path.lineTo(cx - px(6), cy + px(2)); path.lineTo(cx - px(0.5f), cy + px(2));
        path.lineTo(cx - px(2), cy + px(11)); path.lineTo(cx + px(6), cy - px(2)); path.lineTo(cx + px(0.5f), cy - px(2)); path.close();
        c.drawPath(path, p);

        String[] name = {"Solar", n != null && n.grid < -20 ? "Selling" : n != null && n.grid > 20 ? "Buying" : "Grid", "Home",
                n != null && n.battery > 20 ? "Charging" : n != null && n.battery < -20 ? "Discharging" : "Battery"};
        for (int i = 0; i < 4; i++) {
            float x = at[i][0], y = at[i][1];
            p.setShader(new RadialGradient(x, y, nr * 1.9f, (col[i] & 0x00FFFFFF) | 0x30000000, 0, Shader.TileMode.CLAMP));
            c.drawCircle(x, y, nr * 1.9f, p);
            p.setShader(null);
            p.setColor(0xFF161B26);
            c.drawCircle(x, y, nr, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(px(2.5f));
            p.setColor(col[i]);
            c.drawCircle(x, y, nr, p);
            p.setStyle(Paint.Style.FILL);
            icon(c, i, x, y, nr * 0.55f, col[i], n);
            String v = n == null ? "—" : power(watts[i]);
            boolean below = i != 0;
            float ty = below ? y + nr + px(24) : y - nr - px(30);
            text(c, v, x - width(v, 19, true) / 2, ty, 19, Color.WHITE, true);
            text(c, name[i], x - width(name[i], 12, false) / 2, ty + px(17), 12, DIM, false);
        }
    }

    /** Sun, pylon, house, battery (filled to its charge). */
    private void icon(Canvas c, int i, float x, float y, float s, int color, SolisCloud.Reading n) {
        p.setColor(color);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        if (i == 0) {
            c.drawCircle(x, y, s * 0.42f, p);
            p.setStrokeWidth(px(2.4f));
            p.setStyle(Paint.Style.STROKE);
            for (int k = 0; k < 8; k++) {
                double a = Math.PI * k / 4;
                c.drawLine(x + (float) Math.cos(a) * s * 0.66f, y + (float) Math.sin(a) * s * 0.66f,
                        x + (float) Math.cos(a) * s * 0.92f, y + (float) Math.sin(a) * s * 0.92f, p);
            }
        } else if (i == 1) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(px(2.2f));
            path.reset();
            path.moveTo(x - s * 0.55f, y + s * 0.95f); path.lineTo(x - s * 0.12f, y - s * 0.9f); path.lineTo(x + s * 0.12f, y - s * 0.9f);
            path.lineTo(x + s * 0.55f, y + s * 0.95f);
            path.moveTo(x - s * 0.7f, y - s * 0.45f); path.lineTo(x + s * 0.7f, y - s * 0.45f);
            path.moveTo(x - s * 0.5f, y + s * 0.05f); path.lineTo(x + s * 0.5f, y + s * 0.05f);
            path.moveTo(x - s * 0.33f, y - s * 0.45f); path.lineTo(x + s * 0.4f, y + s * 0.05f);
            path.moveTo(x + s * 0.33f, y - s * 0.45f); path.lineTo(x - s * 0.4f, y + s * 0.05f);
            c.drawPath(path, p);
        } else if (i == 2) {
            path.reset();
            path.moveTo(x, y - s * 0.85f); path.lineTo(x + s * 0.85f, y - s * 0.05f); path.lineTo(x + s * 0.62f, y - s * 0.05f);
            path.lineTo(x + s * 0.62f, y + s * 0.8f); path.lineTo(x - s * 0.62f, y + s * 0.8f); path.lineTo(x - s * 0.62f, y - s * 0.05f);
            path.lineTo(x - s * 0.85f, y - s * 0.05f); path.close();
            c.drawPath(path, p);
            p.setColor(0xFF161B26);
            c.drawRoundRect(new RectF(x - s * 0.18f, y + s * 0.25f, x + s * 0.18f, y + s * 0.8f), s * 0.06f, s * 0.06f, p);
        } else {
            float bw = s * 0.95f, bh = s * 1.55f;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(px(2.2f));
            r.set(x - bw / 2, y - bh / 2 + s * 0.12f, x + bw / 2, y + bh / 2);
            c.drawRoundRect(r, s * 0.12f, s * 0.12f, p);
            p.setStyle(Paint.Style.FILL);
            c.drawRect(x - bw * 0.2f, y - bh / 2 - s * 0.05f, x + bw * 0.2f, y - bh / 2 + s * 0.12f, p);
            float soc = n == null || n.soc < 0 ? 0 : (float) Math.min(100, n.soc) / 100f;
            float in = px(3.2f), ft = r.bottom - in - (r.height() - 2 * in) * soc;
            c.drawRect(r.left + in, ft, r.right - in, r.bottom - in, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    // ---- battery and today's energy

    private void drawCard(Canvas c, float l, float t, float rr, float b) {
        r.set(l, t, rr, b);
        p.setColor(0x12FFFFFF);
        c.drawRoundRect(r, px(22), px(22), p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(px(1));
        p.setColor(0x1AFFFFFF);
        c.drawRoundRect(r, px(22), px(22), p);
        p.setStyle(Paint.Style.FILL);
        SolisCloud.Reading n = now;

        // battery ring
        float rad = Math.min(px(62), (b - t) / 2 - px(40)), cx = l + px(28) + rad, cy = t + px(30) + rad;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(px(11));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(0x1FFFFFFF);
        RectF ring = new RectF(cx - rad, cy - rad, cx + rad, cy + rad);
        c.drawArc(ring, 0, 360, false, p);
        double soc = n == null ? -1 : n.soc;
        if (soc >= 0) {
            int ringColor = soc < 20 ? RED : soc < 40 ? ORANGE : BATTERY;
            p.setShader(new LinearGradient(cx, cy - rad, cx, cy + rad, ringColor, (ringColor & 0x00FFFFFF) | 0x99000000, Shader.TileMode.CLAMP));
            c.drawArc(ring, -90, (float) (360 * Math.min(100, soc) / 100), false, p);
            p.setShader(null);
        }
        p.setStyle(Paint.Style.FILL);
        String pct = soc < 0 ? "—" : String.format(Locale.US, "%.0f%%", soc);
        text(c, pct, cx - width(pct, 30, true) / 2, cy + px(8), 30, Color.WHITE, true);
        text(c, "Battery", cx - width("Battery", 12, false) / 2, cy + px(26), 12, DIM, false);
        String st = n == null ? "" : n.battery > 20 ? "Charging " + power(n.battery) : n.battery < -20 ? "Discharging " + power(n.battery) : "Idle";
        text(c, st, cx - width(st, 13, true) / 2, cy + rad + px(30), 13, n != null && n.battery > 20 ? BATTERY : n != null && n.battery < -20 ? ORANGE : DIM, true);

        // today
        float sx = cx + rad + px(34), sw = rr - px(24) - sx, colW = sw / 2;
        text(c, "TODAY", sx, t + px(34), 12, DIM, true);
        String[][] cells = {
                {"Generated", n == null ? "—" : fmt(n.genToday)}, {"Used", n == null ? "—" : fmt(n.homeToday)},
                {"Bought", n == null ? "—" : fmt(n.boughtToday)}, {"Sold", n == null ? "—" : fmt(n.soldToday)},
                {"Charged", n == null ? "—" : fmt(n.chargedToday)}, {"Discharged", n == null ? "—" : fmt(n.dischargedToday)}};
        int[] dot = {SOLAR, HOME, GRID, GRID, BATTERY, BATTERY};
        float rowH = Math.min(px(52), (b - t - px(60)) / 3);
        for (int k = 0; k < 6; k++) {
            float x = sx + (k % 2) * colW, y = t + px(56) + (k / 2) * rowH;
            p.setColor(dot[k]);
            c.drawCircle(x + px(4), y + px(8), px(4), p);
            text(c, cells[k][0], x + px(14), y + px(12), 12, DIM, false);
            String v = cells[k][1];
            text(c, v, x, y + px(36), 20, Color.WHITE, true);
            if (n != null) text(c, " kWh", x + width(v, 20, true), y + px(36), 12, DIM, false);
        }
    }

    private static String fmt(double kwh) { return String.format(Locale.US, kwh >= 100 ? "%.0f" : "%.1f", kwh); }

    // ---- today's chart

    private void drawChart(Canvas c, float l, float t, float rr, float b) {
        text(c, "Today", l + px(4), t + px(4), 15, Color.WHITE, true);
        float lx = l + px(64);
        String[] leg = {"Solar", "Home", "Battery %"};
        int[] lc = {SOLAR, HOME, BATTERY};
        for (int k = 0; k < 3; k++) {
            p.setColor(lc[k]);
            c.drawRoundRect(new RectF(lx, t - px(4), lx + px(14), t + px(1)), px(2), px(2), p);
            text(c, leg[k], lx + px(20), t + px(4), 12, DIM, false);
            lx += px(20) + width(leg[k], 12, false) + px(22);
        }
        float gt = t + px(18), gb = b - px(18), gl = l + px(4), gr = rr - px(4);
        double max = 1000;
        for (SolisCloud.Point q : day) max = Math.max(max, Math.max(q.solar, q.home));
        max = Math.ceil(max / 1000) * 1000;
        // grid lines and labels
        p.setStrokeWidth(px(1));
        for (int k = 0; k <= 2; k++) {
            float y = gt + (gb - gt) * k / 2f;
            p.setColor(0x14FFFFFF);
            c.drawLine(gl, y, gr, y, p);
        }
        String ml = power(max);
        text(c, ml, gr - width(ml, 11, false), gt - px(4), 11, DIM, false);
        for (int hr = 0; hr <= 24; hr += 3) {
            float x = gl + (gr - gl) * hr / 24f;
            String s = String.format(Locale.US, "%02d", hr);
            float tw = width(s, 11, false);
            text(c, s, Math.max(gl, Math.min(gr - tw, x - tw / 2)), b, 11, 0x80FFFFFF, false);
        }
        if (day.size() < 2) {
            String s = now == null ? "" : "No history yet today";
            text(c, s, (gl + gr - width(s, 13, false)) / 2, (gt + gb) / 2 + px(4), 13, DIM, false);
            return;
        }
        float span = gr - gl;
        // solar: filled area
        path.reset();
        path.moveTo(gl + span * day.get(0).minute / 1440f, gb);
        for (SolisCloud.Point q : day) path.lineTo(gl + span * q.minute / 1440f, gb - (gb - gt) * (float) (q.solar / max));
        path.lineTo(gl + span * day.get(day.size() - 1).minute / 1440f, gb);
        path.close();
        p.setShader(new LinearGradient(0, gt, 0, gb, 0x99FFD60A, 0x10FFD60A, Shader.TileMode.CLAMP));
        c.drawPath(path, p);
        p.setShader(null);
        // home and battery %: lines
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        for (int k = 0; k < 2; k++) {
            path.reset();
            boolean first = true;
            for (SolisCloud.Point q : day) {
                double v = k == 0 ? q.home / max : q.soc / 100;
                if (k == 1 && q.soc < 0) continue;
                float x = gl + span * q.minute / 1440f, y = gb - (gb - gt) * (float) Math.min(1, v);
                if (first) path.moveTo(x, y); else path.lineTo(x, y);
                first = false;
            }
            p.setColor(k == 0 ? HOME : BATTERY);
            p.setStrokeWidth(px(k == 0 ? 2 : 1.6f));
            p.setPathEffect(k == 1 ? new DashPathEffect(new float[]{px(5), px(4)}, 0) : null);
            c.drawPath(path, p);
        }
        p.setPathEffect(null);
        p.setStyle(Paint.Style.FILL);
        // now
        SolisCloud.Point last = day.get(day.size() - 1);
        float xn = gl + span * last.minute / 1440f;
        p.setColor(0x66FFFFFF);
        c.drawRect(xn - px(0.5f), gt, xn + px(0.5f), gb, p);
        p.setColor(SOLAR);
        c.drawCircle(xn, gb - (gb - gt) * (float) (last.solar / max), px(4), p);
    }
}
