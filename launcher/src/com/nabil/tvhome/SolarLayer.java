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
 * Solar screen: an illustrated house (panels on the roof, inverter and meter on the wall, battery in front, lit
 * windows) with PV, Load, Battery and Grid callouts, and dots running along the cables the way the power flows,
 * faster the more there is. The arrows pick a part; the panel on the right shows it now, today, and today's curve.
 * Data from SolisCloud every minute (its own data refreshes every 5). Back closes (handled by HomeActivity).
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
            busy = true;
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
                    busy = false;
                    if (fe == null && spinAt > 0) doneAt = SystemClock.uptimeMillis();
                    spinAt = 0;
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

    // ------------------------------------------------------------------ remote: pick PV, Load, Battery or Grid

    /** 0 PV (top left), 1 Load (top right), 2 Battery (bottom left), 3 Grid (bottom right). */
    private int sel = 2;

    @Override public boolean onKeyDown(int code, android.view.KeyEvent e) {
        if (onRefresh) {                                   // the Refresh button (top right) has the focus
            switch (code) {
                case android.view.KeyEvent.KEYCODE_DPAD_DOWN: onRefresh = false; selAt = SystemClock.uptimeMillis(); break;
                case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
                case android.view.KeyEvent.KEYCODE_ENTER: if (e.getRepeatCount() == 0) refresh(); break;
                case android.view.KeyEvent.KEYCODE_DPAD_LEFT:
                case android.view.KeyEvent.KEYCODE_DPAD_RIGHT:
                case android.view.KeyEvent.KEYCODE_DPAD_UP: break;
                default: return super.onKeyDown(code, e);
            }
            invalidate();
            return true;
        }
        if (code == android.view.KeyEvent.KEYCODE_DPAD_UP && sel < 2) { onRefresh = true; invalidate(); return true; }
        int was = sel;
        switch (code) {
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT: sel &= ~1; break;
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: sel |= 1; break;
            case android.view.KeyEvent.KEYCODE_DPAD_UP: sel &= ~2; break;
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN: sel |= 2; break;
            case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
            case android.view.KeyEvent.KEYCODE_ENTER: sel = (sel + 1) % 4; break;
            default: return super.onKeyDown(code, e);
        }
        if (sel != was) selAt = SystemClock.uptimeMillis();
        invalidate();
        return true;
    }

    private long selAt;
    private boolean onRefresh, busy;
    private long spinAt, doneAt;

    /** Fetch now, today's curve included (SolisCloud itself only has new data every 5 minutes). */
    private void refresh() {
        if (busy) return;
        spinAt = SystemClock.uptimeMillis();
        doneAt = 0;
        lastDay = 0;
        poll.run();
    }

    // ------------------------------------------------------------------ drawing

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        long t = SystemClock.uptimeMillis();
        p.setColor(Color.WHITE);
        p.setShader(new LinearGradient(0, 0, 0, h, 0xFF15171C, 0xFF050506, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        drawHeader(c, w);
        drawScene(c, px(24), px(92), w * 0.60f, h - px(16), t);
        drawPanel(c, w * 0.61f, px(104), w - px(40), h - px(36));

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
        float bx = drawRefresh(c, w - px(44), px(30));
        String hint = onRefresh ? "OK: refresh   ·   ▼ back   ·   Back: close" : "◀ ▶ ▲ ▼ choose   ·   Back: close";
        text(c, hint, bx - px(18) - width(hint, 14, false), px(52), 14, DIM, false);
    }

    /** The Refresh button with its right edge at x; returns its left edge. Spins while fetching, ticks when done. */
    private float drawRefresh(Canvas c, float x, float y) {
        long t = SystemClock.uptimeMillis();
        boolean spinning = spinAt > 0, done = doneAt > 0 && t - doneAt < 2500;
        String label = spinning ? "Refreshing…" : done ? "Updated" : "Refresh";
        float tw = width(label, 15, true), bw = tw + px(56), h = px(34);
        RectF b = new RectF(x - bw, y, x, y + h);
        p.setStyle(Paint.Style.FILL);
        if (onRefresh) {
            p.setColor(0x40FFFFFF);
            c.drawRoundRect(new RectF(b.left - px(4), b.top - px(4), b.right + px(4), b.bottom + px(4)), h, h, p);
        }
        p.setColor(onRefresh ? Color.WHITE : 0x1FFFFFFF);
        c.drawRoundRect(b, h / 2, h / 2, p);
        int fg = onRefresh ? 0xFF111111 : Color.WHITE;
        float cx = b.left + px(22), cy = b.centerY(), rad = px(7);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(px(2.2f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(done ? BATTERY : fg);
        if (done) {                                        // a tick
            c.drawLine(cx - rad * 0.8f, cy, cx - rad * 0.2f, cy + rad * 0.6f, p);
            c.drawLine(cx - rad * 0.2f, cy + rad * 0.6f, cx + rad * 0.9f, cy - rad * 0.6f, p);
        } else {                                           // a circular arrow, turning while fetching
            float a0 = spinning ? (t - spinAt) * 0.45f % 360 : -60;
            c.drawArc(new RectF(cx - rad, cy - rad, cx + rad, cy + rad), a0, 290, false, p);
            double end = Math.toRadians(a0 + 290);
            float ex = cx + rad * (float) Math.cos(end), ey = cy + rad * (float) Math.sin(end);
            double tangent = end + Math.PI / 2;
            p.setStyle(Paint.Style.FILL);
            path.reset();
            path.moveTo(ex + px(4) * (float) Math.cos(tangent), ey + px(4) * (float) Math.sin(tangent));
            path.lineTo(ex + px(3.5f) * (float) Math.cos(end), ey + px(3.5f) * (float) Math.sin(end));
            path.lineTo(ex - px(3.5f) * (float) Math.cos(end), ey - px(3.5f) * (float) Math.sin(end));
            path.close();
            c.drawPath(path, p);
        }
        p.setStyle(Paint.Style.FILL);
        text(c, label, b.left + px(38), cy + px(5.5f), 15, done && !onRefresh ? BATTERY : fg, true);
        return b.left;
    }

    // ------------------------------------------------------------------ the house (isometric)

    // world units: x along the front of the house (left to right), y towards the viewer, z up
    private float ox, oy, u;
    private static final float C30 = 0.866f;

    private float sx(float x, float y) { return ox + (x - y) * C30 * u; }
    private float sy(float x, float y, float z) { return oy + ((x + y) * 0.5f - z) * u; }

    private Path poly(float[]... pts) {
        Path q = new Path();
        for (int i = 0; i < pts.length; i++) {
            float X = sx(pts[i][0], pts[i][1]), Y = sy(pts[i][0], pts[i][1], pts[i][2]);
            if (i == 0) q.moveTo(X, Y); else q.lineTo(X, Y);
        }
        q.close();
        return q;
    }

    private void fill(Canvas c, int color, float[]... pts) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        c.drawPath(poly(pts), p);
    }

    private static float[] v(float x, float y, float z) { return new float[]{x, y, z}; }

    /** A box seen from the front-right: its front (y1), right (x1) and top faces. */
    private void box(Canvas c, float x0, float x1, float y0, float y1, float z0, float z1, int front, int side, int top) {
        fill(c, side, v(x1, y0, z0), v(x1, y1, z0), v(x1, y1, z1), v(x1, y0, z1));
        fill(c, front, v(x0, y1, z0), v(x1, y1, z0), v(x1, y1, z1), v(x0, y1, z1));
        fill(c, top, v(x0, y0, z1), v(x1, y0, z1), v(x1, y1, z1), v(x0, y1, z1));
    }

    /** A lit window on the front wall (y = 6) or the right gable (x = 10). */
    private void window(Canvas c, boolean front, float a0, float a1, float z0, float z1, float glow) {
        Path q = front ? poly(v(a0, 6.01f, z0), v(a1, 6.01f, z0), v(a1, 6.01f, z1), v(a0, 6.01f, z1))
                : poly(v(10.01f, a0, z0), v(10.01f, a1, z0), v(10.01f, a1, z1), v(10.01f, a0, z1));
        float cx = front ? sx((a0 + a1) / 2, 6) : sx(10, (a0 + a1) / 2), cy = front ? sy((a0 + a1) / 2, 6, (z0 + z1) / 2) : sy(10, (a0 + a1) / 2, (z0 + z1) / 2);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        p.setShader(new RadialGradient(cx, cy, u * 2.2f, (int) (0x55 * glow) << 24 | 0xFFB347, 0x00FFB347, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, u * 2.2f, p);
        p.setShader(new LinearGradient(0, sy(0, 6, z1), 0, sy(0, 6, z0), 0xFFFFD58A, 0xFFC7711F, Shader.TileMode.CLAMP));
        c.drawPath(q, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, u * 0.07f));
        p.setColor(0xFF2A2522);
        c.drawPath(q, p);
        float m = (a0 + a1) / 2, zm = z0 + (z1 - z0) * 0.62f;          // mullions
        if (front) {
            c.drawLine(sx(m, 6), sy(m, 6, z0), sx(m, 6), sy(m, 6, z1), p);
            c.drawLine(sx(a0, 6), sy(a0, 6, zm), sx(a1, 6), sy(a1, 6, zm), p);
        } else {
            c.drawLine(sx(10, m), sy(10, m, z0), sx(10, m), sy(10, m, z1), p);
            c.drawLine(sx(10, a0), sy(10, a0, zm), sx(10, a1), sy(10, a1, zm), p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    // anchors (world) the callouts point at: PV panel centre, a window (load), battery, meter
    private static final float[][] TARGET = {{5f, 4.7f, 5.4f}, {10f, 3f, 4.85f}, {2.2f, 7.2f, 1.5f}, {8.0f, 6.15f, 1.6f}};

    private void drawScene(Canvas c, float l, float t, float rr, float b, long time) {
        // fit the scene (x-y from -6.4 to 13.5, height from -5.5 to 10.5 units) into the box
        float wU = 19.9f * C30, hU = 16.5f;
        u = Math.min((rr - l) / wU, (b - t) / hU) * 0.86f;
        ox = (l + rr) / 2 - ((-6.4f + 13.5f) / 2 * C30) * u;
        oy = (t + b) / 2 - ((-5.5f + 10.5f) / 2) * u + px(10);
        SolisCloud.Reading n = now;
        double night = n == null ? 1 : Math.max(0, 1 - n.solar / 1500);

        // ground: a soft shadow and a slab
        p.setColor(Color.WHITE);
        p.setShader(new RadialGradient(sx(7.5f, 4), sy(7.5f, 4, 0), u * 11, 0x66000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawCircle(sx(7.5f, 4), sy(7.5f, 4, 0), u * 11, p);
        p.setShader(null);
        fill(c, 0xFF1A1B1F, v(-1, -1, 0), v(16, -1, 0), v(16, 8, 0), v(-1, 8, 0));
        fill(c, 0xFF101114, v(-1, 8, 0), v(16, 8, 0), v(16, 8, -0.35f), v(-1, 8, -0.35f));
        fill(c, 0xFF0C0D0F, v(16, -1, 0), v(16, 8, 0), v(16, 8, -0.35f), v(16, -1, -0.35f));

        // back roof slope and chimney (seen over the ridge), then the walls
        fill(c, 0xFF1B1C20, v(-0.3f, -0.5f, 3.8f), v(10.3f, -0.5f, 3.8f), v(10.3f, 3, 7), v(-0.3f, 3, 7));
        box(c, 6.6f, 7.5f, 1.4f, 2.3f, 5, 8.2f, 0xFF2B2D33, 0xFF1F2125, 0xFF15161A);
        fill(c, 0xFF1F2125, v(6.45f, 1.25f, 8.2f), v(7.65f, 1.25f, 8.2f), v(7.65f, 2.45f, 8.2f), v(6.45f, 2.45f, 8.2f));
        fill(c, 0xFF202227, v(10, 0, 0), v(10, 6, 0), v(10, 6, 4), v(10, 3, 7), v(10, 0, 4));      // right wall and gable
        fill(c, 0xFF2B2D33, v(0, 6, 0), v(10, 6, 0), v(10, 6, 4), v(0, 6, 4));                     // front wall
        float g = (float) (0.55 + 0.45 * night);
        window(c, true, 0.7f, 1.6f, 2.4f, 3.5f, g);
        window(c, true, 2.2f, 3.1f, 2.4f, 3.5f, g);
        window(c, true, 6.5f, 7.4f, 2.4f, 3.5f, g);
        window(c, true, 8.0f, 8.9f, 2.4f, 3.5f, g);
        window(c, true, 0.7f, 1.4f, 0.6f, 1.9f, g);
        window(c, true, 6.2f, 7.1f, 0.6f, 1.9f, g);
        window(c, false, 2.5f, 3.5f, 4.3f, 5.4f, g);
        fill(c, 0xFF3A2A1C, v(8.7f, 6.02f, 0), v(9.6f, 6.02f, 0), v(9.6f, 6.02f, 2.1f), v(8.7f, 6.02f, 2.1f));
        p.setColor(0xFFE0A55A);
        c.drawCircle(sx(9.45f, 6), sy(9.45f, 6, 1.0f), Math.max(1.5f, u * 0.06f), p);

        // garage annex in front of the right wall, with a ribbed roller door
        box(c, 10, 15, 1.5f, 6, 0, 2.8f, 0xFF26282D, 0xFF1C1E22, 0xFF2E3036);
        fill(c, 0xFF141518, v(10, 1.2f, 3.0f), v(15.3f, 1.2f, 2.8f), v(15.3f, 6.3f, 2.8f), v(10, 6.3f, 3.0f));
        float gd0 = 11, gd1 = 14.2f;
        fill(c, 0xFF1E2024, v(gd0, 6.01f, 0), v(gd1, 6.01f, 0), v(gd1, 6.01f, 2.2f), v(gd0, 6.01f, 2.2f));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, u * 0.05f));
        p.setColor(0xFF34373D);
        for (float z = 0.25f; z < 2.2f; z += 0.25f) c.drawLine(sx(gd0, 6), sy(gd0, 6, z), sx(gd1, 6), sy(gd1, 6, z), p);
        p.setStyle(Paint.Style.FILL);

        // front roof slope with the solar panels
        fill(c, 0xFF121316, v(-0.3f, 6.5f, 3.8f), v(10.3f, 6.5f, 3.8f), v(10.3f, 3, 7), v(-0.3f, 3, 7));
        fill(c, 0xFF0D0E10, v(10.3f, 6.5f, 3.8f), v(10.3f, 6.5f, 3.55f), v(-0.3f, 6.5f, 3.55f), v(-0.3f, 6.5f, 3.8f));
        drawPanels(c, n);

        // equipment: inverter and meter on the wall, battery in front
        box(c, 4.3f, 5.7f, 6, 6.3f, 1.2f, 2.8f, 0xFFF2F3F5, 0xFFC9CCD2, 0xFFFFFFFF);
        p.setColor(0xFFFF9F0A);
        c.drawCircle(sx(5.0f, 6.3f), sy(5.0f, 6.3f, 2.5f), Math.max(2, u * 0.12f), p);       // the sun badge
        fill(c, 0xFF9AA3B0, v(4.6f, 6.31f, 1.9f), v(5.4f, 6.31f, 1.9f), v(5.4f, 6.31f, 2.2f), v(4.6f, 6.31f, 2.2f));
        box(c, 7.6f, 8.4f, 6, 6.25f, 1.2f, 2.0f, 0xFFE9EBEE, 0xFFBFC3CA, 0xFFFFFFFF);
        box(c, 1.6f, 2.9f, 6.4f, 7.3f, 0, 2.0f, 0xFFF4F5F7, 0xFFCDD0D6, 0xFFFFFFFF);
        double soc = n == null || n.soc < 0 ? 0 : n.soc;                                       // charge strip
        int bars = (int) Math.round(soc / 20);
        for (int k = 0; k < 5; k++) {
            float z = 0.45f + k * 0.26f;
            fill(c, k < bars ? BATTERY : 0xFFB9BDC4, v(2.05f, 7.31f, z), v(2.45f, 7.31f, z), v(2.45f, 7.31f, z + 0.16f), v(2.05f, 7.31f, z + 0.16f));
        }

        // wires and the power running through them (dots run the way the power flows)
        float[][] pvWire = {{5f, 6.5f, 3.6f}, {5f, 6.15f, 3.2f}, {5f, 6.15f, 2.8f}};
        float[][] batWire = {{2.9f, 6.6f, 1.4f}, {4.3f, 6.6f, 1.4f}, {4.3f, 6.3f, 1.5f}};
        float[][] gridWire = {{5.7f, 6.15f, 1.9f}, {7.6f, 6.15f, 1.7f}};
        float[][] loadWire = {{5.0f, 6.15f, 1.2f}, {5.0f, 6.15f, 0.2f}};
        double pv = n == null ? 0 : n.solar, home = n == null ? 0 : n.home, bat = n == null ? 0 : n.battery, grid = n == null ? 0 : n.grid;
        wire(c, pvWire, pv, true, SOLAR, time);
        wire(c, batWire, Math.abs(bat), bat < 0, BATTERY, time);           // discharging: battery -> inverter
        wire(c, gridWire, Math.abs(grid), grid < 0, GRID, time);           // selling: inverter -> meter
        wire(c, loadWire, home, true, HOME, time);
        float[][] street = {{8.0f, 6.2f, 1.2f}, {8.0f, 6.6f, 0f}, {8.0f, 8.0f, 0f}};
        wire(c, street, Math.abs(grid), grid < 0, GRID, time);

        // callouts
        float[][] label = {{l + px(28), t + px(8)}, {l + (rr - l) * 0.62f, t + px(8)}, {l + px(28), b - px(84)}, {l + (rr - l) * 0.70f, b - px(84)}};
        String[] name = {"PV", "Load", "Battery" + (n != null && n.soc >= 0 ? String.format(Locale.US, "  %.0f%%", n.soc) : ""), "Grid"};
        double[] val = {pv, home, bat, grid};
        int[] col = {SOLAR, HOME, BATTERY, GRID};
        float pulse = (float) (0.5 + 0.5 * Math.sin((time - selAt) / 260.0));
        for (int i = 0; i < 4; i++) {
            boolean on = i == sel;
            float lx = label[i][0], ly = label[i][1];
            float tx = sx(TARGET[i][0], TARGET[i][1]), ty = sy(TARGET[i][0], TARGET[i][1], TARGET[i][2]);
            boolean up = i >= 2;                          // bottom labels lead upwards
            float lineTop = up ? ty : ly + px(4), lineBot = up ? ly + px(52) : ty;
            float hy = up ? lineTop : lineBot;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(px(on ? 1.6f : 1.1f));
            p.setColor(on ? col[i] : 0x80FFFFFF);
            p.setPathEffect(new DashPathEffect(new float[]{px(3), px(3)}, 0));
            path.reset();
            path.moveTo(lx - px(10), up ? lineBot : lineTop);
            path.lineTo(lx - px(10), hy);
            path.lineTo(tx, hy);
            path.lineTo(tx, ty);
            c.drawPath(path, p);
            p.setPathEffect(null);
            p.setStrokeWidth(px(on ? 3 : 1.6f));
            c.drawLine(lx - px(10), ly, lx - px(10), ly + px(48), p);    // the solid bar beside the label
            p.setStyle(Paint.Style.FILL);
            if (on) {
                p.setColor((col[i] & 0x00FFFFFF) | ((int) (0x40 + 0x50 * pulse) << 24));
                c.drawCircle(tx, ty, px(9) + px(5) * pulse, p);
                p.setColor(col[i]);
                c.drawCircle(tx, ty, px(4), p);
            } else {
                p.setColor(0xCCFFFFFF);
                c.drawCircle(tx, ty, px(2.5f), p);
            }
            // a dark plate behind the text keeps it readable over the house
            float vw = Math.max(width(name[i], 13, false), width(power(val[i]), 24, true)) + px(14);
            p.setColor(0xB30B0C0F);
            c.drawRoundRect(new RectF(lx - px(6), ly - px(2), lx + vw, ly + px(52)), px(8), px(8), p);
            text(c, name[i], lx, ly + px(15), 13, on ? col[i] : DIM, on);
            String pw = n == null ? "—" : power(val[i]);
            text(c, pw, lx, ly + px(44), 24, Color.WHITE, true);
            String st = status(i, n);
            if (!st.isEmpty()) {
                float sw = width(st, 11, true) + px(14), sy0 = ly + px(58);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(px(1));
                p.setColor(col[i]);
                c.drawRoundRect(new RectF(lx - px(2), sy0, lx - px(2) + sw, sy0 + px(19)), px(5), px(5), p);
                p.setStyle(Paint.Style.FILL);
                text(c, st, lx + px(5), sy0 + px(14), 11, col[i], true);
            }
        }
    }

    private static String status(int i, SolisCloud.Reading n) {
        if (n == null) return "";
        switch (i) {
            case 0: return n.solar >= 20 ? "Generating" : "";
            case 2: return n.battery > 20 ? "Charging" : n.battery < -20 ? "Discharging" : "Standby";
            case 3: return n.grid > 20 ? "Buying" : n.grid < -20 ? "Selling" : "";
            default: return "";
        }
    }

    /** 3 rows of 8 panels on the front roof slope; they glint when the sun is out. */
    private void drawPanels(Canvas c, SolisCloud.Reading n) {
        float sun = n == null ? 0 : (float) Math.min(1, n.solar / 4000);
        int cols = 8, rows = 3;
        for (int i = 0; i < cols; i++) for (int j = 0; j < rows; j++) {
            float x0 = 0.5f + i * 1.15f, x1 = x0 + 1.05f;
            float f0 = 0.10f + j * 0.28f, f1 = f0 + 0.25f;              // 0 at the eave, 1 at the ridge
            float y0 = 6.5f - 3.5f * f0, z0 = 3.8f + 3.2f * f0, y1 = 6.5f - 3.5f * f1, z1 = 3.8f + 3.2f * f1;
            Path q = poly(v(x0, y0, z0 + 0.05f), v(x1, y0, z0 + 0.05f), v(x1, y1, z1 + 0.05f), v(x0, y1, z1 + 0.05f));
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            int top = blend(0xFF232833, 0xFF3B5A86, sun), bottom = blend(0xFF16191F, 0xFF1D2C46, sun);
            p.setShader(new LinearGradient(0, sy(x0, y1, z1), 0, sy(x0, y0, z0), top, bottom, Shader.TileMode.CLAMP));
            c.drawPath(q, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1, u * 0.04f));
            p.setColor(0xFF3A3F49);
            c.drawPath(q, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    private static int blend(int a, int b, float f) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - f) + ((b >> 16) & 0xFF) * f), g = (int) (((a >> 8) & 0xFF) * (1 - f) + ((b >> 8) & 0xFF) * f),
                bl = (int) ((a & 0xFF) * (1 - f) + (b & 0xFF) * f);
        return 0xFF000000 | r << 16 | g << 8 | bl;
    }

    /** A cable along world points; with power flowing, glowing dots run along it (forward = in the point order). */
    private void wire(Canvas c, float[][] pts, double watts, boolean forward, int color, long time) {
        int m = pts.length;
        float[] X = new float[m], Y = new float[m], len = new float[m];
        for (int i = 0; i < m; i++) {
            X[i] = sx(pts[i][0], pts[i][1]);
            Y[i] = sy(pts[i][0], pts[i][1], pts[i][2]);
            if (i > 0) len[i] = len[i - 1] + (float) Math.hypot(X[i] - X[i - 1], Y[i] - Y[i - 1]);
        }
        boolean on = now != null && watts >= 20;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(Math.max(1.5f, u * 0.09f));
        p.setColor(on ? (color & 0x00FFFFFF) | 0x80000000 : 0xFF3A3D44);
        path.reset();
        path.moveTo(X[0], Y[0]);
        for (int i = 1; i < m; i++) path.lineTo(X[i], Y[i]);
        c.drawPath(path, p);
        p.setStyle(Paint.Style.FILL);
        if (!on) return;
        float total = len[m - 1], gap = px(16), speed = px(24) + px(22) * (float) Math.log10(1 + watts / 100);
        float shift = (time * speed / 1000f) % gap;
        for (float s = shift; s < total; s += gap) {
            float d = forward ? s : total - s;
            int k = 1;
            while (k < m - 1 && len[k] < d) k++;
            float f = (d - len[k - 1]) / Math.max(1, len[k] - len[k - 1]);
            float x = X[k - 1] + (X[k] - X[k - 1]) * f, y = Y[k - 1] + (Y[k] - Y[k - 1]) * f;
            p.setColor((color & 0x00FFFFFF) | 0x50000000);
            c.drawCircle(x, y, px(5), p);
            p.setColor(color);
            c.drawCircle(x, y, px(2.6f), p);
        }
    }

    // ------------------------------------------------------------------ details of the chosen part

    private void drawPanel(Canvas c, float l, float t, float rr, float b) {
        r.set(l, t, rr, b);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x14FFFFFF);
        c.drawRoundRect(r, px(22), px(22), p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(px(1));
        int[] col = {SOLAR, HOME, BATTERY, GRID};
        p.setColor((col[sel] & 0x00FFFFFF) | 0x66000000);
        c.drawRoundRect(r, px(22), px(22), p);
        p.setStyle(Paint.Style.FILL);
        SolisCloud.Reading n = now;
        float x = l + px(26), y = t + px(40);
        String[] title = {"Solar panels", "Home", "Battery", "Grid"};
        p.setColor(col[sel]);
        c.drawCircle(x + px(5), y - px(7), px(5), p);
        text(c, title[sel], x + px(18), y, 18, Color.WHITE, true);

        double[] val = n == null ? new double[4] : new double[]{n.solar, n.home, n.battery, n.grid};
        String big = n == null ? "—" : power(val[sel]);
        String sub;
        if (n == null) sub = "";
        else if (sel == 0) sub = n.solar >= 20 ? "Generating now" : "Not generating";
        else if (sel == 1) sub = "Using now";
        else if (sel == 2) sub = (n.battery > 20 ? "Charging" : n.battery < -20 ? "Discharging" : "Standby") + (n.soc >= 0 ? String.format(Locale.US, "  ·  %.0f%% full", n.soc) : "");
        else sub = n.grid > 20 ? "Buying from the grid" : n.grid < -20 ? "Selling to the grid" : "Not using the grid";
        text(c, big, x, y + px(58), 42, Color.WHITE, true);
        text(c, sub, x, y + px(84), 14, DIM, false);

        if (sel == 2 && n != null && n.soc >= 0) {               // battery: a charge ring at the right
            float rad = px(38), cx = rr - px(26) - rad, cy = y + px(40);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(px(8));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(0x1FFFFFFF);
            RectF ring = new RectF(cx - rad, cy - rad, cx + rad, cy + rad);
            c.drawArc(ring, 0, 360, false, p);
            p.setColor(n.soc < 20 ? RED : n.soc < 40 ? ORANGE : BATTERY);
            c.drawArc(ring, -90, (float) (360 * Math.min(100, n.soc) / 100), false, p);
            p.setStyle(Paint.Style.FILL);
            String pc = String.format(Locale.US, "%.0f%%", n.soc);
            text(c, pc, cx - width(pc, 18, true) / 2, cy + px(7), 18, Color.WHITE, true);
        }

        // today's numbers for this part
        String[][] cells;
        if (n == null) cells = new String[0][];
        else if (sel == 0) cells = new String[][]{{"Generated today", fmt(n.genToday)}, {"Peak today", peak()}};
        else if (sel == 1) cells = new String[][]{{"Used today", fmt(n.homeToday)}, {"From solar", fmt(Math.max(0, n.homeToday - n.boughtToday - n.dischargedToday))}};
        else if (sel == 2) cells = new String[][]{{"Charged today", fmt(n.chargedToday)}, {"Discharged today", fmt(n.dischargedToday)}};
        else cells = new String[][]{{"Bought today", fmt(n.boughtToday)}, {"Sold today", fmt(n.soldToday)}};
        float cy0 = y + px(112), colW = (rr - l - px(52)) / 2;
        for (int k = 0; k < cells.length; k++) {
            float cx = x + k * colW;
            text(c, cells[k][0], cx, cy0 + px(14), 12, DIM, false);
            text(c, cells[k][1], cx, cy0 + px(40), 22, Color.WHITE, true);
            if (!cells[k][1].endsWith("W")) text(c, " kWh", cx + width(cells[k][1], 22, true), cy0 + px(40), 12, DIM, false);
        }
        drawChart(c, l + px(20), cy0 + px(78), rr - px(20), b - px(18));
    }

    private String peak() {
        double m = 0;
        for (SolisCloud.Point q : day) m = Math.max(m, q.solar);
        return power(m);
    }

    private static String fmt(double kwh) { return String.format(Locale.US, kwh >= 100 ? "%.0f" : "%.1f", kwh); }

    /** Today's curve for the chosen part: solar, home use, battery % (grid: solar and home together). */
    private void drawChart(Canvas c, float l, float t, float rr, float b) {
        String[] title = {"Solar today", "Home use today", "Battery charge today", "Solar and home use today"};
        text(c, title[sel], l + px(6), t + px(4), 13, DIM, true);
        float gt = t + px(16), gb = b - px(18), gl = l + px(6), gr = rr - px(6);
        boolean pct = sel == 2;
        double max = pct ? 100 : 1000;
        if (!pct) for (SolisCloud.Point q : day) max = Math.max(max, sel == 0 ? q.solar : sel == 1 ? q.home : Math.max(q.solar, q.home));
        if (!pct) max = Math.ceil(max / 1000) * 1000;
        p.setStrokeWidth(px(1));
        p.setColor(0x14FFFFFF);
        for (int k = 0; k <= 2; k++) { float yy = gt + (gb - gt) * k / 2f; c.drawLine(gl, yy, gr, yy, p); }
        String ml = pct ? "100%" : power(max);
        text(c, ml, gr - width(ml, 11, false), t + px(4), 11, DIM, false);
        for (int hr = 0; hr <= 24; hr += 6) {
            float xx = gl + (gr - gl) * hr / 24f;
            String s = String.format(Locale.US, "%02d", hr);
            float tw = width(s, 11, false);
            text(c, s, Math.max(gl, Math.min(gr - tw, xx - tw / 2)), b, 11, 0x80FFFFFF, false);
        }
        if (day.size() < 2) return;
        float span = gr - gl;
        int[] series = sel == 3 ? new int[]{0, 1} : new int[]{sel == 2 ? 2 : sel};
        for (int sIdx : series) {
            int color = sIdx == 0 ? SOLAR : sIdx == 1 ? HOME : BATTERY;
            path.reset();
            Path line = new Path();
            boolean first = true;
            float lastX = gl;
            for (SolisCloud.Point q : day) {
                double vv = sIdx == 0 ? q.solar : sIdx == 1 ? q.home : q.soc;
                if (sIdx == 2 && vv < 0) continue;
                float xx = gl + span * q.minute / 1440f, yy = gb - (gb - gt) * (float) Math.min(1, vv / max);
                if (first) { path.moveTo(xx, gb); path.lineTo(xx, yy); line.moveTo(xx, yy); } else { path.lineTo(xx, yy); line.lineTo(xx, yy); }
                first = false;
                lastX = xx;
            }
            if (first) continue;
            path.lineTo(lastX, gb);
            path.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            p.setShader(new LinearGradient(0, gt, 0, gb, (color & 0x00FFFFFF) | 0x80000000, (color & 0x00FFFFFF) | 0x08000000, Shader.TileMode.CLAMP));
            c.drawPath(path, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(px(2));
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(color);
            c.drawPath(line, p);
            p.setStyle(Paint.Style.FILL);
        }
    }
}
