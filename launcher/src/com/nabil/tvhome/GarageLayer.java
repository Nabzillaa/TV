package com.nabil.tvhome;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Garage door panel: the door's state (polled while open) and one Open / Close button, plus Cancel. OK presses in the
 * first moment after the panel opens are ignored, so a double press on the dock icon can't move the door.
 */
class GarageLayer extends FrameLayout {
    private static final int GREEN = 0xFF30D158, ORANGE = 0xFFFF9F0A, GREY = 0x99FFFFFF;
    private static final long ARM_MS = 700, MOVE_MS = 30_000;

    private final Handler ui;
    private final float dp;
    private final GarageDoor door;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final TextView title, state, action, cancel;
    private GarageDoor.State last = GarageDoor.State.UNKNOWN;
    private long openedAt, movingUntil;
    private boolean target;                                   // where the door is heading while moving

    GarageLayer(Context c, Handler ui, GarageDoor door) {
        super(c);
        this.ui = ui;
        this.door = door;
        this.dp = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(0xB3000000);
        setVisibility(GONE);
        setClickable(true);

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(40), dp(32), dp(40), dp(32));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF21C1C1E);
        bg.setCornerRadius(dp(24));
        bg.setStroke(Math.max(1, dp(0.5f)), 0x33FFFFFF);
        card.setBackground(bg);
        addView(card, new LayoutParams(dp(440), LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        title = text(c, 18, GREY, Typeface.NORMAL);
        card.addView(title);
        state = text(c, 40, Color.WHITE, Typeface.NORMAL);
        state.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.setMargins(0, dp(6), 0, dp(28));
        card.addView(state, sp);

        action = button(c, true);
        action.setOnClickListener(v -> press());
        card.addView(action, new LinearLayout.LayoutParams(-1, dp(56)));
        cancel = button(c, false);
        cancel.setText("Cancel");
        cancel.setOnClickListener(v -> close());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(48));
        cp.topMargin = dp(12);
        card.addView(cancel, cp);
    }

    private int dp(float v) { return Math.round(v * dp); }

    /** Keep the remote's focus on the panel's two buttons (the dock underneath stays focusable). */
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
        return t;
    }

    private TextView button(Context c, boolean primary) {
        TextView b = text(c, primary ? 20 : 16, Color.WHITE, primary ? Typeface.BOLD : Typeface.NORMAL);
        b.setFocusable(true);
        GradientDrawable idle = new GradientDrawable(), focus = new GradientDrawable();
        idle.setCornerRadius(dp(14));
        focus.setCornerRadius(dp(14));
        idle.setColor(primary ? 0x33FFFFFF : 0x1AFFFFFF);
        focus.setColor(0xFFFFFFFF);
        b.setBackground(idle);
        b.setOnFocusChangeListener((v, has) -> {
            b.setBackground(has ? focus : idle);
            b.setTextColor(has ? Color.BLACK : Color.WHITE);
        });
        return b;
    }

    boolean isOpen() { return getVisibility() == VISIBLE; }

    void open() {
        openedAt = SystemClock.uptimeMillis();
        title.setText(door.name());
        render();
        setAlpha(0f);
        setVisibility(VISIBLE);
        animate().alpha(1f).setDuration(150).start();
        action.requestFocus();
        ui.removeCallbacks(poll);
        ui.post(poll);
    }

    void close() {
        ui.removeCallbacks(poll);
        setVisibility(GONE);
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!isOpen()) return;
            worker.execute(() -> {
                GarageDoor.State s = door.state();
                ui.post(() -> {
                    last = s;
                    if (moving() && s == (target ? GarageDoor.State.OPEN : GarageDoor.State.CLOSED)) movingUntil = 0;
                    render();
                });
            });
            ui.postDelayed(this, moving() ? 1500 : 4000);
        }
    };

    private boolean moving() { return SystemClock.uptimeMillis() < movingUntil; }

    private void render() {
        boolean known = last != GarageDoor.State.UNKNOWN;
        if (moving()) {
            state.setText(target ? "Opening…" : "Closing…");
            state.setTextColor(ORANGE);
        } else {
            state.setText(last == GarageDoor.State.OPEN ? "Open" : last == GarageDoor.State.CLOSED ? "Closed" : "Checking…");
            state.setTextColor(last == GarageDoor.State.OPEN ? ORANGE : last == GarageDoor.State.CLOSED ? GREEN : GREY);
        }
        // the button always offers the opposite of where the door is (or is heading)
        boolean openNext = moving() ? !target : last != GarageDoor.State.OPEN;
        action.setText(openNext ? "Open garage" : "Close garage");
        action.setEnabled(known || moving());
        action.setAlpha(known || moving() ? 1f : 0.4f);
    }

    private void press() {
        if (SystemClock.uptimeMillis() - openedAt < ARM_MS) return;       // ignore the press that opened the panel
        if (last == GarageDoor.State.UNKNOWN && !moving()) return;
        final boolean open = moving() ? !target : last != GarageDoor.State.OPEN;
        target = open;
        movingUntil = SystemClock.uptimeMillis() + MOVE_MS;
        render();
        worker.execute(() -> {
            boolean ok = door.set(open);
            ui.post(() -> {
                if (!ok) {
                    movingUntil = 0;
                    state.setText("Couldn't reach the opener");
                    state.setTextColor(GREY);
                }
                ui.removeCallbacks(poll);
                ui.postDelayed(poll, 1500);
            });
        });
    }
}
