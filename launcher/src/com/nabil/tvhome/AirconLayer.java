package com.nabil.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Aircon screen for the Daikin Airbase: on the left a ceiling vent whose airflow takes the mode's colour and the fan's
 * speed, with the inside and outside temperatures; on the right a card with the set temperature (- / +), the mode, the
 * fan speed, zones when the system has them, and On / Off. Changes show at once and are sent after a short pause, so
 * several presses of + go out as one command; readings that arrive meanwhile don't undo them. Under the temperature,
 * the Airbase's own on and off timers (they run on the Airbase, TV off or not): a switch each, - / + in 15-minute
 * steps (held: an hour at a time) and ✕ to remove a set one.
 */
class AirconLayer extends FrameLayout {
    private static final int GREY = 0x99FFFFFF, BLUE = 0xFF64D2FF, ORANGE = 0xFFFF9F0A, TEAL = 0xFF5EE6C8, WHITE = 0xFFE5E5EA;
    private static final long ARM_MS = 700, SEND_DELAY_MS = 900, HOLD_MS = 8000, POLL_MS = 20_000;
    private static final int[] MODES = {2, 1, 7, 0};                       // cool, heat, dry, fan
    private static final String[] MODE_NAMES = {"Cool", "Heat", "Dry", "Fan"};
    private static final int[] FANS = {1, 3, 5, 0};                        // low, medium, high, auto (0)
    private static final String[] FAN_NAMES = {"Low", "Med", "High", "Auto"};

    private final Handler ui;
    private final float dp;
    private final Airbase ac;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final TextView title, state, temp, minus, plus, power, close, inside, outside;
    private final TextView[] modeChips = new TextView[MODES.length], fanChips = new TextView[FANS.length];
    private final LinearLayout zoneRow;
    private final java.util.List<Integer> zoneIndex = new java.util.ArrayList<>();   // zone number of each zone button
    private final AirArt art;
    private Airbase.Status st;                                // what the screen shows (and what gets sent)
    private long openedAt, touchedAt, timersTouchedAt;
    private Airbase.Timer[] timers;                           // what the timer box shows (and what gets sent)
    private final TextView[] timerSwitch = new TextView[2], timerTime = new TextView[2], timerMinus = new TextView[2], timerPlus = new TextView[2], timerClear = new TextView[2];
    private final LinearLayout timerBox;
    private boolean sending;

    AirconLayer(Context c, Handler ui, Airbase ac) {
        super(c);
        this.ui = ui;
        this.ac = ac;
        this.dp = c.getResources().getDisplayMetrics().density;
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF151820, 0xFF060709}));
        setVisibility(GONE);
        setClickable(true);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(40), dp(32), dp(40), dp(32));
        addView(row, new LayoutParams(-1, -1));

        // ---- airflow picture
        FrameLayout pic = new FrameLayout(c);
        pic.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(22)); }
        });
        pic.setClipToOutline(true);
        pic.setElevation(dp(12));
        art = new AirArt(c);
        pic.addView(art, new LayoutParams(-1, -1));
        LinearLayout temps = new LinearLayout(c);
        temps.setOrientation(LinearLayout.VERTICAL);
        inside = text(c, 64, Color.WHITE, Typeface.NORMAL);
        inside.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        inside.setGravity(Gravity.START);
        outside = text(c, 18, 0xCCFFFFFF, Typeface.NORMAL);
        outside.setGravity(Gravity.START);
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-2, -2);
        op.setMargins(dp(4), -dp(6), 0, 0);
        temps.addView(inside);
        temps.addView(outside, op);
        LayoutParams tp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        tp.setMargins(dp(28), 0, 0, dp(22));
        pic.addView(temps, tp);
        row.addView(pic, new LinearLayout.LayoutParams(0, -1, 1f));

        // ---- control card
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(22), dp(16), dp(22), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE61C1C1E);
        bg.setCornerRadius(dp(22));
        bg.setStroke(Math.max(1, dp(0.5f)), 0x33FFFFFF);
        card.setBackground(bg);
        card.setElevation(dp(12));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(dp(330), -1);
        cardLp.setMarginStart(dp(28));
        row.addView(card, cardLp);

        title = text(c, 20, GREY, Typeface.NORMAL);
        card.addView(title);
        state = text(c, 16, GREY, Typeface.NORMAL);
        card.addView(state);

        LinearLayout tr = new LinearLayout(c);
        tr.setOrientation(LinearLayout.HORIZONTAL);
        tr.setGravity(Gravity.CENTER);
        minus = button(c, "\u2212", 24);
        minus.setOnClickListener(v -> nudge(-1));
        plus = button(c, "+", 24);
        plus.setOnClickListener(v -> nudge(1));
        temp = text(c, 48, Color.WHITE, Typeface.NORMAL);
        temp.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        tr.addView(minus, new LinearLayout.LayoutParams(dp(48), dp(48)));
        tr.addView(temp, new LinearLayout.LayoutParams(dp(120), -2));
        tr.addView(plus, new LinearLayout.LayoutParams(dp(48), dp(48)));
        card.addView(tr, new LinearLayout.LayoutParams(-1, 0, 1f));

        // ---- timers (the Airbase's own on / off timers), under the temperature
        timerBox = new LinearLayout(c);
        timerBox.setOrientation(LinearLayout.VERTICAL);
        timerBox.addView(label(c, "Timer"));
        for (int i = 0; i < 2; i++) {
            final int k = i;
            LinearLayout r = new LinearLayout(c);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            timerSwitch[i] = button(c, i == 0 ? "Turn on" : "Turn off", 14);
            timerSwitch[i].setOnClickListener(v -> timerChange(() -> {
                if (!timers[k].enabled) wake(timers[k]); else timers[k].enabled = false;
            }));
            timerMinus[i] = button(c, "\u2212", 18);
            timerPlus[i] = button(c, "+", 18);
            stepper(timerMinus[i], k, -1);
            stepper(timerPlus[i], k, 1);
            timerTime[i] = text(c, 17, Color.WHITE, Typeface.NORMAL);
            r.addView(timerSwitch[i], new LinearLayout.LayoutParams(dp(80), dp(34)));
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(34), dp(34));
            mp.setMarginStart(dp(6));
            r.addView(timerMinus[i], mp);
            r.addView(timerTime[i], new LinearLayout.LayoutParams(0, -2, 1f));
            r.addView(timerPlus[i], new LinearLayout.LayoutParams(dp(34), dp(34)));
            timerClear[i] = button(c, "\u2715", 15);                // remove this timer
            timerClear[i].setOnClickListener(v -> {
                timerSwitch[k].requestFocus();                    // the ✕ hides once the timer is gone
                timerChange(() -> timers[k].enabled = false);
            });
            LinearLayout.LayoutParams xp = new LinearLayout.LayoutParams(dp(34), dp(34));
            xp.setMarginStart(dp(6));
            r.addView(timerClear[i], xp);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            if (i > 0) rp.topMargin = dp(6);
            timerBox.addView(r, rp);
        }
        timerBox.setVisibility(GONE);
        card.addView(timerBox, new LinearLayout.LayoutParams(-1, -2));

        card.addView(label(c, "Mode"));
        card.addView(chips(c, MODE_NAMES, modeChips, i -> change(() -> {
            st.mode = MODES[i];
            st.temp = clamp(st.temp);
        })), new LinearLayout.LayoutParams(-1, dp(36)));
        card.addView(label(c, "Fan"));
        card.addView(chips(c, FAN_NAMES, fanChips, i -> change(() -> {
            st.fanAuto = FANS[i] == 0;
            if (!st.fanAuto) st.fan = FANS[i];
        })), new LinearLayout.LayoutParams(-1, dp(36)));

        zoneRow = new LinearLayout(c);
        zoneRow.setOrientation(LinearLayout.HORIZONTAL);
        zoneRow.setVisibility(GONE);
        LinearLayout.LayoutParams zp = new LinearLayout.LayoutParams(-1, dp(36));
        zp.topMargin = dp(10);
        card.addView(zoneRow, zp);

        // Turn on / off and Close side by side, to leave room for the timers
        LinearLayout bottom = new LinearLayout(c);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        power = button(c, "", 18);
        power.setTypeface(Typeface.DEFAULT_BOLD);
        power.setOnClickListener(v -> change(() -> st.power = !st.power));
        bottom.addView(power, new LinearLayout.LayoutParams(0, -1, 1.6f));
        close = button(c, "Close", 16);
        close.setOnClickListener(v -> close());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -1, 1f);
        cp.setMarginStart(dp(8));
        bottom.addView(close, cp);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(46));
        bp.topMargin = dp(16);
        card.addView(bottom, bp);
    }

    private int dp(float v) { return Math.round(v * dp); }

    /** Keep the remote's focus on this screen (the dock underneath stays focusable). */
    @Override public View focusSearch(View focused, int direction) {
        View next = android.view.FocusFinder.getInstance().findNextFocus(this, focused, direction);
        return next != null ? next : focused;
    }

    private TextView text(Context c, float sp, int color, int style) {
        TextView t = new TextView(c);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, style);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        return t;
    }

    private TextView label(Context c, String s) {
        TextView t = text(c, 13, GREY, Typeface.NORMAL);
        t.setText(s.toUpperCase());
        t.setLetterSpacing(0.12f);
        t.setGravity(Gravity.START);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(4), dp(10), 0, dp(5));
        t.setLayoutParams(lp);
        return t;
    }

    private TextView button(Context c, String s, float sp) {
        TextView b = text(c, sp, Color.WHITE, Typeface.NORMAL);
        b.setText(s);
        b.setFocusable(true);
        b.setTag(Boolean.FALSE);                                  // "selected" look for chips
        b.setOnFocusChangeListener((v, has) -> {
            style(b);
            b.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(120).start();
        });
        style(b);
        return b;
    }

    /** White when focused; tinted with the mode colour when selected; faint otherwise. */
    private void style(TextView b) {
        boolean focus = b.isFocused(), sel = Boolean.TRUE.equals(b.getTag());
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(12));
        d.setColor(focus ? Color.WHITE : sel ? (modeColor() & 0x00FFFFFF) | 0x66000000 : 0x1AFFFFFF);
        if (sel && !focus) d.setStroke(dp(1.5f), modeColor());
        b.setBackground(d);
        b.setTextColor(focus ? Color.BLACK : Color.WHITE);
    }

    private interface Pick { void at(int i); }

    private LinearLayout chips(Context c, String[] names, TextView[] out, Pick pick) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            TextView b = button(c, names[i], 15);
            b.setOnClickListener(v -> pick.at(k));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            if (i > 0) lp.setMarginStart(dp(6));
            r.addView(b, lp);
            out[i] = b;
        }
        return r;
    }

    // ------------------------------------------------------------ open / close

    boolean isOpen() { return getVisibility() == VISIBLE; }

    void open() {
        openedAt = SystemClock.uptimeMillis();
        title.setText(ac.name());
        render();
        setAlpha(0f);
        setVisibility(VISIBLE);
        animate().alpha(1f).setDuration(180).start();
        power.requestFocus();
        ac.refresh();
        art.start();
        ui.removeCallbacks(poll);
        ui.post(poll);
    }

    void close() {
        ui.removeCallbacks(poll);
        art.stop();
        setVisibility(GONE);
    }

    // ------------------------------------------------------------ state

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!isOpen()) return;
            worker.execute(() -> {
                Airbase.Status s = ac.status();
                ui.post(() -> {
                    if (s == null) {
                        if (st == null) { state.setText("Can't reach the Airbase"); state.setTextColor(GREY); }
                        return;
                    }
                    // a change still waiting to go out (or just sent) wins over this reading, apart from the sensors
                    if (st != null && (sending || SystemClock.uptimeMillis() - touchedAt < HOLD_MS)) {
                        st.inside = s.inside;
                        st.outside = s.outside;
                    } else {
                        st = s;
                    }
                    if (s.timers != null && SystemClock.uptimeMillis() - timersTouchedAt > HOLD_MS) timers = s.timers;
                    render();
                });
            });
            ui.postDelayed(this, POLL_MS);
        }
    };

    private void nudge(int d) {
        if (st == null || st.mode == 0) return;                   // fan only: no temperature
        change(() -> st.temp = clamp(st.temp + d));
    }

    private int clamp(int t) {
        if (st.mode == 1) return Math.max(st.heatLow, Math.min(st.heatHigh, t));
        return Math.max(st.coolLow, Math.min(st.coolHigh, t));
    }

    /** Apply a change on screen now, send it once the presses stop. */
    private void change(Runnable r) {
        if (st == null || SystemClock.uptimeMillis() - openedAt < ARM_MS) return;   // ignore the press that opened it
        r.run();
        touchedAt = SystemClock.uptimeMillis();
        render();
        ui.removeCallbacks(send);
        ui.postDelayed(send, SEND_DELAY_MS);
    }

    private final Runnable send = new Runnable() {
        @Override public void run() {
            if (st == null) return;
            final Airbase.Status s = st;
            sending = true;
            worker.execute(() -> {
                boolean ok = ac.set(s);
                ui.post(() -> {
                    sending = false;
                    touchedAt = SystemClock.uptimeMillis();       // give the Airbase a moment before trusting a reading
                    if (!ok) { state.setText("Couldn't reach the Airbase"); state.setTextColor(GREY); }
                    ui.removeCallbacks(poll);
                    ui.postDelayed(poll, 4000);
                });
            });
        }
    };

    /** - / + on a timer: 15 minutes a press; held, the repeats jump to the next whole hour. */
    private void stepper(TextView b, int k, int dir) {
        b.setOnClickListener(v -> timerChange(() -> step(k, dir, 15)));
        b.setOnKeyListener((v, code, e) -> {
            if ((code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER)
                    && e.getAction() == android.view.KeyEvent.ACTION_DOWN && e.getRepeatCount() > 0) {
                if (e.getRepeatCount() % 3 == 0) timerChange(() -> step(k, dir, 60));
                return true;
            }
            return false;
        });
    }

    /** Switch a timer on; one never set (midnight) starts at 7 am for on, 10 pm for off. */
    private static void wake(Airbase.Timer t) {
        if (t.minutes == 0) t.minutes = t.power ? 7 * 60 : 22 * 60;
        t.enabled = true;
    }

    private void step(int k, int dir, int by) {
        Airbase.Timer t = timers[k];
        if (!t.enabled) { wake(t); return; }                      // the first press shows the time it will use
        int m = t.minutes;
        m = by == 60 ? (dir > 0 ? (m / 60 + 1) * 60 : ((m - 1) / 60) * 60) : m + dir * by;
        t.minutes = ((m % 1440) + 1440) % 1440;
    }

    /** Apply a timer change on screen now, send both slots once the presses stop. */
    private void timerChange(Runnable r) {
        if (timers == null || SystemClock.uptimeMillis() - openedAt < ARM_MS) return;
        r.run();
        timersTouchedAt = SystemClock.uptimeMillis();
        renderTimers();
        ui.removeCallbacks(sendTimers);
        ui.postDelayed(sendTimers, SEND_DELAY_MS + 300);
    }

    private final Runnable sendTimers = new Runnable() {
        @Override public void run() {
            if (timers == null) return;
            final Airbase.Timer[] t = {timers[0].copy(), timers[1].copy()};
            worker.execute(() -> {
                boolean ok = ac.setTimers(t);
                ui.post(() -> {
                    timersTouchedAt = SystemClock.uptimeMillis();
                    if (!ok) { state.setText("Couldn't set the timer"); state.setTextColor(GREY); }
                });
            });
        }
    };

    private static String clock(int minutes) {
        int h = minutes / 60, m = minutes % 60;
        return (h % 12 == 0 ? 12 : h % 12) + ":" + (m < 10 ? "0" : "") + m + (h < 12 ? " am" : " pm");
    }

    private void renderTimers() {
        timerBox.setVisibility(timers != null ? VISIBLE : GONE);
        if (timers == null) return;
        for (int i = 0; i < 2; i++) {
            Airbase.Timer t = timers[i];
            timerSwitch[i].setText(t.power ? "Turn on" : "Turn off");
            timerSwitch[i].setTag(t.enabled);
            style(timerSwitch[i]);
            timerTime[i].setText(t.enabled ? clock(t.minutes) : "Not set");
            timerTime[i].setAlpha(t.enabled ? 1f : 0.5f);
            timerClear[i].setVisibility(t.enabled ? VISIBLE : INVISIBLE);
        }
    }

    private void toggleZone(int i) {
        if (st == null || st.zoneNames == null) return;
        st.zoneOn[i] = !st.zoneOn[i];
        touchedAt = SystemClock.uptimeMillis();
        render();
        final String[] n = st.zoneNames.clone();
        final boolean[] on = st.zoneOn.clone();
        worker.execute(() -> ac.setZones(n, on));
    }

    private int modeColor() {
        if (st == null || !st.power) return WHITE;
        return st.mode == 1 ? ORANGE : st.mode == 2 ? BLUE : st.mode == 7 ? TEAL : WHITE;
    }

    private void render() {
        boolean known = st != null;
        if (known) {
            String doing = !st.power ? "Off" : st.mode == 1 ? "Heating" : st.mode == 2 ? "Cooling" : st.mode == 7 ? "Drying" : st.mode == 3 ? "Auto" : "Fan only";
            state.setText(doing);
            state.setTextColor(st.power ? modeColor() : GREY);
            temp.setText(st.mode == 0 ? "—" : st.temp + "°");
            temp.setAlpha(st.power ? 1f : 0.45f);
            inside.setText(st.inside != null ? st.inside + "°" : "");
            outside.setText(st.outside != null ? "Inside  ·  Outside " + st.outside + "°" : "Inside");
            power.setText(st.power ? "Turn off" : "Turn on");
            art.set(st.power, modeColor(), st.fanAuto ? 3 : st.fan);
        } else {
            state.setText("Checking…");
            state.setTextColor(GREY);
            temp.setText("--°");
            power.setText("Turn on");
            inside.setText("");
            outside.setText("");
            art.set(false, WHITE, 1);
        }
        for (int i = 0; i < MODES.length; i++) {
            modeChips[i].setTag(known && st.mode == MODES[i]);
            if (MODES[i] == 7) modeChips[i].setVisibility(!known || st.hasDry ? VISIBLE : GONE);
            style(modeChips[i]);
        }
        for (int i = 0; i < FANS.length; i++) {
            fanChips[i].setTag(known && (FANS[i] == 0 ? st.fanAuto : !st.fanAuto && st.fan == FANS[i]));
            if (FANS[i] == 0) fanChips[i].setVisibility(!known || st.hasFanAuto ? VISIBLE : GONE);
            style(fanChips[i]);
        }
        minus.setAlpha(known && st.mode != 0 ? 1f : 0.35f);
        plus.setAlpha(known && st.mode != 0 ? 1f : 0.35f);
        style(power);
        renderZones();
        renderTimers();
    }

    private void renderZones() {
        boolean has = st != null && st.zoneNames != null;
        zoneRow.setVisibility(has ? VISIBLE : GONE);
        if (!has) return;
        int named = 0;
        for (String n : st.zoneNames) if (!n.equals("-")) named++;
        if (zoneRow.getChildCount() != named) {
            zoneRow.removeAllViews();
            zoneIndex.clear();
            for (int i = 0; i < st.zoneNames.length; i++) {
                if (st.zoneNames[i].equals("-")) continue;
                final int k = i;
                String n = st.zoneNames[i];
                TextView b = button(getContext(), n, 13);
                b.setEllipsize(android.text.TextUtils.TruncateAt.END);
                b.setOnClickListener(v -> toggleZone(k));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
                if (zoneRow.getChildCount() > 0) lp.setMarginStart(dp(6));
                zoneIndex.add(k);
                zoneRow.addView(b, lp);
            }
        }
        for (int j = 0; j < zoneRow.getChildCount(); j++) {
            TextView b = (TextView) zoneRow.getChildAt(j);
            b.setTag(st.zoneOn[zoneIndex.get(j)]);
            style(b);
        }
    }

    // ------------------------------------------------------------ picture

    /** A ceiling vent; while running, air streams down from it in the mode's colour, faster with more fan. */
    private final class AirArt extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] seed = new float[46];
        private boolean on, running;
        private int color = WHITE, speed = 1;
        private float flow;                                       // 0 stopped .. 1 full, eases in and out
        private long last;

        AirArt(Context c) {
            super(c);
            java.util.Random r = new java.util.Random(7);
            for (int i = 0; i < seed.length; i++) seed[i] = r.nextFloat();
        }

        void set(boolean on, int color, int fan) {
            this.on = on;
            this.color = color;
            this.speed = Math.max(1, fan);
            invalidate();
        }

        void start() { running = true; last = 0; invalidate(); }

        void stop() { running = false; }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 ? 0 : Math.min(0.1f, (now - last) / 1000f);
            last = now;
            flow += ((on ? 1f : 0f) - flow) * Math.min(1f, dt * 1.6f);

            // room: dark, tinted towards the mode colour while running
            int tint = (color & 0x00FFFFFF) | ((int) (0x38 * flow) << 24);
            p.setShader(new LinearGradient(0, 0, 0, h, 0xFF1A1D24, 0xFF0A0B0E, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(new LinearGradient(0, 0, 0, h * 0.9f, tint, tint & 0x00FFFFFF, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);

            // ceiling and vent
            float vw = w * 0.56f, vh = h * 0.075f, vx = (w - vw) / 2, vy = h * 0.11f;
            p.setColor(0xFF2A2D35);
            c.drawRect(0, 0, w, vy, p);
            p.setColor(0x22FFFFFF);
            c.drawRect(0, vy - dp(1), w, vy, p);
            RectF vent = new RectF(vx, vy - vh * 0.35f, vx + vw, vy + vh);
            p.setColor(0xFFD1D1D6);
            c.drawRoundRect(vent, dp(6), dp(6), p);
            p.setColor(0xFF3A3A3F);
            RectF inner = new RectF(vent.left + dp(8), vent.top + dp(6), vent.right - dp(8), vent.bottom - dp(6));
            c.drawRoundRect(inner, dp(3), dp(3), p);
            int slats = 9;
            p.setStrokeWidth(dp(2));
            for (int i = 1; i < slats; i++) {
                float x = inner.left + inner.width() * i / slats;
                p.setColor(0xFFB0B0B5);
                c.drawLine(x, inner.top, x - dp(4) * flow, inner.bottom, p);
            }

            // air streams
            if (flow > 0.01f) {
                float travel = h - vent.bottom - dp(20);
                float sp = 0.07f + 0.05f * speed;                 // fraction of the fall per second
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
                android.graphics.Path wisp = new android.graphics.Path();
                for (int i = 0; i < 18; i++) {
                    float x0 = inner.left + inner.width() * ((i + 0.5f) / 18);
                    float spread = (x0 - w / 2) / (vw / 2);           // fans outwards from the vent
                    float head = ((now / 1000f) * sp * (0.85f + seed[i] * 0.3f) + seed[i + 18]) % 1f;
                    float tail = Math.max(0f, head - 0.22f);
                    // a wavy ribbon from tail to head, faded at both ends of its fall
                    wisp.reset();
                    for (int k = 0; k <= 8; k++) {
                        float t = tail + (head - tail) * k / 8f;
                        float y = vent.bottom + t * travel;
                        float x = x0 + spread * t * w * 0.2f + (float) Math.sin(t * 9f + seed[i] * 6.3f) * dp(14) * t;
                        if (k == 0) wisp.moveTo(x, y); else wisp.lineTo(x, y);
                    }
                    float a = (float) Math.sin(head * Math.PI) * 0.6f * flow;
                    p.setColor((color & 0x00FFFFFF) | ((int) (a * 255) << 24));
                    p.setStrokeWidth(dp(3.5f) * (1 - head * 0.4f));
                    c.drawPath(wisp, p);
                    p.setColor((color & 0x00FFFFFF) | ((int) (a * 0.25f * 255) << 24));   // soft glow
                    p.setStrokeWidth(dp(10f) * (1 - head * 0.4f));
                    c.drawPath(wisp, p);
                }
                p.setStyle(Paint.Style.FILL);
            }
            if (running && (on || flow > 0.01f)) postInvalidateOnAnimation();
        }
    }
}
