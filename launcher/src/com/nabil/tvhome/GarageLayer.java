package com.nabil.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Garage screen: the NVR camera that watches the door (channel 1, pref gd_cam) live on the left, and a control card on
 * the right with an illustrated door that moves with the real one, its state, one Open / Close button and Cancel.
 * OK presses in the first moment after opening are ignored, so a double press on the dock icon can't move the door.
 */
class GarageLayer extends FrameLayout {
    private static final int GREEN = 0xFF30D158, ORANGE = 0xFFFF9F0A, RED = 0xFFFF453A, GREY = 0x99FFFFFF;
    private static final long ARM_MS = 700, MOVE_MS = 30_000, TRAVEL_MS = 14_000;

    private final Handler ui;
    private final float dp;
    private final GarageDoor door;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final TextView title, state, action, cancel, camLabel, camStatus;
    private final View liveBadge;
    private final DoorArt art;
    private final TextureView video;
    private Surface surface;
    private RtspHevcClient stream;
    private int videoW, videoH, subtype;
    private GarageDoor.State last = GarageDoor.State.UNKNOWN;
    private long openedAt, movingUntil;
    private boolean target;                                   // where the door is heading while moving

    GarageLayer(Context c, Handler ui, GarageDoor door, SharedPreferences prefs) {
        super(c);
        this.ui = ui;
        this.door = door;
        this.prefs = prefs;
        this.dp = c.getResources().getDisplayMetrics().density;
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF151820, 0xFF060709}));
        setVisibility(GONE);
        setClickable(true);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(40), dp(32), dp(40), dp(32));
        addView(row, new LayoutParams(-1, -2, Gravity.CENTER));      // as tall as the 16:9 camera; the card matches it

        // ---- camera
        FrameLayout cam = new FrameLayout(c) {               // always 16:9, as tall as its width allows
            @Override protected void onMeasure(int ws, int hs) {
                int w = MeasureSpec.getSize(ws);
                super.onMeasure(ws, MeasureSpec.makeMeasureSpec(w * 9 / 16, MeasureSpec.EXACTLY));
            }
        };
        cam.setBackgroundColor(0xFF0E0F13);
        cam.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(22)); }
        });
        cam.setClipToOutline(true);
        cam.setElevation(dp(12));
        video = new TextureView(c);
        video.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                surface = new Surface(st);
                if (isOpen()) startStream();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { fit(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                stopStream();
                if (surface != null) { surface.release(); surface = null; }
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        cam.addView(video, new LayoutParams(-1, -1));
        View scrim = new View(c);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xB3000000, 0x00000000}));
        cam.addView(scrim, new LayoutParams(-1, dp(90), Gravity.BOTTOM));
        camLabel = text(c, 18, Color.WHITE, Typeface.NORMAL);
        camLabel.setShadowLayer(dp(4), 0, dp(1), Color.BLACK);
        LayoutParams lp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lp.setMargins(dp(22), 0, 0, dp(18));
        cam.addView(camLabel, lp);
        liveBadge = badge(c);
        LayoutParams bp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        bp.setMargins(dp(16), dp(16), 0, 0);
        cam.addView(liveBadge, bp);
        camStatus = text(c, 16, GREY, Typeface.NORMAL);
        cam.addView(camStatus, new LayoutParams(-2, -2, Gravity.CENTER));
        row.addView(cam, new LinearLayout.LayoutParams(0, -2, 1f));

        // ---- control card
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(26), dp(24), dp(26), dp(24));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE61C1C1E);
        bg.setCornerRadius(dp(22));
        bg.setStroke(Math.max(1, dp(0.5f)), 0x33FFFFFF);
        card.setBackground(bg);
        card.setElevation(dp(12));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(dp(270), -1);         // as tall as the camera
        cardLp.setMarginStart(dp(28));
        row.addView(card, cardLp);

        title = text(c, 20, GREY, Typeface.NORMAL);
        card.addView(title);
        art = new DoorArt(c);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-1, 0, 1f);
        ap.setMargins(dp(10), dp(12), dp(10), dp(6));
        card.addView(art, ap);
        state = text(c, 32, Color.WHITE, Typeface.NORMAL);
        state.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.setMargins(0, 0, 0, dp(16));
        card.addView(state, sp);

        action = button(c, true);
        action.setOnClickListener(v -> press());
        card.addView(action, new LinearLayout.LayoutParams(-1, dp(52)));
        cancel = button(c, false);
        cancel.setText("Close");
        cancel.setOnClickListener(v -> close());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, dp(42));
        cp.topMargin = dp(10);
        card.addView(cancel, cp);
    }

    private int dp(float v) { return Math.round(v * dp); }

    /** Keep the remote's focus on this screen's two buttons (the dock underneath stays focusable). */
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
            b.animate().scaleX(has ? 1.03f : 1f).scaleY(has ? 1.03f : 1f).setDuration(120).start();
        });
        return b;
    }

    /** Red dot + LIVE on a dark pill. */
    private View badge(Context c) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(10), dp(4), dp(12), dp(4));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x99000000);
        pill.setCornerRadius(dp(100));
        b.setBackground(pill);
        View dot = new View(c);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(RED);
        dot.setBackground(d);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(8), dp(8));
        dl.setMarginEnd(dp(7));
        b.addView(dot, dl);
        TextView t = text(c, 12, Color.WHITE, Typeface.BOLD);
        t.setText("LIVE");
        t.setLetterSpacing(0.12f);
        b.addView(t);
        b.setVisibility(GONE);
        return b;
    }

    // ------------------------------------------------------------ open / close

    boolean isOpen() { return getVisibility() == VISIBLE; }

    void open() {
        openedAt = SystemClock.uptimeMillis();
        title.setText(door.name());
        camLabel.setText("Channel " + prefs.getInt("gd_cam", 1));
        render();
        setAlpha(0f);
        setVisibility(VISIBLE);
        animate().alpha(1f).setDuration(180).start();
        action.requestFocus();
        subtype = 0;
        startStream();
        ui.removeCallbacks(poll);
        ui.post(poll);
    }

    void close() {
        ui.removeCallbacks(poll);
        stopStream();
        setVisibility(GONE);
    }

    // ------------------------------------------------------------ camera

    private void startStream() {
        String host = prefs.getString("nvr_host", null);
        if (surface == null || stream != null) return;
        if (host == null) { camStatus.setText("No NVR set up"); return; }
        camStatus.setText("Connecting…");
        liveBadge.setVisibility(GONE);
        final int sub = subtype;
        stream = new RtspHevcClient(host, prefs.getString("nvr_user", ""), prefs.getString("nvr_pass", ""),
                prefs.getInt("gd_cam", 1), sub, new RtspHevcClient.Listener() {
            @Override public void onVideoSize(int w, int h) { ui.post(() -> { videoW = w; videoH = h; fit(); }); }
            @Override public void onFirstFrame() { ui.post(() -> { camStatus.setText(""); liveBadge.setVisibility(VISIBLE); }); }
            @Override public void onError(String m) {
                ui.post(() -> {
                    if (!isOpen()) return;
                    stopStream();
                    if (sub == 0) { subtype = 1; startStream(); }       // main stream unavailable: use the sub-stream
                    else camStatus.setText("Camera unavailable");
                });
            }
        });
        stream.start(surface);
    }

    private void stopStream() {
        final RtspHevcClient s = stream;
        stream = null;
        if (s != null) worker.execute(s::stop);
    }

    /** Fill the rounded frame, cropping the video's edges rather than letterboxing. */
    private void fit() {
        int tw = video.getWidth(), th = video.getHeight();
        if (tw == 0 || th == 0 || videoW == 0 || videoH == 0) return;
        float s = Math.max((float) tw / videoW, (float) th / videoH);
        Matrix m = new Matrix();
        m.setScale(videoW * s / tw, videoH * s / th, tw / 2f, th / 2f);
        video.setTransform(m);
    }

    // ------------------------------------------------------------ door

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!isOpen()) return;
            worker.execute(() -> {
                GarageDoor.State s = door.state();
                ui.post(() -> {
                    boolean first = last == GarageDoor.State.UNKNOWN;
                    last = s;
                    if (moving() && s == (target ? GarageDoor.State.OPEN : GarageDoor.State.CLOSED)) movingUntil = 0;
                    if (!moving() && s != GarageDoor.State.UNKNOWN) art.moveTo(s == GarageDoor.State.OPEN ? 1f : 0f, first ? 0 : 600);
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
        action.setAlpha(known || moving() ? 1f : 0.4f);
    }

    private void press() {
        if (SystemClock.uptimeMillis() - openedAt < ARM_MS) return;       // ignore the press that opened the screen
        if (last == GarageDoor.State.UNKNOWN && !moving()) return;
        final boolean open = moving() ? !target : last != GarageDoor.State.OPEN;
        target = open;
        movingUntil = SystemClock.uptimeMillis() + MOVE_MS;
        art.moveTo(open ? 1f : 0f, TRAVEL_MS);
        render();
        worker.execute(() -> {
            boolean ok = door.set(open);
            ui.post(() -> {
                if (!ok) {
                    movingUntil = 0;
                    art.moveTo(last == GarageDoor.State.OPEN ? 1f : 0f, 400);
                    state.setText("Couldn't reach the opener");
                    state.setTextColor(GREY);
                }
                ui.removeCallbacks(poll);
                ui.postDelayed(poll, 1500);
            });
        });
    }

    /** A garage front whose ribbed door rolls up (1) and down (0). */
    private final class DoorArt extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float pos;                                    // 0 closed .. 1 open
        private ValueAnimator anim;

        DoorArt(Context c) { super(c); }

        void moveTo(float to, long ms) {
            if (anim != null) anim.cancel();
            if (ms <= 0) { pos = to; invalidate(); return; }
            anim = ValueAnimator.ofFloat(pos, to);
            anim.setDuration((long) (ms * Math.abs(to - pos)) + 1);
            anim.setInterpolator(new LinearInterpolator());
            anim.addUpdateListener(a -> { pos = (float) a.getAnimatedValue(); invalidate(); });
            anim.start();
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), s = Math.min(w, h * 1.15f);
            float x0 = (w - s) / 2, y0 = (h - s * 0.87f) / 2;
            // house: roof + walls
            android.graphics.Path house = new android.graphics.Path();
            house.moveTo(x0 + s * 0.5f, y0);
            house.lineTo(x0 + s, y0 + s * 0.30f);
            house.lineTo(x0 + s, y0 + s * 0.87f);
            house.lineTo(x0, y0 + s * 0.87f);
            house.lineTo(x0, y0 + s * 0.30f);
            house.close();
            p.setShader(new LinearGradient(0, y0, 0, y0 + s * 0.87f, 0xFF3A3D46, 0xFF26282F, Shader.TileMode.CLAMP));
            c.drawPath(house, p);
            p.setShader(null);
            // opening: warm light inside, growing as the door rises
            RectF open = new RectF(x0 + s * 0.14f, y0 + s * 0.40f, x0 + s * 0.86f, y0 + s * 0.87f);
            p.setShader(new LinearGradient(0, open.top, 0, open.bottom, 0xFFFFC56B, 0xFF7A4A12, Shader.TileMode.CLAMP));
            c.drawRect(open, p);
            p.setShader(null);
            // door: slats from the top of the opening down to (1 - pos) of its height
            float doorBottom = open.top + (open.height()) * (1 - pos);
            c.save();
            c.clipRect(open.left, open.top, open.right, doorBottom);
            int slats = 5;
            float sh = open.height() / slats;
            for (int i = 0; i < slats; i++) {
                float top = doorBottom - (slats - i) * sh;
                p.setColor(i % 2 == 0 ? 0xFFE9EAEE : 0xFFD7D9DF);
                c.drawRect(open.left, top, open.right, top + sh, p);
                p.setColor(0x33000000);
                c.drawRect(open.left, top + sh - s * 0.008f, open.right, top + sh, p);
            }
            c.restore();
            // trim
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(s * 0.025f);
            p.setColor(0xFF4A4E59);
            c.drawRect(open, p);
            p.setStyle(Paint.Style.FILL);
        }
    }
}
