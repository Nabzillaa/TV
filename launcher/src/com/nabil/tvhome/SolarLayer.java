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
class SolarLayer extends android.widget.FrameLayout {
    private static final int SOLAR = 0xFFFFD60A, HOME = 0xFF64D2FF, GRID = 0xFFBF5AF2, BATTERY = 0xFF30D158,
            DIM = 0x99FFFFFF, FAINT = 0x33FFFFFF, RED = 0xFFFF453A, ORANGE = 0xFFFF9F0A, MONEY = 0xFF63E6BE;
    // without an Amber token, money is estimated at typical NSW rates (c/kWh)
    private static final double SELL_EST = 5, BUY_EST = 30;
    private static final int[] COL = {SOLAR, HOME, BATTERY, GRID, MONEY};
    private static final long POLL_MS = 60_000, DAY_MS = 5 * 60_000;

    private final Handler ui;
    private final SolisCloud solis;
    private final AmberClient amber;
    private AmberClient.Money money;
    private String amberError;
    private long amberAt;
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

    private final android.content.SharedPreferences prefs;   // the home screen's weather (Open-Meteo): wx_code, wx_wind, wx_t, …

    SolarLayer(Context c, Handler ui, SolisCloud solis, android.content.SharedPreferences prefs) {
        super(c);
        this.ui = ui;
        this.solis = solis;
        this.prefs = prefs;
        this.amber = new AmberClient(prefs);
        dp = c.getResources().getDisplayMetrics().density;
        setVisibility(GONE);
        setFocusable(true);
        setClickable(true);
        // the header and the details panel are their own view, re-recorded only when the data or the selection changes;
        // the animated house and sky is its own view, redrawn ~30 times a second with corners rounded by the GPU
        back = new View(c) {
            @Override protected void onDraw(Canvas cv) { drawBack(cv); }
        };
        addView(back, new LayoutParams(-1, -1));
        scene = new View(c) {
            @Override protected void onDraw(Canvas cv) {
                if (getWidth() < 50 || getHeight() < 50) return;      // not laid out yet
                cv.translate(-getLeft(), -getTop());
                // the animation clock wraps hourly: uptime in ms (days of it) is too big for float positions
                drawScene(cv, getLeft(), getTop(), getRight(), getBottom(), SystemClock.uptimeMillis() % 3_600_000L);
                if (isOpen()) postInvalidateDelayed(33);
            }
        };
        scene.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(22)); }
        });
        scene.setClipToOutline(true);
        addView(scene, new LayoutParams(-1, -1));
    }

    private final View scene, back;

    /** The scene sits in the left 60%, under the header. */
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        int w = r - l, h = b - t;
        scene.layout(Math.round(px(24)), Math.round(px(92)), Math.round(w * 0.60f), Math.round(h - px(16)));
    }

    /** New data or a new selection: both the panel and the scene change. */
    private void redraw() {
        back.invalidate();
        scene.invalidate();
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
                AmberClient.Money mo = null;
                String aerr = null;
                if (amber.configured() && (wantDay || SystemClock.uptimeMillis() - amberAt > 5 * 60_000)) {
                    try { mo = amber.fetch(); } catch (Exception e) { aerr = e.getMessage(); Log.w("Amber", "poll: " + aerr); }
                }
                final AmberClient.Money fmo = mo;
                final String faerr = aerr;
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
                    if (fmo != null) { money = fmo; amberAt = SystemClock.uptimeMillis(); amberError = null; }
                    else if (faerr != null) amberError = faerr;
                    redraw();
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
            redraw();
            return true;
        }
        if (code == android.view.KeyEvent.KEYCODE_DPAD_UP && (sel < 2 || sel == 4)) { onRefresh = true; redraw(); return true; }
        int was = sel;
        // top row: PV, Money, Load; bottom row: Battery, Grid
        //                 PV  Load Battery Grid Money
        int[] left  =     {0,  4,   2,      2,   0};
        int[] right =     {4,  1,   3,      3,   1};
        int[] up    =     {0,  1,   0,      1,   4};
        int[] down  =     {2,  3,   2,      3,   3};
        switch (code) {
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT: sel = left[sel]; break;
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: sel = right[sel]; break;
            case android.view.KeyEvent.KEYCODE_DPAD_UP: sel = up[sel]; break;
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN: sel = down[sel]; break;
            case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
            case android.view.KeyEvent.KEYCODE_ENTER: sel = new int[]{4, 2, 3, 0, 1}[sel]; break;   // PV, Money, Load, Battery, Grid
            default: return super.onKeyDown(code, e);
        }
        if (sel != was) selAt = SystemClock.uptimeMillis();
        redraw();
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

    private void drawBack(Canvas c) {
        int w = getWidth(), h = getHeight();
        long t = SystemClock.uptimeMillis();
        p.setColor(Color.WHITE);
        p.setShader(new LinearGradient(0, 0, 0, h, 0xFF15171C, 0xFF050506, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);

        drawHeader(c, w);
        float k = Math.min(1, (t - selAt) / 260f), e = 1 - (1 - k) * (1 - k) * (1 - k);     // ease out
        RectF pr = new RectF(w * 0.61f, px(104), w - px(40), h - px(36));
        int save = k < 1 ? c.saveLayerAlpha(pr.left - px(40), pr.top - px(4), pr.right + px(40), pr.bottom + px(4), (int) (255 * (0.25f + 0.75f * e)))
                : c.save();
        c.translate((1 - e) * px(28), 0);
        if (sel == 4) drawMoneyPanel(c, pr.left, pr.top, pr.right, pr.bottom); else drawPanel(c, pr.left, pr.top, pr.right, pr.bottom);
        c.restoreToCount(save);
        if (k < 1) back.postInvalidateOnAnimation();
        if (isOpen() && (spinAt > 0 || (doneAt > 0 && t - doneAt < 2600))) back.postInvalidateOnAnimation();   // the Refresh spinner
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
        if (prefs.contains("wx_time")) {
            String city = prefs.getString("wx_city", "");
            status += "   ·   " + (city.isEmpty() ? "" : city + " ") + Math.round(prefs.getFloat("wx_t", 0)) + "° " + sky(prefs.getInt("wx_code", 1));
        }
        if (status.length() > 130) status = status.substring(0, 130) + "…";
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

    // ------------------------------------------------------------------ outside: the sun and the weather

    /** Light and weather for this frame: worked out once per frame, used by the sky, the house and the rain. */
    private static final class Outside {
        double alt, rise, set, hour;    // sun altitude (degrees); sunrise, sunset and now as hours of the local clock
        float day;                      // 0 night .. 1 full daylight
        float dusk;                     // 0 .. 1 near sunrise / sunset
        float frac;                     // 0 at sunrise .. 1 at sunset (where the sun is across the sky)
        float clouds;                   // 0 clear .. 1 overcast
        int rain;                       // 0 none, 1 drizzle, 2 rain, 3 storm
        boolean snow, fog;
        float wind;                     // km/h
        int code;
    }

    private Outside outside() {
        Outside o = new Outside();
        double lat = prefs.getFloat("wx_lat", -33.9f), lon = prefs.getFloat("wx_lon", 151.2f);
        java.util.Calendar c = java.util.Calendar.getInstance();
        int n = c.get(java.util.Calendar.DAY_OF_YEAR);
        double tz = c.getTimeZone().getOffset(c.getTimeInMillis()) / 3_600_000.0;
        o.hour = c.get(java.util.Calendar.HOUR_OF_DAY) + c.get(java.util.Calendar.MINUTE) / 60.0 + c.get(java.util.Calendar.SECOND) / 3600.0;
        String[] preview = prefs.getString("sc_sky", "").split(",");                  // --es solar_sky "code,hour" (preview)
        if (preview.length == 2) try { o.hour = Double.parseDouble(preview[1]); } catch (NumberFormatException ignored) { }
        double decl = Math.toRadians(23.44 * Math.sin(2 * Math.PI * (284 + n) / 365.0));
        double b = 2 * Math.PI * (n - 81) / 364.0;
        double eot = 9.87 * Math.sin(2 * b) - 7.53 * Math.cos(b) - 1.5 * Math.sin(b);         // minutes
        double noon = 12 - (lon - tz * 15) / 15 - eot / 60, la = Math.toRadians(lat);
        double cosH = (Math.sin(Math.toRadians(-0.83)) - Math.sin(la) * Math.sin(decl)) / (Math.cos(la) * Math.cos(decl));
        double hh = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, cosH)))) / 15;
        o.rise = noon - hh;
        o.set = noon + hh;
        o.alt = Math.toDegrees(Math.asin(Math.sin(la) * Math.sin(decl) + Math.cos(la) * Math.cos(decl) * Math.cos(Math.toRadians(15 * (o.hour - noon)))));
        o.day = smooth(-6, 8, (float) o.alt);
        o.dusk = (float) Math.exp(-Math.pow(o.alt / 6.5, 2));
        o.frac = (float) Math.max(0, Math.min(1, (o.hour - o.rise) / (o.set - o.rise)));
        int k = o.code = prefs.getInt("wx_code", 1);
        if (preview.length == 2) try { k = o.code = Integer.parseInt(preview[0]); } catch (NumberFormatException ignored) { }
        o.clouds = k == 0 ? 0 : k == 1 ? 0.2f : k == 2 ? 0.45f : k == 3 ? 0.9f : k == 45 || k == 48 ? 0.7f : 0.85f;
        o.rain = k >= 95 ? 3 : (k >= 61 && k <= 67) || (k >= 80 && k <= 82) ? 2 : k >= 51 && k <= 57 ? 1 : 0;
        o.snow = (k >= 71 && k <= 77) || k == 85 || k == 86;
        o.fog = k == 45 || k == 48;
        o.wind = prefs.getFloat("wx_wind", 8);
        return o;
    }

    private static float smooth(float a, float b, float x) {
        float t = Math.max(0, Math.min(1, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    private static int mix(int a, int b, float f) {
        f = Math.max(0, Math.min(1, f));
        int ra = (a >> 16) & 0xFF, ga = (a >> 8) & 0xFF, ba = a & 0xFF, aa = a >>> 24;
        int rb = (b >> 16) & 0xFF, gb = (b >> 8) & 0xFF, bb = b & 0xFF, ab = b >>> 24;
        return (int) (aa + (ab - aa) * f) << 24 | (int) (ra + (rb - ra) * f) << 16 | (int) (ga + (gb - ga) * f) << 8 | (int) (ba + (bb - ba) * f);
    }

    /** A wall or roof colour under this light: f is how much of the light reaches the face (0..1). */
    private int lit(int color, float f, Outside o) {
        float l = 0.26f + 0.74f * o.day * (1 - 0.28f * o.clouds);
        int c = mix(0xFF000000, color, Math.min(1.08f, l * f));
        c = mix(c, 0xFF1A2440, (1 - o.day) * 0.35f);                              // moonlight blue at night
        return mix(c, 0xFFFF9A55, o.dusk * o.day * 0.18f * (1 - o.clouds));      // warm low sun
    }

    /** Pseudo-random 0..1, stable per index (stars, clouds, raindrops). */
    private static float rnd(int i) {
        int x = i * 0x9E3779B9;                                                   // murmur3 finaliser
        x ^= x >>> 16; x *= 0x85EBCA6B; x ^= x >>> 13; x *= 0xC2B2AE35; x ^= x >>> 16;
        return (x & 0xFFFFFF) / (float) 0xFFFFFF;
    }

    private void drawSky(Canvas c, float l, float t, float rr, float b, Outside o, long time) {
        float grey = o.clouds * 0.75f;
        int top = mix(mix(0xFF03060F, 0xFF2D78D2, o.day), mix(0xFF0A0C10, 0xFF6B7380, o.day), grey);
        int bottom = mix(mix(0xFF16233F, 0xFFA8D4F5, o.day), mix(0xFF1F232B, 0xFFB4BAC2, o.day), grey);
        float duskShow = o.dusk * (1 - o.clouds * 0.6f);
        top = mix(top, 0xFF3B3F78, duskShow * 0.45f);
        bottom = mix(bottom, 0xFFF39A5B, duskShow * 0.75f);
        if (o.rain >= 2) { top = mix(top, 0xFF000000, 0.25f); bottom = mix(bottom, 0xFF000000, 0.2f); }
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        p.setShader(new LinearGradient(0, t, 0, b, top, bottom, Shader.TileMode.CLAMP));
        c.drawRect(l, t, rr, b, p);
        p.setShader(null);
        float w = rr - l, horizon = t + (b - t) * 0.42f, arcH = (b - t) * 0.34f;

        // stars and the moon at night
        float night = (1 - o.day) * (1 - o.clouds * 0.9f);
        if (night > 0.02f) {
            for (int i = 0; i < 90; i++) {
                float x = l + rnd(i) * w, y = t + rnd(i + 500) * (horizon - t) * 0.9f;
                float tw = 0.55f + 0.45f * (float) Math.sin(i * 1.7);
                p.setColor(Color.WHITE);
                p.setAlpha((int) (200 * night * tw * (0.4f + 0.6f * rnd(i + 900))));
                c.drawCircle(x, y, px(0.6f + 1.1f * rnd(i + 1300)), p);
            }
            p.setAlpha(255);
            double nightLen = 24 - (o.set - o.rise), nf = ((o.hour - o.set + 24) % 24) / nightLen;
            if (nf >= 0 && nf <= 1) {
                float mx = l + w * (0.1f + 0.8f * (float) nf), my = horizon - arcH * (float) Math.sin(Math.PI * nf) * 0.9f, mr = px(15);
                p.setShader(new RadialGradient(mx, my, mr * 4, ((int) (70 * night) << 24) | 0xDDE6FF, 0x00DDE6FF, Shader.TileMode.CLAMP));
                c.drawCircle(mx, my, mr * 4, p);
                p.setShader(null);
                p.setColor(mix(0xFFFFFFFF, 0xFFF4F1E1, 0.6f));
                p.setAlpha((int) (255 * Math.min(1, night * 1.3f)));
                c.drawCircle(mx, my, mr, p);
                p.setColor(top);                                                    // crescent: cut the disc with the sky
                c.drawCircle(mx + mr * 0.45f, my - mr * 0.25f, mr * 0.88f, p);
                p.setAlpha(255);
            }
        }
        // the sun, along its arc from sunrise (left) to sunset (right)
        if (o.alt > -4) {
            float sx = l + w * (0.1f + 0.8f * o.frac), sy = horizon - arcH * (float) Math.sin(Math.PI * o.frac), sr = px(20);
            float vis = (1 - o.clouds * 0.85f) * smooth(-4, 2, (float) o.alt);
            int core = mix(0xFFFFF4C2, 0xFFFFB25C, o.dusk);
            p.setShader(new RadialGradient(sx, sy, sr * 6, ((int) (110 * vis) << 24) | (core & 0xFFFFFF), core & 0xFFFFFF, Shader.TileMode.CLAMP));
            c.drawCircle(sx, sy, sr * 6, p);
            p.setShader(null);
            p.setColor(core);
            p.setAlpha((int) (255 * vis));
            c.drawCircle(sx, sy, sr, p);
            p.setAlpha(255);
        }
    }

    private void drawClouds(Canvas c, float l, float t, float rr, float b, Outside o, long time) {
        float w = rr - l, horizon = t + (b - t) * 0.42f;
        if (o.clouds > 0.1f) {
            int count = Math.round(2 + o.clouds * 7);
            int cc = o.rain >= 2 ? mix(0xFF4D535C, 0xFF7A808A, o.day) : mix(0xFF2A2F3A, o.clouds > 0.7f ? 0xFFC5CAD1 : 0xFFFFFFFF, o.day);
            cc = mix(cc, 0xFFFFB27A, o.dusk * o.day * 0.35f);
            float speed = px(4 + o.wind * 0.9f);
            for (int i = 0; i < count; i++) {
                float s = (0.7f + 0.7f * rnd(i + 40)) * px(1);
                float cw = 150 * s, span = w + cw * 2;
                float x = l - cw + ((rnd(i + 10) * span + time / 1000f * speed * (0.6f + 0.4f * rnd(i + 20))) % span);
                float y = t + (horizon - t) * (0.06f + 0.3f * rnd(i + 30));
                cloud(c, x, y, s, cc, 0.55f + 0.4f * o.clouds);
            }
        }
    }

    /** A soft cloud of overlapping puffs, darker underneath: drawn once into a sprite per colour, then just placed. */
    private void cloud(Canvas c, float x, float y, float s, int color, float alpha) {
        int key = mix(color, 0, 0) ^ (Math.round(alpha * 20) << 24);
        if (cloudSprite == null || cloudKey != key) {
            float k = px(1);
            int bw = Math.round(200 * k), bh = Math.round(90 * k);
            if (cloudSprite == null) cloudSprite = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888);
            cloudSprite.eraseColor(0);
            Canvas cc = new Canvas(cloudSprite);
            Paint q = new Paint(Paint.ANTI_ALIAS_FLAG);
            float ox0 = 60 * k, oy0 = 50 * k;
            q.setColor(mix(color, 0xFF000000, 0.18f));
            cc.drawRoundRect(new RectF(ox0 - 50 * k, oy0 + 4 * k, ox0 + 122 * k, oy0 + 34 * k), 16 * k, 16 * k, q);
            q.setColor(color);
            float[][] puffs = {{0, 0, 30}, {32, -14, 36}, {70, -4, 30}, {100, 6, 24}, {-30, 8, 22}, {50, 10, 30}};
            for (float[] f : puffs) cc.drawCircle(ox0 + f[0] * k, oy0 + f[1] * k, f[2] * k, q);
            cloudKey = key;
            cloudPaint.setAlpha(Math.round(255 * alpha));
        }
        float k = px(1);
        RectF dst = new RectF(x - 60 * k * s / k, y - 50 * k * s / k, x - 60 * k * s / k + cloudSprite.getWidth() * s / k, y - 50 * k * s / k + cloudSprite.getHeight() * s / k);
        c.drawBitmap(cloudSprite, null, dst, cloudPaint);
    }

    private android.graphics.Bitmap cloudSprite;
    private int cloudKey;
    private final Paint cloudPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    /** Rain, snow, fog and lightning, in front of the house. */
    private void drawWeather(Canvas c, float l, float t, float rr, float b, Outside o, long time) {
        float w = rr - l, h = b - t, slant = Math.min(0.6f, o.wind / 60f);
        p.setStrokeCap(Paint.Cap.ROUND);
        if (o.rain > 0) {
            int drops = o.rain == 1 ? 60 : o.rain == 2 ? 120 : 150;
            float len = px(o.rain == 1 ? 8 : 16), speed = px(o.rain == 1 ? 420 : 760);
            float[] seg = new float[drops * 4];                                     // all drops in one draw call
            for (int i = 0; i < drops; i++) {
                float sp = speed * (0.75f + 0.5f * rnd(i + 3000));
                float y = t + ((rnd(i + 2000) * h + time / 1000f * sp) % (h + len));
                float x = l + ((rnd(i + 1000) * (w + h * slant) - (y - t) * slant) % (w + h * slant));
                seg[i * 4] = x; seg[i * 4 + 1] = y; seg[i * 4 + 2] = x + len * slant; seg[i * 4 + 3] = y - len;
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(px(o.rain == 1 ? 1 : 1.4f));
            p.setColor(o.day > 0.3f ? 0x99D6E4F2 : 0x8899AACC);
            p.setStrokeCap(Paint.Cap.BUTT);                                       // round caps doubled each drop here
            c.drawLines(seg, p);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStyle(Paint.Style.FILL);
        }
        if (o.snow) {
            p.setColor(0xDDFFFFFF);
            for (int i = 0; i < 140; i++) {
                float sp = px(40 + 40 * rnd(i + 3100));
                float y = t + ((rnd(i + 2100) * h + time / 1000f * sp) % h);
                float x = l + ((rnd(i + 1100) * w + (float) Math.sin(time / 900.0 + i) * px(10) + time / 1000f * px(o.wind * 0.4f)) % w + w) % w;
                c.drawCircle(x, y, px(1.2f + 1.6f * rnd(i + 4100)), p);
            }
        }
        if (o.fog) {
            for (int i = 0; i < 4; i++) {
                float y = t + h * (0.35f + 0.16f * i), x = l + ((time / 1000f * px(6 + 3 * i) + rnd(i + 70) * w) % w) - w / 2;
                p.setShader(new RadialGradient(x + w / 2, y, w * 0.6f, mix(0x00D8DCE0, 0x55D8DCE0, o.day * 0.6f + 0.4f), 0x00D8DCE0, Shader.TileMode.CLAMP));
                c.drawRect(l, y - px(60), rr, y + px(60), p);
                p.setShader(null);
            }
        }
        if (o.rain == 3) {                                                        // lightning every few seconds
            long cycle = time / 6500, at = time % 6500;
            if (rnd((int) cycle) > 0.35f && at < 380) {
                float flash = at < 90 ? 1 : at < 160 ? 0.3f : at < 230 ? 0.8f : (380 - at) / 150f * 0.5f;
                p.setColor(Color.WHITE);
                p.setAlpha((int) (110 * flash));
                c.drawRect(l, t, rr, b, p);
                if (at < 230) {
                    float x = l + w * (0.2f + 0.6f * rnd((int) cycle + 77)), y = t;
                    path.reset();
                    path.moveTo(x, y);
                    for (int k = 1; k <= 7; k++) { x += px(-14 + 28 * rnd((int) cycle * 13 + k)); y += h * 0.07f; path.lineTo(x, y); }
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(px(2.2f));
                    p.setAlpha((int) (255 * flash));
                    c.drawPath(path, p);
                    p.setStyle(Paint.Style.FILL);
                }
                p.setAlpha(255);
            }
        }
    }

    private static String sky(int k) {
        if (k == 0) return "Clear";
        if (k == 1) return "Mostly clear";
        if (k == 2) return "Partly cloudy";
        if (k == 3) return "Overcast";
        if (k == 45 || k == 48) return "Fog";
        if (k >= 51 && k <= 57) return "Drizzle";
        if (k >= 61 && k <= 67) return "Rain";
        if (k >= 71 && k <= 77) return "Snow";
        if (k >= 80 && k <= 82) return "Showers";
        if (k == 85 || k == 86) return "Snow showers";
        if (k >= 95) return "Thunderstorm";
        return "Cloudy";
    }

    // ------------------------------------------------------------------ the house (isometric, drawn once per light change)

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

    // anchors (world) the callouts point at: PV panel centre, a window (load), battery, meter
    private static final float[][] TARGET = {{5f, 4.7f, 5.4f}, {10f, 3f, 4.85f}, {2.2f, 7.2f, 1.5f}, {8.0f, 6.15f, 1.6f}};

    private void drawScene(Canvas c, float l, float t, float rr, float b, long time) {
        Outside o = outside();
        // fit the scene (x-y from -7 to 17.5, height from -5.5 to 13) into the box
        float wU = 24.5f * C30, hU = 18.5f;
        u = Math.min((rr - l) / wU, (b - t) / hU) * 0.80f;
        ox = (l + rr) / 2 - ((-7f + 17.5f) / 2 * C30) * u;
        oy = (t + b) / 2 - ((-5.5f + 13f) / 2) * u + px(44);

        RectF card = new RectF(l, t, rr, b);
        SolisCloud.Reading n = now;
        int bars = n == null || n.soc < 0 ? 0 : (int) Math.round(n.soc / 20);
        float sun = n == null ? 0 : (float) Math.min(1, n.solar / 4000);
        int sceneW = Math.round(rr - l), sceneH = Math.round(b - t);
        // one opaque image of the sky, sun or moon, stars, house and leader lines: redrawn only when the light, the
        // sun's place, the data or the selection change, so each frame blends just the moving parts over it
        double nightLen = 24 - (o.set - o.rise), nf = ((o.hour - o.set + 24) % 24) / nightLen;
        String key = sceneW + "x" + sceneH + "/" + Math.round(o.day * 24) + "/" + Math.round(o.frac * 150) + "/" + Math.round(nf * 150)
                + "/" + Math.round(o.dusk * 8) + "/" + Math.round(o.clouds * 10) + "/" + o.rain + "/" + o.snow + "/" + bars + "/" + Math.round(sun * 8)
                + "/" + sel + "/" + onRefresh;
        if (base == null || !key.equals(baseKey)) {
            if (base == null || base.getWidth() != sceneW || base.getHeight() != sceneH) {
                if (base != null) base.recycle();
                base = android.graphics.Bitmap.createBitmap(Math.max(1, sceneW), Math.max(1, sceneH), android.graphics.Bitmap.Config.ARGB_8888);
            }
            Canvas bc = new Canvas(base);
            bc.translate(-l, -t);                                // drawn in screen coordinates
            drawSky(bc, l, t, rr, b, o, 0);
            drawHouse(bc, o, bars, sun);
            drawLeaders(bc, l, t, rr, b);
            base.setHasAlpha(false);
            baseKey = key;
        }
        c.drawBitmap(base, l, t, null);
        drawClouds(c, l, t, rr, b, o, time);

        // cables, with dots running the way the power flows
        float[][] pvWire = {{5f, 6.5f, 3.6f}, {5f, 6.15f, 3.2f}, {5f, 6.15f, 2.8f}};
        float[][] batWire = {{2.9f, 6.6f, 1.4f}, {4.3f, 6.6f, 1.4f}, {4.3f, 6.3f, 1.5f}};
        float[][] gridWire = {{5.7f, 6.15f, 1.9f}, {7.6f, 6.15f, 1.7f}};
        float[][] loadWire = {{5.0f, 6.15f, 1.2f}, {5.0f, 6.15f, 0.2f}};
        float[][] street = SERVICE_LINE;
        double pv = n == null ? 0 : n.solar, home = n == null ? 0 : n.home, bat = n == null ? 0 : n.battery, grid = n == null ? 0 : n.grid;
        wire(c, pvWire, pv, true, SOLAR, time);
        wire(c, batWire, Math.abs(bat), bat < 0, BATTERY, time);           // discharging: battery -> inverter
        wire(c, gridWire, Math.abs(grid), grid < 0, GRID, time);           // selling: inverter -> meter
        wire(c, loadWire, home, true, HOME, time);
        wire(c, street, Math.abs(grid), grid < 0, GRID, time);

        drawWeather(c, l, t, rr, b, o, time);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(px(1));
        p.setColor(0x1FFFFFFF);
        c.drawRoundRect(card, px(22), px(22), p);
        p.setStyle(Paint.Style.FILL);

        String[] name = {"PV", "Load", "Battery" + (n != null && n.soc >= 0 ? String.format(Locale.US, "  %.0f%%", n.soc) : ""), "Grid",
                money != null ? "Earned today" : "Earned today (est.)"};
        drawPlates(c, l, t, rr, b, n, name, new double[]{pv, home, bat, grid, 0});
        if (!onRefresh && sel < 4) {                                         // the chosen part pulses
            int[] col = COL;
            float pulse = (float) (0.5 + 0.5 * Math.sin((time - selAt) / 260.0));
            float tx = sx(TARGET[sel][0], TARGET[sel][1]), ty = sy(TARGET[sel][0], TARGET[sel][1], TARGET[sel][2]);
            p.setStyle(Paint.Style.FILL);
            p.setColor((col[sel] & 0x00FFFFFF) | ((int) (0x40 + 0x50 * pulse) << 24));
            c.drawCircle(tx, ty, px(9) + px(5) * pulse, p);
            p.setColor(col[sel]);
            c.drawCircle(tx, ty, px(4), p);
        }
    }

    private android.graphics.Bitmap base;
    private String baseKey;

    private float[][] labelAt(float l, float t, float rr, float b) {
        return new float[][]{{l + px(28), t + px(14)}, {l + (rr - l) * 0.64f, t + px(14)}, {l + px(28), b - px(92)}, {l + (rr - l) * 0.70f, b - px(92)},
                {l + (rr - l) * 0.33f, t + px(14)}};
    }

    /** The dashed leader lines from each label to its part (baked into the base image). */
    private void drawLeaders(Canvas c, float l, float t, float rr, float b) {
        float[][] label = labelAt(l, t, rr, b);
        int[] col = {SOLAR, HOME, BATTERY, GRID};
        for (int i = 0; i < 4; i++) {
            boolean on = i == sel && !onRefresh;
            float lx = label[i][0], ly = label[i][1];
            float tx = sx(TARGET[i][0], TARGET[i][1]), ty = sy(TARGET[i][0], TARGET[i][1], TARGET[i][2]);
            boolean up = i >= 2;                          // bottom labels lead upwards
            float lineTop = up ? ty : ly + px(4), lineBot = up ? ly + px(52) : ty;
            float hy = up ? lineTop : lineBot;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(px(on ? 1.6f : 1.1f));
            p.setColor(on ? col[i] : 0xB3FFFFFF);
            p.setPathEffect(new DashPathEffect(new float[]{px(3), px(3)}, 0));
            path.reset();
            path.moveTo(lx - px(10), up ? lineBot : lineTop);
            path.lineTo(lx - px(10), hy);
            path.lineTo(tx, hy);
            path.lineTo(tx, ty);
            c.drawPath(path, p);
            p.setPathEffect(null);
            p.setStyle(Paint.Style.FILL);
            if (!on) {
                p.setColor(0xE6FFFFFF);
                c.drawCircle(tx, ty, px(2.5f), p);
            }
        }
    }

    /** The label plates (name, power, status), drawn every frame so clouds and rain pass behind them. */
    private void drawPlates(Canvas c, float l, float t, float rr, float b, SolisCloud.Reading n, String[] name, double[] val) {
        float[][] label = labelAt(l, t, rr, b);
        double[] cash = todayMoney();
        float pop = Math.min(1, (SystemClock.uptimeMillis() - selAt) / 220f);
        pop = 1 + 0.07f * (float) Math.sin(Math.PI * pop) + 0.03f * pop;                    // a small bounce, settling a bit larger
        for (int i = 0; i < 5; i++) {
            boolean on = i == sel && !onRefresh;
            float lx = label[i][0], ly = label[i][1];
            int col = COL[i];
            String pw = i == 4 ? (cash == null ? "—" : String.format(Locale.US, "$%.2f", cash[0])) : n == null ? "—" : power(val[i]);
            String st = i == 4 ? (cash == null ? "" : money != null && !Double.isNaN(money.sellNow)
                    ? String.format(Locale.US, "Sell %.1fc · buy %.0fc", money.sellNow, money.buyNow) : String.format(Locale.US, "at ~%.0fc/kWh", SELL_EST))
                    : status(i, n);
            c.save();
            if (on) c.scale(pop, pop, lx, ly + px(26));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(px(on ? 3 : 1.6f));
            p.setColor(on ? col : 0xB3FFFFFF);
            c.drawLine(lx - px(10), ly, lx - px(10), ly + px(48), p);
            p.setStyle(Paint.Style.FILL);
            float vw = Math.max(width(name[i], 13, false), width(pw, 24, true)) + px(14);
            p.setColor(on ? 0xD90B0C0F : 0xB30B0C0F);
            RectF plate = new RectF(lx - px(6), ly - px(2), lx + vw, ly + px(52));
            c.drawRoundRect(plate, px(8), px(8), p);
            if (on) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(px(1.2f));
                p.setColor((col & 0x00FFFFFF) | 0x99000000);
                c.drawRoundRect(plate, px(8), px(8), p);
                p.setStyle(Paint.Style.FILL);
            }
            text(c, name[i], lx, ly + px(15), 13, on ? col : 0xCCFFFFFF, on);
            text(c, pw, lx, ly + px(44), 24, i == 4 && cash != null ? MONEY : Color.WHITE, true);
            if (!st.isEmpty()) {
                float sw = width(st, 11, true) + px(14), sy0 = ly + px(58);
                p.setColor(0xB30B0C0F);
                c.drawRoundRect(new RectF(lx - px(2), sy0, lx - px(2) + sw, sy0 + px(19)), px(5), px(5), p);
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(px(1));
                p.setColor(col);
                c.drawRoundRect(new RectF(lx - px(2), sy0, lx - px(2) + sw, sy0 + px(19)), px(5), px(5), p);
                p.setStyle(Paint.Style.FILL);
                text(c, st, lx + px(5), sy0 + px(14), 11, col, true);
            }
            c.restore();
        }
    }

    // ------------------------------------------------------------------ money (Amber Electric)

    /** Today's ≈ {$ earned selling, $ spent buying, c/kWh selling, c/kWh buying}: SolisCloud's kWh at Amber's prices,
     *  each half hour weighted by how much was generated (selling) or used (buying) then; typical rates without Amber. */
    private double[] todayMoney() {
        SolisCloud.Reading n = now;
        if (n == null || n.demo) return null;
        double sell = SELL_EST, buy = BUY_EST;
        AmberClient.Money m = money;
        if (m != null && !m.today.isEmpty()) {
            double ws = 0, s = 0, wb = 0, b = 0, ss = 0, sb = 0;
            int now = nowMinute();
            for (AmberClient.Price pr : m.today) {
                if (pr.minute > now) continue;
                double solar = 0, home = 0;
                int cnt = 0;
                for (SolisCloud.Point q : day) if (q.minute >= pr.minute && q.minute < pr.minute + 30) { solar += q.solar; home += q.home; cnt++; }
                if (cnt > 0) { solar /= cnt; home /= cnt; }
                ws += solar; s += solar * pr.sell; wb += home; b += home * pr.buy;
                ss += pr.sell; sb += pr.buy;
            }
            int k = 0;
            for (AmberClient.Price pr : m.today) if (pr.minute <= now) k++;
            if (k > 0) {
                sell = ws > 0 ? s / ws : ss / k;
                buy = wb > 0 ? b / wb : sb / k;
            }
        }
        return new double[]{n.soldToday * sell / 100, n.boughtToday * buy / 100, sell, buy};
    }

    private static int nowMinute() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE);
    }

    private static String dollars(double v) { return (v < 0 ? "−$" : "$") + String.format(Locale.US, "%.2f", Math.abs(v)); }

    /** Amber's price level as a colour: green when cheap, red at a spike. */
    private static int levelColor(String level) {
        switch (level == null ? "" : level) {
            case "extremelyLow": case "veryLow": case "low": return BATTERY;
            case "neutral": return 0xFFE5E5EA;
            case "high": return ORANGE;
            case "spike": return RED;
            default: return 0xFFE5E5EA;
        }
    }

    private void drawMoneyPanel(Canvas c, float l, float t, float rr, float b) {
        r.set(l, t, rr, b);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x14FFFFFF);
        c.drawRoundRect(r, px(22), px(22), p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(px(1));
        p.setColor((MONEY & 0x00FFFFFF) | 0x66000000);
        c.drawRoundRect(r, px(22), px(22), p);
        p.setStyle(Paint.Style.FILL);
        AmberClient.Money m = money;
        float x = l + px(26), y = t + px(40), colW = (rr - l - px(52)) / 2;
        p.setColor(MONEY);
        c.drawCircle(x + px(5), y - px(7), px(5), p);
        text(c, m != null ? "Money  ·  Amber Electric" : "Money  ·  estimate", x + px(18), y, 18, Color.WHITE, true);

        double[] cash = todayMoney();
        SolisCloud.Reading n = now;
        if (cash == null) { text(c, n != null && n.demo ? "Not shown with demo data" : "Waiting for SolisCloud…", x, y + px(50), 15, DIM, false); return; }
        text(c, "≈ " + dollars(cash[0]), x, y + px(58), 42, MONEY, true);
        text(c, String.format(Locale.US, "earned today  ·  %s kWh sent at ~%.1fc", fmt(n.soldToday), cash[2]), x, y + px(84), 14, DIM, false);

        // live prices, coloured by Amber's level
        float cy = y + px(108);
        if (m != null && !Double.isNaN(m.buyNow)) {
            String a = String.format(Locale.US, "Buy now %.1fc", m.buyNow), s2 = String.format(Locale.US, "Sell now %.1fc", m.sellNow);
            chip(c, a, x, cy, levelColor(m.levelNow));
            chip(c, s2, x + width(a, 13, true) + px(34), cy, MONEY);
        } else {
            text(c, amberError != null ? "Amber: " + amberError : "Estimated at 5c sell, 30c buy  ·  add Amber for real prices", x, cy + px(14), 12, amberError != null ? RED : DIM, false);
        }

        String[][] cells = {
                {"Spent today ≈", dollars(cash[1])}, {"Net today ≈", dollars(cash[0] - cash[1])},
                {"This month earned", m != null && !Double.isNaN(m.monthEarned) ? dollars(m.monthEarned) : "—"},
                {"This month spent", m != null && !Double.isNaN(m.monthSpent) ? dollars(m.monthSpent) : "—"}};
        for (int k = 0; k < 4; k++) {
            float cx = x + (k % 2) * colW, ry = cy + px(36) + (k / 2) * px(50);
            text(c, cells[k][0], cx, ry + px(12), 12, DIM, false);
            int vc = k == 1 ? (cash[0] - cash[1] >= 0 ? MONEY : ORANGE) : k == 2 ? MONEY : Color.WHITE;
            text(c, cells[k][1], cx, ry + px(36), 21, vc, true);
        }
        if (m != null && !m.monthTo.isEmpty()) text(c, "Month from Amber's billing, to " + m.monthTo, x, cy + px(150), 11, 0x80FFFFFF, false);
        drawPriceChart(c, l + px(20), cy + px(166), rr - px(20), b - px(18), m);
    }

    private void chip(Canvas c, String s, float x, float y, int color) {
        float w = width(s, 13, true) + px(20);
        p.setStyle(Paint.Style.FILL);
        p.setColor((color & 0x00FFFFFF) | 0x26000000);
        c.drawRoundRect(new RectF(x, y, x + w, y + px(24)), px(12), px(12), p);
        text(c, s, x + px(10), y + px(17), 13, color, true);
    }

    /** Today's half-hourly Amber prices: buying (orange) and selling (green), c/kWh, with now marked. */
    private void drawPriceChart(Canvas c, float l, float t, float rr, float b, AmberClient.Money m) {
        text(c, "Amber prices today (c/kWh)", l + px(6), t + px(4), 13, DIM, true);
        float gt = t + px(16), gb = b - px(18), gl = l + px(6), gr = rr - px(6);
        if (gb - gt < px(30)) return;
        for (int hr = 0; hr <= 24; hr += 6) {
            float xx = gl + (gr - gl) * hr / 24f;
            String s = String.format(Locale.US, "%02d", hr);
            float tw = width(s, 11, false);
            text(c, s, Math.max(gl, Math.min(gr - tw, xx - tw / 2)), b, 11, 0x80FFFFFF, false);
        }
        p.setStrokeWidth(px(1));
        p.setColor(0x14FFFFFF);
        c.drawLine(gl, gb, gr, gb, p);
        if (m == null || m.today.size() < 2) {
            String s = "Add Amber to see today's prices";
            text(c, s, (gl + gr - width(s, 12, false)) / 2, (gt + gb) / 2, 12, DIM, false);
            return;
        }
        double lo = 0, hi = 10;
        for (AmberClient.Price q : m.today) { hi = Math.max(hi, Math.max(q.buy, q.sell)); lo = Math.min(lo, Math.min(q.buy, q.sell)); }
        hi = Math.ceil(hi / 10) * 10;
        float span = gr - gl;
        if (lo < 0) {                                                       // zero line when prices go negative
            float zy = gb - (gb - gt) * (float) ((0 - lo) / (hi - lo));
            c.drawLine(gl, zy, gr, zy, p);
        }
        text(c, String.format(Locale.US, "%.0fc", hi), gr - width(String.format(Locale.US, "%.0fc", hi), 11, false), t + px(4), 11, DIM, false);
        for (int sIdx = 0; sIdx < 2; sIdx++) {
            Path line = new Path();
            boolean first = true;
            for (AmberClient.Price q : m.today) {
                double v = sIdx == 0 ? q.buy : q.sell;
                float x0 = gl + span * q.minute / 1440f, x1 = gl + span * (q.minute + 30) / 1440f, yy = gb - (gb - gt) * (float) ((v - lo) / (hi - lo));
                if (first) line.moveTo(x0, yy); else line.lineTo(x0, yy);
                line.lineTo(x1, yy);                                       // a step per half hour
                first = false;
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(px(2));
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setColor(sIdx == 0 ? ORANGE : MONEY);
            c.drawPath(line, p);
        }
        p.setStyle(Paint.Style.FILL);
        float xn = gl + span * nowMinute() / 1440f;
        p.setColor(0x66FFFFFF);
        c.drawRect(xn - px(0.5f), gt, xn + px(0.5f), gb, p);
    }

    // the service line: from the meter up the wall to the eave, then sagging across to the power pole's crossarm
    private static final float[][] SERVICE_LINE;
    static {
        float[] eave = {8.0f, 6.35f, 3.45f}, pole = {16.3f, 9.3f, 6.15f};
        SERVICE_LINE = new float[11][];
        SERVICE_LINE[0] = new float[]{8.0f, 6.22f, 2.0f};
        for (int k = 0; k <= 9; k++) {
            float f = k / 9f;
            SERVICE_LINE[k + 1] = new float[]{eave[0] + (pole[0] - eave[0]) * f, eave[1] + (pole[1] - eave[1]) * f,
                    eave[2] + (pole[2] - eave[2]) * f - 0.7f * (float) Math.sin(Math.PI * f)};
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

    // ---- the static house, lit for the time of day and the weather

    private static final int BRICK = 0xFFA4563F, ROOF = 0xFF44474D, LAWN = 0xFF5B8A3A, CONCRETE = 0xFFC2BDB2, TRIM = 0xFFF1F0EC;

    private void drawHouse(Canvas c, Outside o, int bars, float sun) {
        float night = 1 - o.day, sunny = o.day * (1 - o.clouds) * smooth(0, 6, (float) o.alt);
        // light on each face: the front wall faces the morning sun (left), the right wall the afternoon sun
        float front = 0.72f + 0.28f * (1 - o.frac), side = 0.6f + 0.38f * o.frac;
        front = front + (0.82f - front) * o.clouds;
        side = side + (0.74f - side) * o.clouds;

        // the block: lawn on a soil base
        fill(c, lit(0xFF5A4636, 0.55f, o), v(-2, 10, 0), v(17, 10, 0), v(17, 10, -0.7f), v(-2, 10, -0.7f));
        fill(c, lit(0xFF4A392C, 0.45f, o), v(17, -2, 0), v(17, 10, 0), v(17, 10, -0.7f), v(17, -2, -0.7f));
        p.setColor(Color.WHITE);
        p.setShader(new LinearGradient(sx(-2, -2), sy(-2, -2, 0), sx(17, 10), sy(17, 10, 0), lit(0xFF6E9C47, 1, o), lit(LAWN, 0.85f, o), Shader.TileMode.CLAMP));
        c.drawPath(poly(v(-2, -2, 0), v(17, -2, 0), v(17, 10, 0), v(-2, 10, 0)), p);
        p.setShader(null);
        fill(c, lit(CONCRETE, 0.95f, o), v(10.7f, 6, 0.01f), v(14.5f, 6, 0.01f), v(14.5f, 10, 0.01f), v(10.7f, 10, 0.01f));   // driveway
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, u * 0.04f));
        p.setColor(lit(0xFF9C978C, 0.9f, o));
        c.drawLine(sx(12.6f, 6), sy(12.6f, 6, 0.01f), sx(12.6f, 10), sy(12.6f, 10, 0.01f), p);
        p.setStyle(Paint.Style.FILL);
        for (int k = 0; k < 6; k++) {                                                          // stepping-stone path to the door
            float y0 = 6.5f + k * 0.6f;
            fill(c, lit(0xFFB7AE9E, 0.95f, o), v(8.75f, y0, 0.01f), v(9.55f, y0, 0.01f), v(9.55f, y0 + 0.42f, 0.01f), v(8.75f, y0 + 0.42f, 0.01f));
        }
        if (night > 0.3f) {                                                                    // window light on the lawn
            int a = (int) (0x50 * night);
            float[][] spill = {{1.15f, 7.2f}, {6.65f, 7.2f}, {9.15f, 7.0f}};
            for (float[] s : spill) {
                p.setShader(new RadialGradient(sx(s[0], s[1]), sy(s[0], s[1], 0), u * 2.2f, (a << 24) | 0xFFB45A, 0x00FFB45A, Shader.TileMode.CLAMP));
                c.drawCircle(sx(s[0], s[1]), sy(s[0], s[1], 0), u * 2.2f, p);
            }
            p.setShader(null);
        }
        // shadows away from the sun
        if (sunny > 0.05f) {
            float len = (float) Math.min(2.2, 1 / Math.tan(Math.toRadians(Math.max(8, o.alt))));
            float dx = (0.5f - o.frac) * 2.2f * len, dy = -0.9f * len;                        // sun left (morning) -> shadow right
            shadow(c, 0, 10, 0, 6, 4, 7, dx, dy, sunny);
            shadow(c, 10, 15, 1.5f, 6, 2.9f, 2.9f, dx, dy, sunny);
        }
        tree(c, 15.6f, -0.6f, 1.3f, o);

        // back roof slope and chimney, seen over the ridge
        int roofBack = lit(ROOF, 0.78f, o);
        fill(c, roofBack, v(-0.3f, -0.5f, 3.8f), v(10.3f, -0.5f, 3.8f), v(10.3f, 3, 7), v(-0.3f, 3, 7));
        tiles(c, roofBack, false);
        brickBox(c, 6.6f, 7.5f, 1.4f, 2.3f, 5, 8.2f, o, front, side);
        fill(c, lit(0xFF6B6E73, 0.9f, o), v(6.45f, 1.25f, 8.2f), v(7.65f, 1.25f, 8.2f), v(7.65f, 2.45f, 8.2f), v(6.45f, 2.45f, 8.2f));

        // walls
        Path right = poly(v(10, 0, 0), v(10, 6, 0), v(10, 6, 4), v(10, 3, 7), v(10, 0, 4));
        Path wall = poly(v(0, 6, 0), v(10, 6, 0), v(10, 6, 4), v(0, 6, 4));
        p.setColor(lit(BRICK, side, o));
        c.drawPath(right, p);
        bricks(c, right, false, 10, 0, 6, 0, 7, lit(BRICK, side, o));
        p.setColor(lit(BRICK, front, o));
        c.drawPath(wall, p);
        bricks(c, wall, true, 6, 0, 10, 0, 4, lit(BRICK, front, o));
        // contact shadow where the walls meet the lawn
        p.setColor(Color.WHITE);
        p.setShader(new LinearGradient(0, sy(0, 6, 0), 0, sy(0, 6, 0.6f), 0x40000000, 0x00000000, Shader.TileMode.CLAMP));
        c.drawPath(wall, p);
        p.setShader(null);

        float glow = (float) (0.15 + 0.85 * night);
        window(c, true, 0.7f, 1.6f, 2.4f, 3.5f, glow, o);
        window(c, true, 2.2f, 3.1f, 2.4f, 3.5f, glow, o);
        window(c, true, 6.5f, 7.4f, 2.4f, 3.5f, glow * 0.8f, o);
        window(c, true, 8.0f, 8.9f, 2.4f, 3.5f, glow, o);
        window(c, true, 0.7f, 1.4f, 0.6f, 1.9f, glow, o);
        window(c, true, 6.2f, 7.1f, 0.6f, 1.9f, glow, o);
        window(c, false, 2.5f, 3.5f, 4.3f, 5.4f, glow * 0.7f, o);
        // front door: timber with a frosted side light and a porch light
        fill(c, lit(TRIM, front, o), v(8.6f, 6.02f, 0), v(9.7f, 6.02f, 0), v(9.7f, 6.02f, 2.25f), v(8.6f, 6.02f, 2.25f));
        fill(c, lit(0xFF6B4226, front, o), v(8.7f, 6.03f, 0), v(9.35f, 6.03f, 0), v(9.35f, 6.03f, 2.15f), v(8.7f, 6.03f, 2.15f));
        fill(c, mix(lit(0xFFC9D6E0, front, o), 0xFFFFD08A, night * 0.8f), v(9.42f, 6.03f, 0.1f), v(9.62f, 6.03f, 0.1f), v(9.62f, 6.03f, 2.1f), v(9.42f, 6.03f, 2.1f));
        p.setColor(mix(0xFFB89A6A, 0xFFFFE2A8, night));
        c.drawCircle(sx(9.2f, 6.03f), sy(9.2f, 6.03f, 1.05f), Math.max(1.5f, u * 0.06f), p);
        if (night > 0.3f) {
            p.setShader(new RadialGradient(sx(9.9f, 6.05f), sy(9.9f, 6.05f, 2.4f), u * 1.6f, ((int) (0x99 * night) << 24) | 0xFFD38A, 0x00FFD38A, Shader.TileMode.CLAMP));
            c.drawCircle(sx(9.9f, 6.05f), sy(9.9f, 6.05f, 2.4f), u * 1.6f, p);
            p.setShader(null);
        }
        p.setColor(mix(0xFF2A2A2A, 0xFFFFE9B8, night));
        c.drawCircle(sx(9.9f, 6.05f), sy(9.9f, 6.05f, 2.4f), Math.max(1.5f, u * 0.08f), p);

        // garage: brick walls, a tiled skillion roof, a white panelled door
        Path gSide = poly(v(15, 1.5f, 0), v(15, 6, 0), v(15, 6, 2.8f), v(15, 1.5f, 2.8f));
        Path gFront = poly(v(10, 6, 0), v(15, 6, 0), v(15, 6, 2.8f), v(10, 6, 2.8f));
        p.setColor(lit(BRICK, side, o));
        c.drawPath(gSide, p);
        bricks(c, gSide, false, 15, 1.5f, 6, 0, 2.8f, lit(BRICK, side, o));
        p.setColor(lit(BRICK, front, o));
        c.drawPath(gFront, p);
        bricks(c, gFront, true, 6, 10, 15, 0, 2.8f, lit(BRICK, front, o));
        int gRoof = lit(ROOF, 0.92f, o);
        fill(c, gRoof, v(9.8f, 1.2f, 3.15f), v(15.3f, 1.2f, 3.15f), v(15.3f, 6.35f, 2.8f), v(9.8f, 6.35f, 2.8f));
        fill(c, lit(TRIM, front * 0.92f, o), v(9.8f, 6.35f, 2.8f), v(15.3f, 6.35f, 2.8f), v(15.3f, 6.35f, 2.6f), v(9.8f, 6.35f, 2.6f));
        fill(c, lit(TRIM, side * 0.9f, o), v(15.3f, 1.2f, 3.15f), v(15.3f, 6.35f, 2.8f), v(15.3f, 6.35f, 2.6f), v(15.3f, 1.2f, 2.95f));
        float gd0 = 10.9f, gd1 = 14.3f, gz = 2.25f;
        fill(c, lit(0xFFEDEDE8, front, o), v(gd0, 6.01f, 0), v(gd1, 6.01f, 0), v(gd1, 6.01f, gz), v(gd0, 6.01f, gz));
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, u * 0.035f));
        p.setColor(lit(0xFFB9B9B2, front, o));
        for (int rI = 1; rI < 4; rI++) { float z = gz * rI / 4; c.drawLine(sx(gd0, 6.01f), sy(gd0, 6.01f, z), sx(gd1, 6.01f), sy(gd1, 6.01f, z), p); }
        for (int k = 0; k < 4; k++) for (int cI = 0; cI < 4; cI++) {                        // raised panels
            float a0 = gd0 + 0.12f + cI * (gd1 - gd0) / 4, a1 = a0 + (gd1 - gd0) / 4 - 0.24f, z0 = gz * k / 4 + 0.1f, z1 = z0 + gz / 4 - 0.2f;
            c.drawPath(poly(v(a0, 6.01f, z0), v(a1, 6.01f, z0), v(a1, 6.01f, z1), v(a0, 6.01f, z1)), p);
        }
        p.setStyle(Paint.Style.FILL);

        // front roof slope: tiles, fascia and gutter, then the panels
        int roofFront = lit(ROOF, 0.98f, o);
        fill(c, roofFront, v(-0.3f, 6.5f, 3.8f), v(10.3f, 6.5f, 3.8f), v(10.3f, 3, 7), v(-0.3f, 3, 7));
        tiles(c, roofFront, true);
        fill(c, lit(TRIM, front * 0.95f, o), v(10.3f, 6.5f, 3.8f), v(10.3f, 6.5f, 3.52f), v(-0.3f, 6.5f, 3.52f), v(-0.3f, 6.5f, 3.8f));
        fill(c, lit(TRIM, side * 0.9f, o), v(10.3f, 6.5f, 3.8f), v(10.3f, 3, 7), v(10.3f, 3, 6.75f), v(10.3f, 6.5f, 3.52f));     // barge board
        // ridge capping, gutter and downpipes
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStrokeWidth(Math.max(2, u * 0.16f));
        p.setColor(lit(0xFF5A5D63, 1.05f, o));
        c.drawLine(sx(-0.3f, 3), sy(-0.3f, 3, 7.02f), sx(10.3f, 3), sy(10.3f, 3, 7.02f), p);
        p.setStrokeWidth(Math.max(1.5f, u * 0.1f));
        p.setColor(lit(0xFFDADAD5, front, o));
        c.drawLine(sx(-0.3f, 6.55f), sy(-0.3f, 6.55f, 3.5f), sx(10.3f, 6.55f), sy(10.3f, 6.55f, 3.5f), p);
        for (float dx : new float[]{-0.15f, 10.15f}) c.drawLine(sx(dx, 6.55f), sy(dx, 6.55f, 3.5f), sx(dx, 6.55f), sy(dx, 6.55f, 0.05f), p);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStyle(Paint.Style.FILL);
        drawPanels(c, sun, o);

        // equipment: inverter and meter on the wall, the battery in front
        box(c, 4.3f, 5.7f, 6, 6.3f, 1.2f, 2.8f, lit(0xFFF2F3F5, front, o), lit(0xFFD2D5DA, side, o), lit(0xFFFFFFFF, 1, o));
        p.setColor(0xFFFF9F0A);
        c.drawCircle(sx(5.0f, 6.3f), sy(5.0f, 6.3f, 2.5f), Math.max(2, u * 0.12f), p);       // the sun badge
        fill(c, lit(0xFF7D8794, front, o), v(4.6f, 6.31f, 1.9f), v(5.4f, 6.31f, 1.9f), v(5.4f, 6.31f, 2.2f), v(4.6f, 6.31f, 2.2f));
        box(c, 7.6f, 8.4f, 6, 6.25f, 1.2f, 2.0f, lit(0xFFE9EBEE, front, o), lit(0xFFC6CAD0, side, o), lit(0xFFFFFFFF, 1, o));
        fill(c, 0x30000000, v(1.5f, 6.4f, 0.01f), v(3.1f, 6.4f, 0.01f), v(3.1f, 7.5f, 0.01f), v(1.5f, 7.5f, 0.01f));
        box(c, 1.6f, 2.9f, 6.4f, 7.3f, 0, 2.0f, lit(0xFFF4F5F7, front, o), lit(0xFFD3D6DC, side, o), lit(0xFFFFFFFF, 1, o));
        for (int k = 0; k < 5; k++) {
            float z = 0.45f + k * 0.26f;
            fill(c, k < bars ? BATTERY : lit(0xFFB9BDC4, front, o), v(2.05f, 7.31f, z), v(2.45f, 7.31f, z), v(2.45f, 7.31f, z + 0.16f), v(2.05f, 7.31f, z + 0.16f));
        }
        // garden: shrubs along the front
        shrub(c, 0.55f, 7.0f, 0.55f, o);
        shrub(c, 3.6f, 7.0f, 0.45f, o);
        shrub(c, 6.5f, 6.95f, 0.5f, o);
        shrub(c, 7.75f, 7.05f, 0.38f, o);
        shrub(c, 15.6f, 6.8f, 0.6f, o);
        tree(c, -1.0f, 8.6f, 1.0f, o);
        car(c, o, front, side);
        letterbox(c, 9.9f, 9.55f, o);
        pole(c, 16.3f, 9.3f, o);
    }

    /** A white SUV parked on the driveway, nose to the garage. */
    private void car(Canvas c, Outside o, float front, float side) {
        float x0 = 11.9f, x1 = 13.3f, y0 = 7.0f, y1 = 9.7f;
        fill(c, 0x40000000, v(x0 - 0.15f, y0 - 0.1f, 0.01f), v(x1 + 0.25f, y0 - 0.1f, 0.01f), v(x1 + 0.25f, y1 + 0.15f, 0.01f), v(x0 - 0.15f, y1 + 0.15f, 0.01f));
        int body = 0xFFF2F2F0;
        // wheels (on the visible right side)
        p.setColor(lit(0xFF1C1C1E, 1, o));
        for (float wy : new float[]{7.55f, 9.15f}) {
            float cx = sx(x1 + 0.01f, wy), cy = sy(x1 + 0.01f, wy, 0.3f), rw = u * 0.34f;
            c.drawOval(new RectF(cx - rw * 0.75f, cy - rw, cx + rw * 0.75f, cy + rw), p);
        }
        box(c, x0, x1, y0, y1, 0.25f, 0.95f, lit(body, front, o), lit(body, side * 0.92f, o), lit(body, 1.02f, o));
        // cabin with dark glass
        float cy0 = 7.35f, cy1 = 9.0f;
        fill(c, lit(0xFF1F2933, 1, o), v(x1 - 0.05f, cy0, 0.95f), v(x1 - 0.05f, cy1, 0.95f), v(x1 - 0.12f, cy1 - 0.25f, 1.5f), v(x1 - 0.12f, cy0 + 0.1f, 1.5f));
        fill(c, lit(0xFF2B3742, 1, o), v(x0 + 0.05f, cy1, 0.95f), v(x1 - 0.05f, cy1, 0.95f), v(x1 - 0.12f, cy1 - 0.25f, 1.5f), v(x0 + 0.12f, cy1 - 0.25f, 1.5f));
        fill(c, lit(body, 1.02f, o), v(x0 + 0.12f, cy0 + 0.1f, 1.5f), v(x1 - 0.12f, cy0 + 0.1f, 1.5f), v(x1 - 0.12f, cy1 - 0.25f, 1.5f), v(x0 + 0.12f, cy1 - 0.25f, 1.5f));
        p.setColor(lit(body, side, o));                                       // pillar between the windows
        c.drawLine(sx(x1 - 0.08f, 8.2f), sy(x1 - 0.08f, 8.2f, 0.95f), sx(x1 - 0.1f, 8.15f), sy(x1 - 0.1f, 8.15f, 1.5f), p);
        // lights: tail lights at the back (towards the viewer), glowing a little at night
        float glow = 1 - o.day;
        fill(c, mix(0xFF8A1C1C, 0xFFFF3B30, glow), v(x0 + 0.1f, y1 + 0.01f, 0.7f), v(x0 + 0.4f, y1 + 0.01f, 0.7f), v(x0 + 0.4f, y1 + 0.01f, 0.82f), v(x0 + 0.1f, y1 + 0.01f, 0.82f));
        fill(c, mix(0xFF8A1C1C, 0xFFFF3B30, glow), v(x1 - 0.4f, y1 + 0.01f, 0.7f), v(x1 - 0.1f, y1 + 0.01f, 0.7f), v(x1 - 0.1f, y1 + 0.01f, 0.82f), v(x1 - 0.4f, y1 + 0.01f, 0.82f));
    }

    /** A timber power pole with a crossarm at the street corner. */
    private void pole(Canvas c, float x, float y, Outside o) {
        float bx = sx(x, y), by = sy(x, y, 0), top = sy(x, y, 6.6f), w = u * 0.12f;
        p.setColor(0x30000000);
        c.drawOval(new RectF(bx - u * 0.4f, by - u * 0.12f, bx + u * 0.4f, by + u * 0.12f), p);
        p.setColor(lit(0xFF6B5544, 0.9f, o));
        c.drawRect(bx - w, top, bx + w, by, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(2, u * 0.11f));
        p.setColor(lit(0xFF5A4636, 0.9f, o));
        c.drawLine(sx(x - 0.9f, y), sy(x - 0.9f, y, 6.15f), sx(x + 0.9f, y), sy(x + 0.9f, y, 6.15f), p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(lit(0xFFDDE3EA, 1, o));                                   // insulators
        for (float dx : new float[]{-0.7f, 0, 0.7f}) c.drawCircle(sx(x + dx, y), sy(x + dx, y, 6.3f), Math.max(1.5f, u * 0.07f), p);
    }

    private void letterbox(Canvas c, float x, float y, Outside o) {
        p.setColor(lit(0xFF5A4636, 0.9f, o));
        c.drawRect(sx(x, y) - u * 0.04f, sy(x, y, 1.0f), sx(x, y) + u * 0.04f, sy(x, y, 0), p);
        box(c, x - 0.2f, x + 0.2f, y - 0.25f, y + 0.25f, 1.0f, 1.4f, lit(0xFF3A3D42, 0.9f, o), lit(0xFF2E3136, 0.8f, o), lit(0xFF4A4D52, 1, o));
    }

    /** A box seen from the front-right: its front (y1), right (x1) and top faces. */
    private void box(Canvas c, float x0, float x1, float y0, float y1, float z0, float z1, int front, int side, int top) {
        fill(c, side, v(x1, y0, z0), v(x1, y1, z0), v(x1, y1, z1), v(x1, y0, z1));
        fill(c, front, v(x0, y1, z0), v(x1, y1, z0), v(x1, y1, z1), v(x0, y1, z1));
        fill(c, top, v(x0, y0, z1), v(x1, y0, z1), v(x1, y1, z1), v(x0, y1, z1));
    }

    private void brickBox(Canvas c, float x0, float x1, float y0, float y1, float z0, float z1, Outside o, float front, float side) {
        Path s = poly(v(x1, y0, z0), v(x1, y1, z0), v(x1, y1, z1), v(x1, y0, z1)), f = poly(v(x0, y1, z0), v(x1, y1, z0), v(x1, y1, z1), v(x0, y1, z1));
        p.setColor(lit(BRICK, side, o));
        c.drawPath(s, p);
        bricks(c, s, false, x1, y0, y1, z0, z1, lit(BRICK, side, o));
        p.setColor(lit(BRICK, front, o));
        c.drawPath(f, p);
        bricks(c, f, true, y1, x0, x1, z0, z1, lit(BRICK, front, o));
    }

    /** Mortar lines over a brick face (front: along x at y = fixed; side: along y at x = fixed), clipped to it. */
    private void bricks(Canvas c, Path face, boolean frontFace, float fixed, float a0, float a1, float z0, float z1, int base) {
        c.save();
        c.clipPath(face);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(0.8f, u * 0.025f));
        p.setColor(mix(base, 0xFFE8DCCF, 0.28f));
        p.setAlpha(110);
        float row = 0.22f;
        int r = 0;
        for (float z = z0 + row; z < z1; z += row, r++) {
            if (frontFace) c.drawLine(sx(a0, fixed), sy(a0, fixed, z), sx(a1, fixed), sy(a1, fixed, z), p);
            else c.drawLine(sx(fixed, a0), sy(fixed, a0, z), sx(fixed, a1), sy(fixed, a1, z), p);
            float off = (r % 2) * 0.25f;
            for (float a = a0 + off; a < a1; a += 0.5f) {
                if (frontFace) c.drawLine(sx(a, fixed), sy(a, fixed, z), sx(a, fixed), sy(a, fixed, z - row), p);
                else c.drawLine(sx(fixed, a), sy(fixed, a, z), sx(fixed, a), sy(fixed, a, z - row), p);
            }
        }
        p.setAlpha(255);
        p.setStyle(Paint.Style.FILL);
        c.restore();
    }

    /** Tile rows across a roof slope, parallel to the eave. */
    private void tiles(Canvas c, int base, boolean front) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(0.8f, u * 0.03f));
        p.setColor(mix(base, 0xFF000000, 0.35f));
        for (float f = 0.08f; f < 1; f += 0.09f) {
            float y = front ? 6.5f - 3.5f * f : -0.5f + 3.5f * f, z = 3.8f + 3.2f * f;
            c.drawLine(sx(-0.3f, y), sy(-0.3f, y, z), sx(10.3f, y), sy(10.3f, y, z), p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    /** A window on the front wall (y = 6) or the right gable (x = 10): white frame and sill, glass that reflects the sky
     *  by day and glows warm at night. */
    private void window(Canvas c, boolean front, float a0, float a1, float z0, float z1, float glow, Outside o) {
        float pad = 0.08f;
        Path frame = front ? poly(v(a0 - pad, 6.01f, z0 - pad), v(a1 + pad, 6.01f, z0 - pad), v(a1 + pad, 6.01f, z1 + pad), v(a0 - pad, 6.01f, z1 + pad))
                : poly(v(10.01f, a0 - pad, z0 - pad), v(10.01f, a1 + pad, z0 - pad), v(10.01f, a1 + pad, z1 + pad), v(10.01f, a0 - pad, z1 + pad));
        Path q = front ? poly(v(a0, 6.02f, z0), v(a1, 6.02f, z0), v(a1, 6.02f, z1), v(a0, 6.02f, z1))
                : poly(v(10.02f, a0, z0), v(10.02f, a1, z0), v(10.02f, a1, z1), v(10.02f, a0, z1));
        float cx = front ? sx((a0 + a1) / 2, 6) : sx(10, (a0 + a1) / 2), cy = front ? sy((a0 + a1) / 2, 6, (z0 + z1) / 2) : sy(10, (a0 + a1) / 2, (z0 + z1) / 2);
        float warm = glow * (0.25f + 0.75f * (1 - o.day));
        if (warm > 0.2f) {
            p.setColor(Color.WHITE);
            p.setShader(new RadialGradient(cx, cy, u * 2.0f, ((int) (0x60 * warm) << 24) | 0xFFB347, 0x00FFB347, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, u * 2.0f, p);
            p.setShader(null);
        }
        p.setColor(lit(TRIM, 0.95f, o));
        c.drawPath(frame, p);
        int glassTop = mix(lit(0xFF9DB6CF, 1, o), 0xFFFFD58A, warm), glassBottom = mix(lit(0xFF3E4E61, 1, o), 0xFFC7711F, warm);
        p.setColor(Color.WHITE);
        p.setShader(new LinearGradient(0, cy - u, 0, cy + u, glassTop, glassBottom, Shader.TileMode.CLAMP));
        c.drawPath(q, p);
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(1, u * 0.06f));
        p.setColor(lit(TRIM, 0.95f, o));
        float m = (a0 + a1) / 2, zm = z0 + (z1 - z0) * 0.62f;
        if (front) {
            c.drawLine(sx(m, 6.02f), sy(m, 6.02f, z0), sx(m, 6.02f), sy(m, 6.02f, z1), p);
            c.drawLine(sx(a0, 6.02f), sy(a0, 6.02f, zm), sx(a1, 6.02f), sy(a1, 6.02f, zm), p);
        } else {
            c.drawLine(sx(10.02f, m), sy(10.02f, m, z0), sx(10.02f, m), sy(10.02f, m, z1), p);
            c.drawLine(sx(10.02f, a0), sy(10.02f, a0, zm), sx(10.02f, a1), sy(10.02f, a1, zm), p);
        }
        p.setStyle(Paint.Style.FILL);
        if (front)                                                                            // sill
            fill(c, lit(TRIM, 1, o), v(a0 - 0.14f, 6.0f, z0 - 0.1f), v(a1 + 0.14f, 6.0f, z0 - 0.1f), v(a1 + 0.14f, 6.18f, z0 - 0.1f), v(a0 - 0.14f, 6.18f, z0 - 0.1f));
    }

    /** 3 rows of 8 framed panels on the front roof slope; deep blue that catches the light when the sun is out. */
    private void drawPanels(Canvas c, float sun, Outside o) {
        int cols = 8, rows = 3;
        float sheen = o.day * (1 - o.clouds * 0.7f);
        for (int i = 0; i < cols; i++) for (int j = 0; j < rows; j++) {
            float x0 = 0.5f + i * 1.15f, x1 = x0 + 1.05f;
            float f0 = 0.10f + j * 0.28f, f1 = f0 + 0.25f;              // 0 at the eave, 1 at the ridge
            float y0 = 6.5f - 3.5f * f0, z0 = 3.8f + 3.2f * f0, y1 = 6.5f - 3.5f * f1, z1 = 3.8f + 3.2f * f1;
            Path q = poly(v(x0, y0, z0 + 0.06f), v(x1, y0, z0 + 0.06f), v(x1, y1, z1 + 0.06f), v(x0, y1, z1 + 0.06f));
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            int top = mix(lit(0xFF1F2B44, 1, o), 0xFF4A6FA8, sheen * 0.6f + sun * 0.25f), bottom = lit(0xFF111827, 1, o);
            p.setShader(new LinearGradient(sx(x0, y1), sy(x0, y1, z1), sx(x1, y0), sy(x1, y0, z0), top, bottom, Shader.TileMode.CLAMP));
            c.drawPath(q, p);
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(0.6f, u * 0.02f));                                       // cell grid
            p.setColor((lit(0xFF8090A8, 1, o) & 0x00FFFFFF) | 0x40000000);
            for (int k = 1; k < 4; k++) {
                float xx = x0 + (x1 - x0) * k / 4;
                c.drawLine(sx(xx, y0), sy(xx, y0, z0 + 0.06f), sx(xx, y1), sy(xx, y1, z1 + 0.06f), p);
            }
            float ym = (y0 + y1) / 2, zmid = (z0 + z1) / 2 + 0.06f;
            c.drawLine(sx(x0, ym), sy(x0, ym, zmid), sx(x1, ym), sy(x1, ym, zmid), p);
            p.setStrokeWidth(Math.max(1, u * 0.045f));
            p.setColor(lit(0xFFB8C0CB, 1, o));                                                 // aluminium frame
            c.drawPath(q, p);
        }
        p.setStyle(Paint.Style.FILL);
    }

    /** The shadow of a block (walls to wallZ, roof ridge to ridgeZ) on the lawn, cast by (dx, dy) per unit of height. */
    private void shadow(Canvas c, float x0, float x1, float y0, float y1, float wallZ, float ridgeZ, float dx, float dy, float strength) {
        float[][] base = {{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}};
        List<float[]> pts = new ArrayList<>();
        for (float[] q : base) {
            pts.add(new float[]{sx(q[0], q[1]), sy(q[0], q[1], 0)});
            pts.add(new float[]{sx(q[0] + dx * wallZ, q[1] + dy * wallZ), sy(q[0] + dx * wallZ, q[1] + dy * wallZ, 0)});
        }
        float my = (y0 + y1) / 2;
        for (float x : new float[]{x0, x1}) pts.add(new float[]{sx(x + dx * ridgeZ, my + dy * ridgeZ), sy(x + dx * ridgeZ, my + dy * ridgeZ, 0)});
        List<float[]> hull = hull(pts);
        path.reset();
        for (int i = 0; i < hull.size(); i++) { if (i == 0) path.moveTo(hull.get(i)[0], hull.get(i)[1]); else path.lineTo(hull.get(i)[0], hull.get(i)[1]); }
        path.close();
        p.setStyle(Paint.Style.FILL);
        p.setColor(((int) (0x55 * strength) << 24));
        c.drawPath(path, p);
    }

    private static List<float[]> hull(List<float[]> pts) {
        pts.sort((a, b) -> a[0] != b[0] ? Float.compare(a[0], b[0]) : Float.compare(a[1], b[1]));
        int n = pts.size(), k = 0;
        float[][] h = new float[2 * n][];
        for (int i = 0; i < n; i++) { while (k >= 2 && cross(h[k - 2], h[k - 1], pts.get(i)) <= 0) k--; h[k++] = pts.get(i); }
        for (int i = n - 2, t = k + 1; i >= 0; i--) { while (k >= t && cross(h[k - 2], h[k - 1], pts.get(i)) <= 0) k--; h[k++] = pts.get(i); }
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < k - 1; i++) out.add(h[i]);
        return out;
    }

    private static float cross(float[] o, float[] a, float[] b) { return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]); }

    private void shrub(Canvas c, float x, float y, float r, Outside o) {
        float cx = sx(x, y), cy = sy(x, y, r * 0.8f), rr = r * u * 1.15f;
        p.setColor(0x30000000);
        c.drawOval(new RectF(cx - rr, sy(x, y, 0) - rr * 0.35f, cx + rr, sy(x, y, 0) + rr * 0.35f), p);
        p.setColor(Color.WHITE);
        p.setShader(new RadialGradient(cx - rr * 0.35f, cy - rr * 0.4f, rr * 1.4f, lit(0xFF5FA046, 1.05f, o), lit(0xFF2B5524, 0.8f, o), Shader.TileMode.CLAMP));
        c.drawCircle(cx - rr * 0.35f, cy + rr * 0.1f, rr * 0.75f, p);
        c.drawCircle(cx + rr * 0.35f, cy + rr * 0.15f, rr * 0.7f, p);
        c.drawCircle(cx, cy - rr * 0.25f, rr * 0.8f, p);
        p.setShader(null);
    }

    private void tree(Canvas c, float x, float y, float s, Outside o) {
        float bx = sx(x, y), by = sy(x, y, 0), r = u * 1.6f * s;
        p.setColor(0x33000000);
        c.drawOval(new RectF(bx - r, by - r * 0.3f, bx + r, by + r * 0.3f), p);
        p.setColor(lit(0xFF5B4030, 0.85f, o));
        c.drawRect(bx - u * 0.12f * s, sy(x, y, 3.2f * s), bx + u * 0.12f * s, by, p);
        p.setColor(Color.WHITE);
        float cy = sy(x, y, 4.2f * s);
        p.setShader(new RadialGradient(bx - r * 0.4f, cy - r * 0.5f, r * 2.0f, lit(0xFF6DAA4C, 1.05f, o), lit(0xFF24461E, 0.8f, o), Shader.TileMode.CLAMP));
        c.drawCircle(bx - r * 0.45f, cy + r * 0.25f, r * 0.75f, p);
        c.drawCircle(bx + r * 0.5f, cy + r * 0.3f, r * 0.7f, p);
        c.drawCircle(bx, cy - r * 0.35f, r * 0.85f, p);
        c.drawCircle(bx + r * 0.1f, cy + r * 0.55f, r * 0.6f, p);
        p.setShader(null);
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
        // small glowing arrowheads pointing the way the power flows, each pulling a fading trail along the cable
        float total = len[m - 1], gap = px(18), speed = px(24) + px(22) * (float) Math.log10(1 + watts / 100);
        float shift = (float) ((time * (double) speed / 1000) % gap), a = px(2.6f), trail = gap * 0.85f;
        int steps = 6;
        Path arrows = new Path();
        Path[] fade = new Path[steps];
        for (int k = 0; k < steps; k++) fade[k] = new Path();
        float[] pt = new float[4];
        for (float s = shift; s < total; s += gap) {
            float d = forward ? s : total - s;
            along(X, Y, len, d, pt);
            float x = pt[0], y = pt[1], ux = pt[2], uy = pt[3];
            if (!forward) { ux = -ux; uy = -uy; }
            float tipX = x + ux * a * 0.6f, tipY = y + uy * a * 0.6f;                // > shape: tip ahead, wings behind
            arrows.moveTo(tipX - ux * a - uy * a * 0.85f, tipY - uy * a + ux * a * 0.85f);
            arrows.lineTo(tipX, tipY);
            arrows.lineTo(tipX - ux * a + uy * a * 0.85f, tipY - uy * a - ux * a * 0.85f);
            for (int k = 0; k < steps; k++) {                                     // the trail, brighter towards the arrow
                float d0 = d - (forward ? 1 : -1) * trail * (1 - (float) k / steps), d1 = d - (forward ? 1 : -1) * trail * (1 - (float) (k + 1) / steps);
                if (Math.min(d0, d1) < 0 || Math.max(d0, d1) > total) continue;
                along(X, Y, len, d0, pt);
                fade[k].moveTo(pt[0], pt[1]);
                along(X, Y, len, d1, pt);
                fade[k].lineTo(pt[0], pt[1]);
            }
        }
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(px(1.8f));
        for (int k = 0; k < steps; k++) {
            p.setColor((color & 0x00FFFFFF) | ((int) (0xC0 * (k + 1f) / steps * (k + 1f) / steps) << 24));
            c.drawPath(fade[k], p);
        }
        p.setStrokeWidth(px(4.2f));
        p.setColor((color & 0x00FFFFFF) | 0x50000000);                                  // glow
        c.drawPath(arrows, p);
        p.setStrokeWidth(px(1.3f));
        p.setColor(mix(color, 0xFFFFFFFF, 0.45f));                                      // bright core
        c.drawPath(arrows, p);
        p.setStyle(Paint.Style.FILL);
    }

    /** The point at distance d along a polyline and its direction: out = {x, y, ux, uy}. */
    private static void along(float[] X, float[] Y, float[] len, float d, float[] out) {
        int m = X.length, k = 1;
        while (k < m - 1 && len[k] < d) k++;
        float sl = Math.max(1, len[k] - len[k - 1]), f = (d - len[k - 1]) / sl;
        out[0] = X[k - 1] + (X[k] - X[k - 1]) * f;
        out[1] = Y[k - 1] + (Y[k] - Y[k - 1]) * f;
        out[2] = (X[k] - X[k - 1]) / sl;
        out[3] = (Y[k] - Y[k - 1]) / sl;
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
