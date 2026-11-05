package com.nabil.tvhome;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home hub: Cameras, Garage, Solar and Aircon behind one dock tile, with the everyday controls right on the cards.
 * - Cameras: each picture is selectable; OK opens that camera full screen (Back comes straight back here). The selected
 *   picture refreshes faster than the rest.
 * - Garage: the door's camera live, Open/Close (a second press within 3 s confirms, so a stray
 *   press can't move the door) and a camera button for the full Garage screen (which has Stop).
 * - Aircon: power, - / + and mode on the card; changes show at once and are sent when the presses stop, like the
 *   Aircon screen. More opens that screen.
 * - Solar: an animated flow of power between the panels, the house, the battery and the grid. OK opens the Solar screen.
 * Polling stops while one of the screens is open, so the hub never competes with it (the Airbase in particular locks
 * everyone out when asked too often). A pill at the bottom confirms each action.
 */
class HubLayer extends FrameLayout {
    private static final int GREEN = 0xFF30D158, ORANGE = 0xFFFF9F0A, BLUE = 0xFF0A84FF,
            YELLOW = 0xFFFFD60A, ICE = 0xFF64D2FF, TEAL = 0xFF66D4CF, PURPLE = 0xFFBF5AF2, GREY = 0x99FFFFFF;
    private static final long GARAGE_MS = 5_000, GARAGE_MOVING_MS = 1_500, AIRCON_MS = 30_000, SOLAR_MS = 60_000,
            SNAP_MS = 1_500, ARM_MS = 3_000, TRAVEL_MS = 14_000, SEND_DELAY_MS = 900, HOLD_MS = 8_000;
    private static final int R_TINT = 0x7f0b0001;            // a private view-tag key for a chip's tint

    private final Handler ui;
    private final float dp;
    private final SharedPreferences prefs;
    private final CameraLayer cameras;
    private final GarageDoor door;
    private final GarageLayer garageLayer;
    private final SolisCloud solis;
    private final SolarLayer solarLayer;
    private final Airbase aircon;
    private final AirconLayer airconLayer;
    private final Bitmap camIcon, garageIcon, solarIcon, airconIcon;
    private final ExecutorService net = Executors.newFixedThreadPool(4);
    private final TextView hello, summary, toast;
    private final LinearLayout grid;
    private final List<Card> cards = new ArrayList<>();
    private Card camCard, garageCard, solarCard, airconCard;
    private String focusKey;                                  // the focused control, kept across rebuilds and screens

    // latest readings (null: not read yet or unreachable)
    private GarageDoor.State garageState;
    private SolisCloud.Reading solar;
    private Airbase.Status ac;
    private boolean acRead;
    private long garageDue, airconDue, solarDue, snapDue;
    private boolean garageBusy, airconBusy, solarBusy, snapBusy, childWasOpen;
    private int gen;

    // cameras
    private NvrClient nvr;
    private ImageView[] snaps = new ImageView[0];
    private int snapNext, snapTries, snapFocus = -1;
    private boolean snapTurn;

    // garage
    private TextureView garageVideo;
    private android.view.Surface garageSurface;
    private RtspHevcClient garageStream;
    private TextView garageCamStatus;
    private View garageLive;
    private int garageVideoW, garageVideoH;
    private TextView garageAction;
    private long armedUntil, movingUntil;
    private boolean movingOpen;

    // aircon
    private TextView acPower, acMode, acTemp;
    private long acTouchedAt;

    // solar
    private FlowView flow;

    HubLayer(Context c, Handler ui, SharedPreferences prefs, CameraLayer cameras, GarageDoor door, GarageLayer garageLayer,
             SolisCloud solis, SolarLayer solarLayer, Airbase aircon, AirconLayer airconLayer,
             Bitmap camIcon, Bitmap garageIcon, Bitmap solarIcon, Bitmap airconIcon) {
        super(c);
        this.ui = ui;
        this.prefs = prefs;
        this.cameras = cameras;
        this.door = door;
        this.garageLayer = garageLayer;
        this.solis = solis;
        this.solarLayer = solarLayer;
        this.aircon = aircon;
        this.airconLayer = airconLayer;
        this.camIcon = camIcon;
        this.garageIcon = garageIcon;
        this.solarIcon = solarIcon;
        this.airconIcon = airconIcon;
        this.dp = c.getResources().getDisplayMetrics().density;
        // opaque: the home screen's HUD and clock would otherwise ghost through the header
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF141826, 0xFF080A10, 0xFF0A0608}));
        setVisibility(GONE);
        setClickable(true);
        setClipChildren(false);                                // a focused card grows past its row: don't crop its border

        View glowA = glow(0x335E5CE6), glowB = glow(0x26FF9F0A);
        addView(glowA, new LayoutParams(dp(520), dp(520), Gravity.TOP | Gravity.START));
        glowA.setTranslationX(-dp(160));
        glowA.setTranslationY(-dp(200));
        addView(glowB, new LayoutParams(dp(480), dp(480), Gravity.BOTTOM | Gravity.END));
        glowB.setTranslationX(dp(160));
        glowB.setTranslationY(dp(200));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.setPadding(dp(48), dp(30), dp(48), dp(34));
        addView(col, new LayoutParams(-1, -1));

        // ---- header: title and summary on the left, the time on the right
        FrameLayout head = new FrameLayout(c);
        LinearLayout titles = new LinearLayout(c);
        titles.setOrientation(LinearLayout.VERTICAL);
        hello = text(c, 14, GREY, Typeface.NORMAL);
        hello.setLetterSpacing(0.08f);
        titles.addView(hello);
        TextView title = text(c, 34, Color.WHITE, Typeface.NORMAL);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setText("Home");
        titles.addView(title);
        summary = text(c, 15, 0xCCFFFFFF, Typeface.NORMAL);
        titles.addView(summary);
        LayoutParams tp = new LayoutParams(-1, -2, Gravity.START | Gravity.CENTER_VERTICAL);
        tp.setMarginEnd(dp(200));                              // room for the clock
        head.addView(titles, tp);
        TextClock clock = new TextClock(c);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTextSize(42);
        clock.setTextColor(Color.WHITE);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        head.addView(clock, new LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.bottomMargin = dp(18);
        col.addView(head, hp);

        grid = new LinearLayout(c);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        col.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        toast = text(c, 15, Color.WHITE, Typeface.BOLD);
        toast.setPadding(dp(20), dp(9), dp(20), dp(9));
        GradientDrawable tb = new GradientDrawable();
        tb.setColor(0xF02C2C30);
        tb.setCornerRadius(dp(100));
        tb.setStroke(Math.max(1, dp(0.5f)), 0x40FFFFFF);
        toast.setBackground(tb);
        toast.setElevation(dp(20));
        toast.setAlpha(0f);
        LayoutParams lp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(10);
        addView(toast, lp);

        // the card holding the focus lights up; remember which control it was
        getViewTreeObserver().addOnGlobalFocusChangeListener((was, now) -> {
            if (!isOpen() || now == null || !inside(now, grid)) return;
            Object k = now.getTag();
            if (k instanceof String) focusKey = (String) k;
            for (Card card : cards) card.lit(inside(now, card));
            int f = -1;
            for (int i = 0; i < snaps.length; i++) if (snaps[i] != null && inside(now, (View) snaps[i].getParent())) f = i;
            snapFocus = f;
        });
    }

    private int dp(float v) { return Math.round(v * dp); }

    private static boolean inside(View v, View group) {
        for (View p = v; p != null; p = p.getParent() instanceof View ? (View) p.getParent() : null) if (p == group) return true;
        return false;
    }

    /** Keep the remote's focus on the hub (the dock underneath stays focusable). */
    @Override public View focusSearch(View focused, int direction) {
        if (childOpen()) return super.focusSearch(focused, direction);
        View next = android.view.FocusFinder.getInstance().findNextFocus(this, focused, direction);
        return next != null ? next : focused;
    }

    // ------------------------------------------------------------ open / close

    boolean isOpen() { return getVisibility() == VISIBLE; }

    /** Is there anything to put in the hub? */
    boolean configured() { return cameras.configured() || door.configured() || solis.configured() || aircon.configured(); }

    /** One of the screens the hub opens is showing over it. */
    boolean childOpen() { return cameras.isOpen() || garageLayer.isOpen() || solarLayer.isOpen() || airconLayer.isOpen(); }

    void open() {
        gen++;
        armedUntil = 0;
        build();
        updateHello();
        render();
        setAlpha(0f);
        setVisibility(VISIBLE);
        animate().alpha(1f).setDuration(200).start();
        for (int i = 0; i < cards.size(); i++) {               // cards rise in one after another
            View v = cards.get(i);
            v.setAlpha(0f);
            v.setTranslationY(dp(26));
            v.animate().alpha(1f).translationY(0).setStartDelay(60L * i).setDuration(320)
                    .setInterpolator(new DecelerateInterpolator(2f)).withEndAction(() -> v.animate().setStartDelay(0)).start();
        }
        restoreFocus();
        startGarageStream();
        cameras.prewarm();                                     // Cameras opens instantly from here
        garageDue = airconDue = solarDue = snapDue = 0;
        childWasOpen = false;
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    void close() {
        gen++;
        ui.removeCallbacks(tick);
        flushAircon();                                         // don't lose a change still waiting to be sent
        stopGarageStream();
        setVisibility(GONE);
    }

    /** A screen opened from the hub closed: put the focus back where it was and refresh now. */
    void childClosed() {
        if (!isOpen()) return;
        childWasOpen = false;
        restoreFocus();
        startGarageStream();
        garageDue = airconDue = solarDue = snapDue = 0;
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    private void restoreFocus() {
        View v = focusKey != null ? grid.findViewWithTag(focusKey) : null;
        if (v == null) {
            ArrayList<View> all = new ArrayList<>();
            grid.addFocusables(all, FOCUS_FORWARD);
            if (!all.isEmpty()) v = all.get(0);
        }
        if (v != null) v.requestFocus();
    }

    // ------------------------------------------------------------ building the cards

    private void build() {
        grid.removeAllViews();
        cards.clear();
        Context c = getContext();
        camCard = cameras.configured() ? buildCameras(c) : null;
        solarCard = solis.configured() ? buildSolar(c) : null;
        garageCard = door.configured() ? buildGarage(c) : null;
        airconCard = aircon.configured() ? buildAircon(c) : null;
        if (camCard == null) { snaps = new ImageView[0]; nvr = null; }
        for (Card k : new Card[]{camCard, solarCard, garageCard, airconCard}) if (k != null) cards.add(k);
        for (int r = 0; r < (cards.size() + 1) / 2; r++) {
            LinearLayout row = new LinearLayout(c);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            for (int i = r * 2; i < r * 2 + 2; i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
                if (i % 2 == 1) lp.setMarginStart(dp(22));
                row.addView(i < cards.size() ? cards.get(i) : new View(c), lp);
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, 0, 1f);
            if (r > 0) rp.topMargin = dp(22);
            grid.addView(row, rp);
        }
        if (cards.size() <= 2) {                               // one row: keep the cards card-shaped, not stretched
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, 0, 1f);
            rp.topMargin = dp(22);
            grid.addView(new View(c), rp);
        }
    }

    /** Four selectable pictures; the card's name floats over them. */
    private Card buildCameras(Context c) {
        Card card = new Card(c, BLUE, false);
        int n = Math.max(1, Math.min(4, prefs.getInt("nvr_n", 4)));
        nvr = new NvrClient(prefs.getString("nvr_host", ""), prefs.getString("nvr_user", ""), prefs.getString("nvr_pass", ""));
        snaps = new ImageView[n];
        snapNext = snapTries = 0;
        snapFocus = -1;
        LinearLayout mosaic = new LinearLayout(c);
        mosaic.setOrientation(LinearLayout.VERTICAL);
        mosaic.setPadding(dp(6), dp(6), dp(6), dp(6));
        mosaic.setClipChildren(false);
        mosaic.setClipToPadding(false);
        int perRow = n == 1 ? 1 : 2;
        for (int r = 0; r < (n + perRow - 1) / perRow; r++) {
            LinearLayout row = new LinearLayout(c);
            row.setClipChildren(false);
            for (int i = r * perRow; i < Math.min(n, r * perRow + perRow); i++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
                if (i % perRow == 1) lp.setMarginStart(dp(6));
                row.addView(cameraTile(c, i), lp);
            }
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, 0, 1f);
            if (r > 0) rp.topMargin = dp(6);
            mosaic.addView(row, rp);
        }
        card.addView(mosaic, new LayoutParams(-1, -1));
        LinearLayout name = iconName(c, camIcon, "Cameras");
        name.setPadding(dp(8), dp(5), dp(14), dp(5));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xB3000000);
        pill.setCornerRadius(dp(100));
        name.setBackground(pill);
        name.setElevation(dp(16));                             // over a grown, focused picture
        LayoutParams np = new LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        np.setMargins(dp(16), dp(14), 0, 0);
        card.addView(name, np);
        card.addRing();
        return card;
    }

    private View cameraTile(Context c, int i) {
        FrameLayout t = new FrameLayout(c);
        t.setFocusable(true);
        t.setTag("cam:" + i);
        t.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(16)); }
        });
        t.setClipToOutline(true);
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(0xFF0E0F13);
        snaps[i] = iv;
        t.addView(iv, new LayoutParams(-1, -1));
        View scrim = new View(c);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xB3000000, 0x00000000}));
        t.addView(scrim, new LayoutParams(-1, dp(40), Gravity.BOTTOM));
        TextView label = text(c, 12, Color.WHITE, Typeface.BOLD);
        label.setText("Channel " + (i + 1));
        LayoutParams lp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lp.setMargins(dp(10), 0, 0, dp(7));
        t.addView(label, lp);
        TextView play = text(c, 11, Color.BLACK, Typeface.BOLD);
        play.setText("▶  Watch live");
        play.setPadding(dp(9), dp(3), dp(9), dp(3));
        GradientDrawable pb = new GradientDrawable();
        pb.setColor(Color.WHITE);
        pb.setCornerRadius(dp(100));
        play.setBackground(pb);
        play.setAlpha(0f);
        LayoutParams pp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        pp.setMargins(0, 0, dp(8), dp(6));
        t.addView(play, pp);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(16));
        ring.setColor(0x00000000);
        View rv = new View(c);
        rv.setBackground(ring);
        t.addView(rv, new LayoutParams(-1, -1));
        t.setOnFocusChangeListener((v, has) -> {
            ring.setStroke(has ? dp(3) : 0, Color.WHITE);
            play.animate().alpha(has ? 1f : 0f).setDuration(150).start();
            v.animate().scaleX(has ? 1.07f : 1f).scaleY(has ? 1.07f : 1f).setDuration(200)
                    .setInterpolator(new OvershootInterpolator(has ? 2f : 0f)).start();
            v.setElevation(dp(has ? 12 : 0));
            if (has) snapDue = 0;                              // the picture you're looking at refreshes next
        });
        t.setOnClickListener(v -> { bounce(v); cameras.openChannel(i + 1); });
        return t;
    }

    /** The numbers on the left, the live energy flow on the right; the whole card opens the Solar screen. */
    private Card buildSolar(Context c) {
        Card card = new Card(c, YELLOW, true);
        card.setTag("solar");
        LinearLayout row = new LinearLayout(c);
        row.setPadding(dp(22), dp(18), dp(16), dp(16));
        card.addView(row, new LayoutParams(-1, -1));
        row.addView(infoColumn(c, card, solarIcon, "Solar"), new LinearLayout.LayoutParams(0, -1, 1f));
        flow = new FlowView(c);
        row.addView(flow, new LinearLayout.LayoutParams(dp(190), -1));
        if (solar != null) flow.set(solar);
        card.addRing();
        card.setOnClickListener(v -> { bounce(v); solarLayer.open(); });
        return card;
    }

    /** State on the left; the door and its buttons on the right. */
    private Card buildGarage(Context c) {
        Card card = new Card(c, ORANGE, false);
        LinearLayout row = new LinearLayout(c);
        row.setPadding(dp(22), dp(18), dp(16), dp(16));
        row.setClipChildren(false);
        card.addView(row, new LayoutParams(-1, -1));
        row.addView(infoColumn(c, card, garageIcon, door.name()), new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setClipChildren(false);
        right.addView(garageCamera(c), new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout btns = new LinearLayout(c);
        btns.setClipChildren(false);
        garageAction = chip(c, "Open", "garage:go");
        garageAction.setOnClickListener(v -> garagePress());
        btns.addView(garageAction, new LinearLayout.LayoutParams(0, dp(34), 1f));
        TextView cam = chip(c, "Camera ›", "garage:cam");
        cam.setOnClickListener(v -> { bounce(v); garageLayer.open(); });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, dp(34));
        cp.setMarginStart(dp(8));
        btns.addView(cam, cp);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = dp(8);
        right.addView(btns, bp);
        row.addView(right, new LinearLayout.LayoutParams(dp(200), -1));
        card.addRing();
        return card;
    }

    /** The NVR camera that watches the door (pref gd_cam, as on the Garage screen), live, cropped to fill. */
    private View garageCamera(Context c) {
        FrameLayout cam = new FrameLayout(c);
        cam.setBackgroundColor(0xFF0E0F13);
        cam.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14)); }
        });
        cam.setClipToOutline(true);
        garageVideo = new TextureView(c);
        garageVideo.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                garageSurface = new android.view.Surface(st);
                if (isOpen() && !childOpen()) startGarageStream();
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { fitGarage(); }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                stopGarageStream();
                if (garageSurface != null) { garageSurface.release(); garageSurface = null; }
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        cam.addView(garageVideo, new LayoutParams(-1, -1));
        garageCamStatus = text(c, 12, GREY, Typeface.NORMAL);
        cam.addView(garageCamStatus, new LayoutParams(-2, -2, Gravity.CENTER));
        garageLive = liveBadge(c);
        LayoutParams bp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        bp.setMargins(dp(7), dp(7), 0, 0);
        cam.addView(garageLive, bp);
        return cam;
    }

    /** Red dot + LIVE on a dark pill. */
    private View liveBadge(Context c) {
        LinearLayout b = new LinearLayout(c);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(7), dp(2), dp(8), dp(2));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x99000000);
        pill.setCornerRadius(dp(100));
        b.setBackground(pill);
        View dot = new View(c);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(0xFFFF453A);
        dot.setBackground(d);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(6), dp(6));
        dl.setMarginEnd(dp(5));
        b.addView(dot, dl);
        TextView t = text(c, 9, Color.WHITE, Typeface.BOLD);
        t.setText("LIVE");
        t.setLetterSpacing(0.12f);
        b.addView(t);
        b.setVisibility(GONE);
        return b;
    }

    /** The sub-stream, decoded in software: the TV's few hardware decoders stay free for the full screens. */
    private void startGarageStream() {
        if (garageCard == null || garageSurface == null || garageStream != null) return;
        String host = prefs.getString("nvr_host", null);
        if (host == null) { garageCamStatus.setText("No camera set up"); return; }
        garageCamStatus.setText("Connecting…");
        garageLive.setVisibility(GONE);
        final int g = gen;
        RtspHevcClient s = new RtspHevcClient(host, prefs.getString("nvr_user", ""), prefs.getString("nvr_pass", ""),
                prefs.getInt("gd_cam", 1), 1, new RtspHevcClient.Listener() {
            @Override public void onVideoSize(int w, int h) { ui.post(() -> { garageVideoW = w; garageVideoH = h; fitGarage(); }); }
            @Override public void onFirstFrame() { ui.post(() -> { if (g == gen) { garageCamStatus.setText(""); garageLive.setVisibility(VISIBLE); } }); }
            @Override public void onError(String m) {
                ui.post(() -> {
                    if (g != gen) return;
                    stopGarageStream();
                    garageCamStatus.setText("Camera unavailable");
                    ui.postDelayed(() -> { if (g == gen && isOpen() && !childOpen()) startGarageStream(); }, 10_000);
                });
            }
        });
        s.preferSoftware = true;
        garageStream = s;
        s.start(garageSurface);
    }

    private void stopGarageStream() {
        final RtspHevcClient s = garageStream;
        garageStream = null;
        if (garageLive != null) garageLive.setVisibility(GONE);
        if (s == null) return;
        s.detach();                                            // free the decoder now; the socket closes in the background
        net.execute(s::stop);
    }

    /** Crop the picture to fill its rounded box. */
    private void fitGarage() {
        int tw = garageVideo.getWidth(), th = garageVideo.getHeight();
        if (tw == 0 || th == 0 || garageVideoW == 0 || garageVideoH == 0) return;
        float k = Math.max((float) tw / garageVideoW, (float) th / garageVideoH);
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(garageVideoW * k / tw, garageVideoH * k / th, tw / 2f, th / 2f);
        garageVideo.setTransform(m);
    }

    /** State and temperatures on the left; - set point + over power, mode and More on the right. */
    private Card buildAircon(Context c) {
        Card card = new Card(c, ICE, false);
        LinearLayout row = new LinearLayout(c);
        row.setPadding(dp(22), dp(18), dp(16), dp(16));
        row.setClipChildren(false);
        card.addView(row, new LayoutParams(-1, -1));
        row.addView(infoColumn(c, card, airconIcon, aircon.name()), new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout right = new LinearLayout(c);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setClipChildren(false);
        LinearLayout set = new LinearLayout(c);
        set.setGravity(Gravity.CENTER);
        set.setClipChildren(false);
        TextView minus = chip(c, "−", "ac:minus");
        minus.setTextSize(22);
        minus.setPadding(0, 0, 0, 0);
        minus.setOnClickListener(v -> acNudge(-1));
        set.addView(minus, new LinearLayout.LayoutParams(dp(42), dp(42)));
        acTemp = text(c, 32, Color.WHITE, Typeface.NORMAL);
        acTemp.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        acTemp.setGravity(Gravity.CENTER);
        set.addView(acTemp, new LinearLayout.LayoutParams(dp(96), -2));
        TextView plus = chip(c, "+", "ac:plus");
        plus.setTextSize(22);
        plus.setPadding(0, 0, 0, 0);
        plus.setOnClickListener(v -> acNudge(1));
        set.addView(plus, new LinearLayout.LayoutParams(dp(42), dp(42)));
        right.addView(set, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout btns = new LinearLayout(c);
        btns.setClipChildren(false);
        acPower = chip(c, "Power", "ac:power");               // (the TV's font has no power symbol)
        acPower.setOnClickListener(v -> acChange("power"));
        btns.addView(acPower, new LinearLayout.LayoutParams(-2, dp(34)));
        acMode = chip(c, "Mode", "ac:mode");
        acMode.setOnClickListener(v -> acChange("mode"));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, dp(34), 1f);
        mp.setMarginStart(dp(8));
        btns.addView(acMode, mp);
        TextView more = chip(c, "More ›", "ac:more");
        more.setOnClickListener(v -> { bounce(v); flushAircon(); airconLayer.open(); });
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(-2, dp(34));
        op.setMarginStart(dp(8));
        btns.addView(more, op);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = dp(8);
        right.addView(btns, bp);
        row.addView(right, new LinearLayout.LayoutParams(dp(214), -1));
        card.addRing();
        return card;
    }

    /** Icon and name, then (at the bottom) the big value, a detail line and an optional battery bar. */
    private LinearLayout infoColumn(Context c, Card card, Bitmap icon, String name) {
        LinearLayout info = new LinearLayout(c);
        info.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = iconName(c, icon, name);
        card.dot = new View(c);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(GREY);
        card.dot.setBackground(d);
        card.dot.setVisibility(INVISIBLE);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(9), dp(9));
        dl.setMarginStart(dp(10));
        top.addView(card.dot, dl);
        info.addView(top);
        info.addView(new View(c), new LinearLayout.LayoutParams(1, 0, 1f));
        card.value = text(c, 34, Color.WHITE, Typeface.NORMAL);
        card.value.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        info.addView(card.value);
        card.detail = text(c, 14, 0xB3FFFFFF, Typeface.NORMAL);
        info.addView(card.detail);
        card.barBox = new FrameLayout(c);
        GradientDrawable track = new GradientDrawable();
        track.setColor(0x26FFFFFF);
        track.setCornerRadius(dp(3));
        card.barBox.setBackground(track);
        card.barFill = new View(c);
        card.barBox.addView(card.barFill, new LayoutParams(0, -1));
        card.barBox.setVisibility(GONE);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(6));
        bp.topMargin = dp(8);
        bp.setMarginEnd(dp(14));
        info.addView(card.barBox, bp);
        return info;
    }

    private LinearLayout iconName(Context c, Bitmap icon, String name) {
        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(c);
        iv.setImageBitmap(icon);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(8)); }
        });
        iv.setClipToOutline(true);
        top.addView(iv, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView label = text(c, 16, Color.WHITE, Typeface.BOLD);
        label.setText(name);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginStart(dp(10));
        top.addView(label, lp);
        return top;
    }

    // ------------------------------------------------------------ garage

    private void garagePress() {
        long now = SystemClock.uptimeMillis();
        if (now < movingUntil) { say("The door is moving: Camera › has Stop"); return; }
        GarageDoor.State s = garageState;
        if (s == null || s == GarageDoor.State.UNKNOWN) { say("The garage opener isn't answering"); shake(garageAction); return; }
        final boolean open = s != GarageDoor.State.OPEN;
        if (now > armedUntil) {                                 // first press: arm, and ask for a second
            armedUntil = now + ARM_MS;
            say("Press OK again to " + (open ? "open" : "close") + " the garage");
            bounce(garageAction);
            renderGarageButton();
            ui.postDelayed(this::renderGarageButton, ARM_MS + 50);
            return;
        }
        armedUntil = 0;
        movingUntil = now + TRAVEL_MS + 2_000;
        movingOpen = open;
        bounce(garageAction);
        say(open ? "Opening the garage…" : "Closing the garage…");
        render();
        final int g = gen;
        net.execute(() -> {
            boolean ok = door.set(open);
            ui.post(() -> {
                if (!ok) {
                    movingUntil = 0;
                    say("Couldn't reach the garage opener");
                }
                if (g == gen) { garageDue = 0; render(); }
            });
        });
    }

    private void renderGarageButton() {
        if (garageAction == null) return;
        long now = SystemClock.uptimeMillis();
        boolean armed = now < armedUntil, moving = now < movingUntil;
        boolean open = garageState != GarageDoor.State.OPEN;
        garageAction.setText(moving ? (movingOpen ? "Opening…" : "Closing…") : armed ? "Confirm" : open ? "Open" : "Close");
        chipTint(garageAction, armed ? ORANGE : 0);
    }

    // ------------------------------------------------------------ aircon

    private void acNudge(int d) {
        if (ac != null && ac.mode == 0) { say("Fan mode has no temperature"); shake(acTemp); return; }
        acChange(d > 0 ? "plus" : "minus");
    }

    /** Apply on screen now, send once the presses stop (like the Aircon screen). */
    private void acChange(String what) {
        Airbase.Status s = ac;
        if (s == null) { say(acRead ? "The aircon isn't answering" : "Still reading the aircon…"); return; }
        switch (what) {
            case "power": s.power = !s.power; break;
            case "plus": s.temp = clamp(s, s.temp + 1); break;
            case "minus": s.temp = clamp(s, s.temp - 1); break;
            case "mode": {
                List<Integer> modes = new ArrayList<>();
                modes.add(2);
                modes.add(1);
                if (s.hasDry) modes.add(7);
                modes.add(0);
                if (s.hasAuto) modes.add(3);
                int i = modes.indexOf(s.mode);
                s.mode = modes.get((i + 1) % modes.size());
                s.temp = clamp(s, s.temp);
                s.power = true;                                // picking a mode means you want it running
                break;
            }
        }
        acTouchedAt = SystemClock.uptimeMillis();
        render();
        bounce("power".equals(what) ? acPower : "mode".equals(what) ? acMode : acTemp);
        ui.removeCallbacks(acSend);
        ui.postDelayed(acSend, SEND_DELAY_MS);
    }

    private static int clamp(Airbase.Status s, int t) {
        if (s.mode == 1) return Math.max(s.heatLow, Math.min(s.heatHigh, t));
        return Math.max(s.coolLow, Math.min(s.coolHigh, t));
    }

    private void flushAircon() { if (ui.hasCallbacks(acSend)) { ui.removeCallbacks(acSend); acSend.run(); } }

    private final Runnable acSend = new Runnable() {
        @Override public void run() {
            final Airbase.Status s = ac;
            if (s == null) return;
            final String what = !s.power ? "Aircon off" : "Aircon " + modeName(s.mode).toLowerCase(Locale.US) + (s.mode == 0 ? "" : " to " + s.temp + "°");
            net.execute(() -> {
                boolean ok = aircon.set(s);
                ui.post(() -> {
                    acTouchedAt = SystemClock.uptimeMillis();     // give the Airbase a moment before trusting a reading
                    say(ok ? "✓  " + what : "Couldn't reach the Airbase");
                    airconDue = SystemClock.uptimeMillis() + HOLD_MS + 500;
                });
            });
        }
    };

    private static String modeName(int m) { return m == 1 ? "Heating" : m == 2 ? "Cooling" : m == 7 ? "Drying" : m == 3 ? "Auto" : "Fan"; }

    private static int modeColor(int m) { return m == 1 ? ORANGE : m == 2 ? ICE : m == 7 ? TEAL : m == 3 ? PURPLE : Color.WHITE; }

    // ------------------------------------------------------------ polling

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!isOpen()) return;
            ui.postDelayed(this, 500);
            if (childOpen()) { childWasOpen = true; stopGarageStream(); return; }   // the screen gets its own stream
            if (childWasOpen) {                                // closed with its own button rather than Back
                childClosed();
                return;
            }
            long now = SystemClock.uptimeMillis();
            final int g = gen;
            if (garageCard != null && !garageBusy && now >= garageDue) {
                garageBusy = true;
                net.execute(() -> {
                    GarageDoor.State s = door.state();
                    ui.post(() -> {
                        garageBusy = false;
                        long t = SystemClock.uptimeMillis();
                        if (t < movingUntil && !movingOpen && s == GarageDoor.State.CLOSED) {
                            movingUntil = 0;                   // the sensor says it's down
                            say("✓  Garage closed");
                        }
                        boolean moving = t < movingUntil;
                        garageDue = t + (moving ? GARAGE_MOVING_MS : GARAGE_MS);
                        if (g != gen) return;
                        garageState = s;
                        render();
                    });
                });
            }
            if (airconCard != null && !airconBusy && now >= airconDue && !ui.hasCallbacks(acSend)) {
                airconBusy = true;
                net.execute(() -> {
                    Airbase.Status s = aircon.status();
                    ui.post(() -> {
                        airconBusy = false;
                        airconDue = SystemClock.uptimeMillis() + AIRCON_MS;
                        if (g != gen || SystemClock.uptimeMillis() - acTouchedAt < HOLD_MS) return;   // you just changed it
                        ac = s;
                        acRead = true;
                        render();
                    });
                });
            }
            if (solarCard != null && !solarBusy && now >= solarDue) {
                solarBusy = true;
                net.execute(() -> {
                    SolisCloud.Reading r = null;
                    try { r = solis.now(); } catch (Exception ignored) { }
                    final SolisCloud.Reading rr = r;
                    ui.post(() -> { solarBusy = false; solarDue = SystemClock.uptimeMillis() + SOLAR_MS; if (g == gen && rr != null) { solar = rr; render(); } });
                });
            }
            if (nvr != null && snaps.length > 0 && !snapBusy && now >= snapDue) {
                snapBusy = true;
                // once the mosaic is filled, the selected picture gets every other turn; the rest take turns
                snapTurn = !snapTurn;
                final int i;
                if (snapFocus >= 0 && snapTurn && snapTries >= snaps.length) i = snapFocus;
                else { i = snapNext; snapNext = (snapNext + 1) % snaps.length; }
                final NvrClient client = nvr;
                net.execute(() -> {
                    Bitmap b = null;
                    try { b = decode(client.snapshot(i + 1)); } catch (Exception ignored) { }
                    final Bitmap bb = b;
                    ui.post(() -> {
                        snapBusy = false;
                        boolean firstPass = ++snapTries < snaps.length;
                        snapDue = SystemClock.uptimeMillis() + (bb == null ? SNAP_MS * 4 : firstPass ? 0 : snapFocus >= 0 ? SNAP_MS / 2 : SNAP_MS);
                        if (g == gen && bb != null && i < snaps.length) crossfade(snaps[i], bb);
                    });
                });
            }
        }
    };

    /** A camera snapshot shrunk to about tile size (the main stream's JPEG is up to 3072 px wide). */
    private static Bitmap decode(byte[] jpg) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= 480) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
    }

    private static void crossfade(ImageView iv, Bitmap b) {
        boolean first = iv.getDrawable() == null;
        iv.setImageBitmap(b);
        if (first) { iv.setAlpha(0f); iv.animate().alpha(1f).setDuration(400).start(); }
    }

    // ------------------------------------------------------------ rendering

    private void updateHello() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        hello.setText(h < 5 ? "GOOD NIGHT" : h < 12 ? "GOOD MORNING" : h < 18 ? "GOOD AFTERNOON" : "GOOD EVENING");
    }

    private void render() {
        List<String> bits = new ArrayList<>();
        if (garageCard != null) {
            GarageDoor.State s = garageState;
            boolean moving = SystemClock.uptimeMillis() < movingUntil;
            if (moving) garageCard.show(movingOpen ? "Opening" : "Closing", ORANGE, "Stop it from Camera ›", ORANGE);
            else if (s == null) garageCard.show("…", Color.WHITE, "Reading the door", 0);
            else if (s == GarageDoor.State.OPEN) garageCard.show("Open", ORANGE, "Close: press twice", ORANGE);
            else if (s == GarageDoor.State.CLOSED) garageCard.show("Closed", GREEN, "All shut", GREEN);
            else garageCard.show("—", GREY, "Opener not answering", 0);
            if (s == GarageDoor.State.OPEN) bits.add("Garage open");
            else if (s == GarageDoor.State.CLOSED) bits.add("Garage closed");
            renderGarageButton();
        }
        if (airconCard != null) {
            Airbase.Status s = ac;
            if (!acRead) { airconCard.show("…", Color.WHITE, "Reading the aircon", 0); acTemp.setText("—"); }
            else if (s == null) { airconCard.show("—", GREY, "Airbase not answering", 0); acTemp.setText("—"); }
            else {
                String temps = (s.inside != null ? "In " + s.inside + "°" : "") + (s.inside != null && s.outside != null ? "  ·  " : "")
                        + (s.outside != null ? "Out " + s.outside + "°" : "");
                int color = s.power ? modeColor(s.mode) : Color.WHITE;
                airconCard.show(s.power ? modeName(s.mode) : "Off", color, temps, s.power ? color : 0);
                acTemp.setText(s.mode == 0 ? "Fan" : s.temp + "°");
                acTemp.setTextColor(s.power ? color : GREY);
                chipTint(acPower, s.power ? GREEN : 0);
                acMode.setText(s.mode == 1 ? "Heat" : s.mode == 2 ? "Cool" : s.mode == 7 ? "Dry" : s.mode == 3 ? "Auto" : "Fan");
                bits.add(s.power ? "Aircon " + modeName(s.mode).toLowerCase(Locale.US) : "Aircon off");
            }
        }
        if (solarCard != null) {
            SolisCloud.Reading r = solar;
            if (r == null) solarCard.show("…", Color.WHITE, "Reading the inverter", 0);
            else {
                String grid = Math.abs(r.grid) < 50 ? "Grid idle" : (r.grid > 0 ? "Buying " : "Selling ") + power(r.grid);
                solarCard.show(power(r.solar), r.solar >= 50 ? YELLOW : Color.WHITE, "Home " + power(r.home) + "  ·  " + grid,
                        r.grid < -50 ? GREEN : r.grid > 50 ? ORANGE : 0);
                solarCard.battery(r.soc, r.battery);
                flow.set(r);
                bits.add("Solar " + power(r.solar));
                if (r.soc >= 0) bits.add("Battery " + Math.round(r.soc) + "%");
            }
        }
        summary.setText(bits.isEmpty() ? "Cameras, garage, solar and aircon in one place" : String.join("   ·   ", bits));
    }

    private static String power(double w) {
        w = Math.abs(w);
        return w < 1000 ? String.format(Locale.US, "%.0f W", w) : String.format(Locale.US, "%.1f kW", w / 1000);
    }

    // ------------------------------------------------------------ feedback

    private void say(String msg) {
        toast.setText(msg);
        toast.animate().cancel();
        if (toast.getAlpha() < 0.5f) toast.setTranslationY(dp(16));
        toast.animate().alpha(1f).translationY(0).setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
        ui.removeCallbacks(hideToast);
        ui.postDelayed(hideToast, 2600);
    }

    private final Runnable hideToast = this::hideToast;

    private void hideToast() { toast.animate().alpha(0f).translationY(dp(16)).setDuration(250).start(); }

    /** A quick squash and spring back, from whatever size it is (a focused control is already grown). */
    private static void bounce(View v) {
        if (v == null) return;
        float s = v.isFocused() ? 1.1f : 1f;
        v.animate().cancel();
        v.animate().scaleX(s * 0.88f).scaleY(s * 0.88f).setDuration(70).withEndAction(() ->
                v.animate().scaleX(s).scaleY(s).setDuration(260).setInterpolator(new OvershootInterpolator(3f)).start()).start();
    }

    private void shake(View v) {
        ObjectAnimator a = ObjectAnimator.ofFloat(v, "translationX", 0, -dp(8), dp(8), -dp(5), dp(5), 0);
        a.setDuration(320);
        a.start();
    }

    // ------------------------------------------------------------ views

    private View glow(int color) {
        View v = new View(getContext());
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(dp(260));
        g.setColors(new int[]{color, 0x00000000});
        v.setBackground(g);
        return v;
    }

    private TextView text(Context c, float sp, int color, int style) {
        TextView t = new TextView(c);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.DEFAULT, style);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    /** A pill button: glassy at rest, white with black text when focused. A tint colours it (armed, switched on). */
    private TextView chip(Context c, String label, String key) {
        TextView b = text(c, 14, Color.WHITE, Typeface.BOLD);
        b.setText(label);
        b.setTag(key);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setFocusable(true);
        b.setIncludeFontPadding(false);
        chipTint(b, 0);
        b.setOnFocusChangeListener((v, has) -> {
            chipStyle(b);
            b.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(150)
                    .setInterpolator(new OvershootInterpolator(has ? 2.5f : 0f)).start();
            b.setElevation(dp(has ? 8 : 0));
        });
        return b;
    }

    private void chipTint(TextView b, int tint) {
        if (b == null) return;
        b.setTag(R_TINT, tint);
        chipStyle(b);
    }

    private void chipStyle(TextView b) {
        Object t = b.getTag(R_TINT);
        int tint = t instanceof Integer ? (Integer) t : 0;
        boolean has = b.isFocused();
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(100));
        if (has) g.setColor(tint != 0 ? tint : Color.WHITE);
        else if (tint != 0) { g.setColor((tint & 0x00FFFFFF) | 0x40000000); g.setStroke(dp(1.5f), tint); }
        else g.setColor(0x26FFFFFF);
        b.setBackground(g);
        b.setTextColor(has ? Color.BLACK : tint != 0 ? tint : Color.WHITE);
    }

    /** One glass card. It lights up (accent tint, coloured or white edge, a little bigger) while it holds the focus. */
    private final class Card extends FrameLayout {
        final int accent;
        final boolean whole;                                   // the card itself is the button
        final GradientDrawable bg = new GradientDrawable(), ring = new GradientDrawable();
        TextView value, detail;
        View dot, barFill;
        FrameLayout barBox;

        Card(Context c, int accent, boolean focusable) {
            super(c);
            this.accent = accent;
            this.whole = focusable;
            setFocusable(focusable);
            setClipChildren(false);
            bg.setCornerRadius(dp(24));
            bg.setOrientation(GradientDrawable.Orientation.TL_BR);
            bg.setColors(new int[]{0xE6262830, 0xE6191A20});
            setBackground(bg);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(24)); }
            });
            setElevation(dp(6));
            ring.setCornerRadius(dp(24));
            ring.setColor(0x00000000);
            ring.setStroke(Math.max(1, dp(0.5f)), 0x26FFFFFF);
        }

        /** The edge, drawn last so it sits over the pictures. */
        void addRing() {
            View v = new View(getContext());
            v.setBackground(ring);
            addView(v, new LayoutParams(-1, -1));
        }

        void lit(boolean on) {
            ring.setStroke(on ? dp(whole ? 3 : 2) : Math.max(1, dp(0.5f)), on ? (whole ? Color.WHITE : (accent & 0x00FFFFFF) | 0xCC000000) : 0x26FFFFFF);
            bg.setColors(on ? new int[]{blend(accent, 0xFF262830, 0.28f), 0xF01A1B22} : new int[]{0xE6262830, 0xE6191A20});
            animate().scaleX(on ? 1.025f : 1f).scaleY(on ? 1.025f : 1f).setStartDelay(0).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator()).start();
            setElevation(dp(on ? 16 : 6));
        }

        void show(String v, int color, String d, int dotColor) {
            if (value == null) return;
            value.setText(v);
            value.setTextColor(color);
            detail.setText(d);
            dot.setVisibility(dotColor == 0 ? INVISIBLE : VISIBLE);
            if (dotColor != 0) {
                ((GradientDrawable) dot.getBackground()).setColor(dotColor);
                if (dot.getTag() == null) {                    // a slow breathing pulse
                    dot.setTag(Boolean.TRUE);
                    ObjectAnimator a = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.3f);
                    a.setDuration(1100);
                    a.setRepeatMode(ValueAnimator.REVERSE);
                    a.setRepeatCount(ValueAnimator.INFINITE);
                    a.start();
                }
            }
        }

        /** Battery: a thin bar, green while charging, amber while discharging, white idle (the % is in the flow). */
        void battery(double soc, double watts) {
            if (soc < 0 || barBox == null) return;
            barBox.setVisibility(VISIBLE);
            GradientDrawable f = new GradientDrawable();
            f.setCornerRadius(dp(3));
            f.setColor(watts > 50 ? GREEN : watts < -50 ? ORANGE : 0xCCFFFFFF);
            barFill.setBackground(f);
            final double frac = Math.max(0, Math.min(1, soc / 100));
            barBox.post(() -> {
                LayoutParams lp = (LayoutParams) barFill.getLayoutParams();
                lp.width = (int) (barBox.getWidth() * frac);
                barFill.setLayoutParams(lp);
            });
        }
    }

    private static int blend(int a, int b, float t) {
        int r = Math.round(Color.red(a) * t + Color.red(b) * (1 - t));
        int g = Math.round(Color.green(a) * t + Color.green(b) * (1 - t));
        int bl = Math.round(Color.blue(a) * t + Color.blue(b) * (1 - t));
        return Color.argb(0xF0, r, g, bl);
    }

    /**
     * Where the power is going: the sun, the house, the battery and the grid, with dots running along the lines in the
     * direction power flows, faster for more power. Redraws at about 30 fps while it's on screen.
     */
    private final class FlowView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), t = new Paint(Paint.ANTI_ALIAS_FLAG);
        private double sun, home, bat, grid, soc = -1;
        private boolean known;
        private final float[] phase = new float[3];
        private long last;

        FlowView(Context c) {
            super(c);
            t.setColor(0xCCFFFFFF);
            t.setTextAlign(Paint.Align.CENTER);
            t.setTextSize(dp(10.5f));
            t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        }

        void set(SolisCloud.Reading r) { sun = r.solar; home = r.home; bat = r.battery; grid = r.grid; soc = r.soc; known = true; invalidate(); }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float r = Math.min(w, h) * 0.13f;
            float hx = w * 0.5f, hy = h * 0.5f;                 // house in the middle
            float sx = w * 0.5f, sy = r + dp(1);                // sun above
            float bx = r + dp(4), by = h - r - dp(14);          // battery bottom left
            float gx = w - r - dp(4), gy = h - r - dp(14);      // grid bottom right
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 ? 0 : Math.min(100, now - last) / 1000f;
            last = now;
            line(c, 0, sx, sy, hx, hy, sun, YELLOW, dt, r);     // + generating: sun -> house
            line(c, 1, hx, hy, bx, by, bat, GREEN, dt, r);      // + charging: house -> battery
            line(c, 2, gx, gy, hx, hy, grid, ORANGE, dt, r);    // + buying: grid -> house
            node(c, sx, sy, r, sun > 50 ? YELLOW : 0xFF4A4A50);
            node(c, hx, hy, r, 0xFF3A3D46);
            node(c, bx, by, r, 0xFF263A2C);
            node(c, gx, gy, r, 0xFF3D3328);
            sunGlyph(c, sx, sy, r * 0.6f);
            houseGlyph(c, hx, hy, r * 0.55f);
            batteryGlyph(c, bx, by, r * 0.6f);
            gridGlyph(c, gx, gy, r * 0.6f);
            if (known) {
                c.drawText(power(home), hx + r + dp(24), hy + dp(4), t);
                c.drawText(soc >= 0 ? Math.round(soc) + "%" : power(bat), bx, by + r + dp(12), t);
                c.drawText(Math.abs(grid) < 50 ? "idle" : power(grid), gx, gy + r + dp(12), t);
            }
            if (isShown()) postInvalidateDelayed(33);
        }

        private void line(Canvas c, int k, float x1, float y1, float x2, float y2, double watts, int color, float dt, float r) {
            boolean on = known && Math.abs(watts) >= 50;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(on ? (color & 0x00FFFFFF) | 0x55000000 : 0x22FFFFFF);
            c.drawLine(x1, y1, x2, y2, p);
            if (!on) return;
            float speed = (float) (0.25 + 0.35 * Math.log10(1 + Math.abs(watts) / 100));   // more power, faster dots
            phase[k] = (phase[k] + dt * speed * (watts > 0 ? 1 : -1) + 1) % 1f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            float len = (float) Math.hypot(x2 - x1, y2 - y1), skip = r / Math.max(1, len);
            for (int i = 0; i < 3; i++) {
                float f = (phase[k] + i / 3f) % 1f;
                float u = skip + f * (1 - 2 * skip);
                p.setAlpha(Math.round(255 * Math.min(1, Math.min(f, 1 - f) * 6)));   // fade in and out at the ends
                c.drawCircle(x1 + (x2 - x1) * u, y1 + (y2 - y1) * u, dp(3), p);
            }
            p.setAlpha(255);
        }

        private void node(Canvas c, float x, float y, float r, int color) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            c.drawCircle(x, y, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1));
            p.setColor(0x33FFFFFF);
            c.drawCircle(x, y, r, p);
        }

        private void sunGlyph(Canvas c, float x, float y, float s) {
            int col = sun > 50 ? 0xFF5A3A00 : 0xCCFFFFFF;
            p.setStyle(Paint.Style.FILL);
            p.setColor(col);
            c.drawCircle(x, y, s * 0.42f, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.5f));
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4;
                c.drawLine(x + (float) Math.cos(a) * s * 0.62f, y + (float) Math.sin(a) * s * 0.62f,
                        x + (float) Math.cos(a) * s * 0.92f, y + (float) Math.sin(a) * s * 0.92f, p);
            }
        }

        private void houseGlyph(Canvas c, float x, float y, float s) {
            Path h = new Path();
            h.moveTo(x, y - s);
            h.lineTo(x + s, y - s * 0.1f);
            h.lineTo(x + s * 0.75f, y - s * 0.1f);
            h.lineTo(x + s * 0.75f, y + s * 0.8f);
            h.lineTo(x - s * 0.75f, y + s * 0.8f);
            h.lineTo(x - s * 0.75f, y - s * 0.1f);
            h.lineTo(x - s, y - s * 0.1f);
            h.close();
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            c.drawPath(h, p);
            p.setColor(YELLOW);
            c.drawRect(x - s * 0.18f, y + s * 0.25f, x + s * 0.18f, y + s * 0.8f, p);
        }

        private void batteryGlyph(Canvas c, float x, float y, float s) {
            RectF b = new RectF(x - s * 0.45f, y - s * 0.72f, x + s * 0.45f, y + s * 0.8f);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.5f));
            p.setColor(Color.WHITE);
            c.drawRoundRect(b, dp(2), dp(2), p);
            p.setStyle(Paint.Style.FILL);
            c.drawRect(x - s * 0.18f, y - s * 0.9f, x + s * 0.18f, y - s * 0.72f, p);
            float f = (float) Math.max(0, Math.min(1, (soc < 0 ? 0 : soc) / 100));
            p.setColor(bat > 50 ? GREEN : bat < -50 ? ORANGE : 0xCCFFFFFF);
            float inset = dp(2.5f);
            c.drawRect(b.left + inset, b.bottom - inset - (b.height() - 2 * inset) * f, b.right - inset, b.bottom - inset, p);
        }

        private void gridGlyph(Canvas c, float x, float y, float s) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.5f));
            p.setColor(Color.WHITE);
            c.drawLine(x - s * 0.55f, y + s * 0.85f, x, y - s * 0.85f, p);
            c.drawLine(x + s * 0.55f, y + s * 0.85f, x, y - s * 0.85f, p);
            c.drawLine(x - s * 0.7f, y - s * 0.35f, x + s * 0.7f, y - s * 0.35f, p);
            c.drawLine(x - s * 0.45f, y + s * 0.2f, x + s * 0.45f, y + s * 0.2f, p);
        }
    }
}
