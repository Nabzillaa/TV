package com.nabil.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Full-screen overlay: 2x2 grid of live NVR streams (H.265 over RTSP, hardware-decoded);
 * OK opens one full screen, Left/Right switches. Any stream that fails falls back to
 * JPEG snapshots from the NVR so a tile is never just dead.
 */
class CameraLayer extends FrameLayout {
    private static final String TAG = "CameraLayer";
    private static final int MAX = 16;

    private final Handler ui;
    private final SharedPreferences prefs;
    private final float dp;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    private String host, user, pass;
    private NvrClient snap;
    private int channels;
    private boolean baseusOn;
    private int tiles;                       // NVR channels + Baseus lenses + Eufy cameras
    private boolean eufyOn;
    private int euN;                         // number of Eufy tiles
    private int[] euCh = new int[0];         // Eufy camera channel per Eufy tile
    private String[] euName = new String[0];
    private EufyStream eufy;
    private AnnexBDecoder[] euDec = new AnnexBDecoder[0];   // live, hardware: full screen only
    private AnnexBDecoder[] euSnap = new AnnexBDecoder[0];  // thumbnails, software: grid tiles
    private final boolean[] euUnavailable = new boolean[16];
    private BaseusStream baseus;
    private String dbName, dbUrl;            // Google doorbell: last tile, Google Home for web in GeckoView
    private DoorbellView doorbell;
    private final AnnexBDecoder[] lensDec = new AnnexBDecoder[2];    // live, hardware: full screen only
    private final AnnexBDecoder[] lensSnap = new AnnexBDecoder[2];   // keyframe thumbnails, software: grid tiles
    private volatile String baseusState = "Connecting…";
    private int current = 1;
    private boolean fullMode;

    // grid
    private final LinearLayout gridBox;
    private final ImageView[] tileImage = new ImageView[MAX + 1];
    private final TextureView[] tileVideo = new TextureView[MAX + 1];
    private final TextView[] tileStatus = new TextView[MAX + 1];
    private final View[] tileFrame = new View[MAX + 1];
    private final Surface[] tileSurface = new Surface[MAX + 1];
    private final RtspHevcClient[] tileClient = new RtspHevcClient[MAX + 1];
    private final int[][] tileSize = new int[MAX + 1][2];

    // full screen
    private final FrameLayout fullBox;
    private final ImageView fullImage;
    private final TextureView fullVideo;
    private final TextView fullLabel, fullStatus;
    private Surface fullSurface;
    private RtspHevcClient fullClient, fullPreview;
    private int fullSubtype;
    private final int[] fullSize = new int[2];

    // snapshot fallback
    private final Set<Integer> snapChannels = new TreeSet<>();
    private boolean fullSnap;
    private volatile int snapGen;
    private boolean snapRunning;

    CameraLayer(Context c, Handler ui, SharedPreferences prefs) {
        super(c);
        this.ui = ui;
        this.prefs = prefs;
        this.dp = c.getResources().getDisplayMetrics().density;
        setBackgroundColor(0xFF050507);
        setVisibility(GONE);

        gridBox = new LinearLayout(c);
        gridBox.setOrientation(LinearLayout.VERTICAL);
        gridBox.setPadding(dp(40), dp(64), dp(40), dp(24));
        addView(gridBox, new LayoutParams(-1, -1));

        TextView title = new TextView(c);
        title.setText("Cameras");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        LayoutParams tp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tp.setMargins(dp(44), dp(18), 0, 0);
        addView(title, tp);
        TextView hint = new TextView(c);
        hint.setText("OK: full screen   ·   Back: close");
        hint.setTextColor(0x99FFFFFF);
        hint.setTextSize(14);
        LayoutParams hp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        hp.setMargins(0, dp(28), dp(44), 0);
        addView(hint, hp);

        fullBox = new FrameLayout(c);
        fullBox.setBackgroundColor(Color.BLACK);
        fullBox.setVisibility(GONE);
        fullImage = new ImageView(c);
        fullImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        fullBox.addView(fullImage, new LayoutParams(-1, -1));
        fullVideo = new TextureView(c);
        fullVideo.setSurfaceTextureListener(new SurfaceTextureAdapter() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                fullSurface = new Surface(st);
                if (fullMode && !fullSnap && fullPreview == null && !isDb(current)) showPreviewThenMain();
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                stopFullClient();
                AnnexBDecoder ld = liveDecoder(current);
                if (ld != null) ld.detach();
                if (fullSurface != null) { fullSurface.release(); fullSurface = null; }
                return true;
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { applyTransform(fullVideo, fullSize[0], fullSize[1], false); }
        });
        fullVideo.setFocusable(true);
        fullVideo.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { switchTo(current % streamTiles() + 1); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) { switchTo(current == 1 ? streamTiles() : current - 1); return true; }
            return false;
        });
        fullBox.addView(fullVideo, new LayoutParams(-1, -1));
        fullLabel = overlayText(c, 18);
        LayoutParams lp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lp.setMargins(dp(24), dp(16), 0, 0);
        fullBox.addView(fullLabel, lp);
        fullStatus = overlayText(c, 14);
        LayoutParams sp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sp.bottomMargin = dp(14);
        fullBox.addView(fullStatus, sp);
        addView(fullBox, new LayoutParams(-1, -1));
    }

    private int dp(float v) { return Math.round(v * dp); }

    /** Move the remote's focus only among this screen's views: the dock underneath is still focusable, and on the
     *  bottom row it is often nearer than the next tile. */
    @Override public View focusSearch(View focused, int direction) {
        View next = android.view.FocusFinder.getInstance().findNextFocus(this, focused, direction);
        return next != null ? next : focused;
    }

    private TextView overlayText(Context c, float sp) {
        TextView t = new TextView(c);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        t.setShadowLayer(dp(4), 0, dp(1), Color.BLACK);
        return t;
    }

    /** SurfaceTextureListener with no-op defaults. */
    private abstract static class SurfaceTextureAdapter implements TextureView.SurfaceTextureListener {
        @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) { }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture st) { }
    }

    // ------------------------------------------------------------ configuration

    boolean configured() { return prefs.getString("nvr_host", null) != null; }

    /** (Re)read settings from prefs and rebuild the tile grid. */
    void reload() {
        stopAll();
        gridBox.removeAllViews();
        if (!configured()) return;
        host = prefs.getString("nvr_host", "");
        user = prefs.getString("nvr_user", "");
        pass = prefs.getString("nvr_pass", "");
        snap = new NvrClient(host, user, pass);
        channels = Math.max(1, Math.min(MAX - 2, prefs.getInt("nvr_n", 4)));
        baseusOn = prefs.getString("bs_user", null) != null && prefs.getString("bs_pass", null) != null;
        eufyOn = prefs.getString("eu_user", null) != null && prefs.getString("eu_pass", null) != null;
        euN = 0;
        euCh = new int[0];
        euName = new String[0];
        if (eufyOn) {
            String list = prefs.getString("eu_cams", "");
            String[] parts = list.isEmpty() ? new String[0] : list.split(";");
            java.util.Set<String> hidden = new java.util.HashSet<>(java.util.Arrays.asList(prefs.getString("eu_hide", "").split(",")));
            java.util.List<Integer> chs = new java.util.ArrayList<>();
            java.util.List<String> names = new java.util.ArrayList<>();
            for (String part : parts) {
                int c = part.indexOf(':');
                if (c <= 0) continue;
                try {
                    int chn = Integer.parseInt(part.substring(0, c));
                    if (hidden.contains(String.valueOf(chn))) continue;       // switched off on purpose
                    chs.add(chn); names.add(part.substring(c + 1));
                } catch (NumberFormatException ignored) { }
            }
            euN = Math.min(chs.size(), 8);
            euCh = new int[euN];
            euName = new String[euN];
            for (int i = 0; i < euN; i++) { euCh[i] = chs.get(i); euName[i] = names.get(i); }
            euDec = new AnnexBDecoder[euN];
            euSnap = new AnnexBDecoder[euN];
        }
        java.util.Arrays.fill(euUnavailable, false);
        for (String b : prefs.getString("eu_bad", "").split(",")) {
            try { int c = Integer.parseInt(b.trim()); for (int i = 0; i < euN; i++) if (euCh[i] == c) euUnavailable[channels + (baseusOn ? 2 : 0) + 1 + i] = true; } catch (NumberFormatException ignored) { }
        }
        dbUrl = prefs.getString("db_url", null);
        dbName = prefs.getString("db_name", "Doorbell");
        tiles = channels + (baseusOn ? 2 : 0) + euN + (dbUrl != null ? 1 : 0);
        int cols = tiles <= 1 ? 1 : tiles <= 4 ? 2 : tiles <= 9 ? 3 : 4, rows = (tiles + cols - 1) / cols, ch = 1;
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            gridBox.addView(row, new LinearLayout.LayoutParams(-1, 0, 1f));
            for (int k = 0; k < cols && ch <= tiles; k++, ch++) row.addView(buildTile(ch), tileLp());
        }
    }

    private LinearLayout.LayoutParams tileLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1f);
        p.setMargins(dp(6), dp(6), dp(6), dp(6));
        return p;
    }

    private boolean isBs(int ch) { return baseusOn && ch > channels && ch <= channels + 2; }
    private int lensOf(int ch) { return ch - channels - 1; }
    private boolean isEu(int ch) { int base = channels + (baseusOn ? 2 : 0); return eufyOn && ch > base && ch <= base + euN; }
    private int euIdx(int ch) { return ch - channels - (baseusOn ? 2 : 0) - 1; }
    private boolean isDb(int ch) { return dbUrl != null && ch == tiles; }
    private int streamTiles() { return Math.max(1, dbUrl != null ? tiles - 1 : tiles); }   // tiles that play here
    private boolean isLive(int ch) { return isBs(ch) || isEu(ch); }          // tiles that run on a non-NVR stream
    private AnnexBDecoder liveDecoder(int ch) { return isBs(ch) ? lensDec[lensOf(ch)] : isEu(ch) ? euDec[euIdx(ch)] : null; }
    private AnnexBDecoder snapDecoder(int ch) { return isBs(ch) ? lensSnap[lensOf(ch)] : isEu(ch) ? euSnap[euIdx(ch)] : null; }
    private String tileName(int ch) {
        if (isBs(ch)) return "Baseus · " + (lensOf(ch) == 0 ? "Left" : "Right");
        if (isEu(ch)) return "Eufy · " + euName[euIdx(ch)] + " (" + euCh[euIdx(ch)] + ")";
        if (isDb(ch)) return "Google · " + dbName;
        return "Channel " + ch;
    }

    private View buildTile(final int ch) {
        Context c = getContext();
        FrameLayout f = new FrameLayout(c);
        f.setFocusable(true);
        f.setPadding(dp(3), dp(3), dp(3), dp(3));
        GradientDrawable idle = border(0x22FFFFFF), focus = border(0xFF64D2FF);
        f.setBackground(idle);
        f.setOnFocusChangeListener((v, has) -> f.setBackground(has ? focus : idle));
        f.setOnClickListener(v -> { if (isDb(ch)) openDoorbell(); else enterFull(ch); });
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(0xFF111114);
        f.addView(iv, new LayoutParams(-1, -1));
        TextureView tv = new TextureView(c);
        tv.setSurfaceTextureListener(new SurfaceTextureAdapter() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                tileSurface[ch] = new Surface(st);
                if (isOpen() && !fullMode) startTile(ch);
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                RtspHevcClient dc = tileClient[ch];
                if (dc != null) dc.detach();
                AnnexBDecoder sd = snapDecoder(ch);
                if (sd != null) sd.detach();
                if (tileSurface[ch] != null) { tileSurface[ch].release(); tileSurface[ch] = null; }
                return true;
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { applyTransform(tv, tileSize[ch][0], tileSize[ch][1], true); }
        });
        f.addView(tv, new LayoutParams(-1, -1));
        TextView label = overlayText(c, 15);
        label.setText(tileName(ch));
        LayoutParams lp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lp.setMargins(dp(12), 0, 0, dp(8));
        f.addView(label, lp);
        TextView st = overlayText(c, 13);
        st.setText(isDb(ch) ? "" : "Connecting…");
        if (isDb(ch)) iv.setImageBitmap(doorbellGlyph());
        f.addView(st, new LayoutParams(-2, -2, Gravity.CENTER));
        tileImage[ch] = iv;
        tileVideo[ch] = tv;
        tileStatus[ch] = st;
        tileFrame[ch] = f;
        return f;
    }

    /** The doorbell only streams through Google (WebRTC, no local API): full screen shows Google Home for web in GeckoView. */
    private void openDoorbell() {
        current = tiles;
        fullMode = true;
        for (int i = 1; i <= channels; i++) stopTile(i);              // free Wi-Fi, CPU and memory for the browser engine
        stopBaseus();
        stopEufy();
        for (int i = 1; i <= MAX; i++) detachTile(i);
        synchronized (snapChannels) { snapChannels.clear(); }
        ensureDoorbell();
        doorbell.view.setVisibility(VISIBLE);
        fullVideo.setVisibility(GONE);                                 // no camera stream behind the page
        gridBox.setVisibility(GONE);
        fullBox.setVisibility(VISIBLE);
        fullImage.setImageDrawable(null);
        fullLabel.setText(tileName(current));
        fullStatus.setText("Google Home   ·   Back: grid");
        doorbell.view.requestFocus();
        doorbell.show();
    }

    /** Open the Cameras screen straight on the doorbell (adb: --es show doorbell). */
    void showDoorbell() {
        if (dbUrl == null) return;
        if (!isOpen()) open();
        openDoorbell();
    }

    private void ensureDoorbell() {
        if (doorbell != null) return;
        doorbell = new DoorbellView(getContext(), dbUrl);
        doorbell.view.setVisibility(GONE);
        fullBox.addView(doorbell.view, 2, new LayoutParams(-1, -1));
    }

    /** Load Google Home in the background once the Cameras screen is up, so the doorbell tile opens quickly. */
    private final Runnable warmDoorbell = () -> {
        if (dbUrl == null || !isOpen()) return;
        ensureDoorbell();
        doorbell.warm();
    };

    private void hideDoorbell() {
        if (doorbell == null || doorbell.view.getVisibility() != VISIBLE) return;
        doorbell.hide();
        doorbell.view.setVisibility(GONE);
        fullVideo.setVisibility(VISIBLE);
    }

    /** Doorbell card for the grid: dark gradient with a white doorbell and a blue ring button. */
    private Bitmap doorbellGlyph() {
        int w = dp(320), h = dp(180);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(bmp);
        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setShader(new android.graphics.LinearGradient(0, 0, 0, h, 0xFF1E2A3A, 0xFF0B0F16, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
        float cx = w * 0.27f, cy = h * 0.44f, bw = h * 0.20f, bh = h * 0.46f;
        p.setColor(0xFFF2F2F7);
        c.drawRoundRect(new android.graphics.RectF(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2), bw / 2, bw / 2, p);
        p.setColor(0xFF1C1C1E);
        c.drawCircle(cx, cy - bh * 0.24f, bw * 0.17f, p);              // camera lens
        p.setColor(0xFF64D2FF);
        c.drawCircle(cx, cy + bh * 0.18f, bw * 0.30f, p);              // lit ring button
        p.setColor(0xFFF2F2F7);
        c.drawCircle(cx, cy + bh * 0.18f, bw * 0.20f, p);
        p.setColor(Color.WHITE);
        p.setTextSize(h * 0.11f);
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        c.drawText("Watch live", w * 0.44f, cy - h * 0.02f, p);
        p.setColor(0xB3FFFFFF);
        p.setTextSize(h * 0.075f);
        p.setTypeface(Typeface.DEFAULT);
        c.drawText("Press OK", w * 0.44f, cy + h * 0.10f, p);
        return bmp;
    }

    private GradientDrawable border(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setCornerRadius(dp(10));
        g.setStroke(dp(2), color);
        return g;
    }

    // ------------------------------------------------------------ open / close

    boolean isOpen() { return getVisibility() == VISIBLE; }

    void open() {
        if (!configured()) return;
        if (snap == null) reload();
        ui.removeCallbacks(warmStop);
        ui.removeCallbacks(baseusStop);
        ui.removeCallbacks(eufyStop);
        fullMode = false;
        fullBox.setVisibility(GONE);
        gridBox.setVisibility(VISIBLE);
        setAlpha(0f);
        setVisibility(VISIBLE);
        animate().alpha(1f).setDuration(180).start();
        ui.removeCallbacks(warmDoorbell);
        ui.postDelayed(warmDoorbell, 1500);
        if (tileFrame[1] != null) tileFrame[1].requestFocus();
        for (int ch = 1; ch <= tiles; ch++) startTile(ch);
    }

    /** Back: full screen -> grid -> closed. */
    void back() {
        if (fullMode) {
            leaveFull();
        } else {
            close();
        }
    }

    /** Hide the screen but keep the NVR connections (and their latest keyframes) alive briefly so re-opening is instant. */
    void close() {
        snapGen++;
        snapRunning = false;
        synchronized (snapChannels) { snapChannels.clear(); }
        fullSnap = false;
        stopFullClient();
        hideDoorbell();
        for (int ch = 1; ch <= MAX; ch++) detachTile(ch);
        setVisibility(GONE);
        armWarmTimer();
        armBaseusStop();
        armEufyStop();
    }

    /** Leaving the launcher: drop every stream. */
    void shutdown() {
        ui.removeCallbacks(warmStop);
        hideDoorbell();
        stopAll();
        setVisibility(GONE);
    }

    /** Called when the Cameras dock icon is focused: connect the grid streams before the user presses OK. */
    void prewarm() {
        if (!configured() || isOpen()) return;
        if (snap == null) reload();
        for (int ch = 1; ch <= tiles; ch++) startTile(ch);
        armWarmTimer();
        armBaseusStop();
        armEufyStop();
    }

    private final Runnable warmStop = () -> { if (!isOpen()) stopAll(); };
    // The Baseus camera is battery/solar powered, so it is released sooner than the NVR streams.
    private final Runnable baseusStop = () -> { if (!isOpen()) stopBaseus(); };
    private final Runnable eufyStop = () -> { if (!isOpen()) stopEufy(); };

    private void armEufyStop() {
        ui.removeCallbacks(eufyStop);
        if (eufyOn) ui.postDelayed(eufyStop, 45_000);
    }

    private void armBaseusStop() {
        ui.removeCallbacks(baseusStop);
        if (baseusOn) ui.postDelayed(baseusStop, 45_000);
    }

    private void armWarmTimer() {
        ui.removeCallbacks(warmStop);
        ui.postDelayed(warmStop, 180_000);
    }

    private void detachTile(int ch) {
        if (isLive(ch)) { AnnexBDecoder sd = snapDecoder(ch); if (sd != null) sd.detach(); return; }
        RtspHevcClient c = tileClient[ch];
        if (c != null) c.detach();
    }

    private void stopAll() {
        snapGen++;
        snapRunning = false;
        synchronized (snapChannels) { snapChannels.clear(); }
        fullSnap = false;
        stopFullClient();
        for (int ch = 1; ch <= MAX; ch++) stopTile(ch);
        stopBaseus();
        stopEufy();
        if (doorbell != null) { hideDoorbell(); doorbell.unload(); }
    }

    // ------------------------------------------------------------ grid streams

    private void startTile(final int ch) {
        if (isBs(ch)) { startBaseusTile(ch); return; }
        if (isEu(ch)) { startEufyTile(ch); return; }
        if (ch > channels || host == null) return;
        boolean visible = isOpen() && !fullMode;
        RtspHevcClient warm = tileClient[ch];
        if (warm != null) {
            if (visible && tileSurface[ch] != null) warm.attach(tileSurface[ch]);
            return;
        }
        if (visible) tileStatus[ch].setText("Connecting…");
        RtspHevcClient c = new RtspHevcClient(host, user, pass, ch, 1, new RtspHevcClient.Listener() {
            @Override public void onVideoSize(int w, int h) { ui.post(() -> { tileSize[ch][0] = w; tileSize[ch][1] = h; applyTransform(tileVideo[ch], w, h, true); if (fullMode && ch == current && fullPreview != null) { fullSize[0] = w; fullSize[1] = h; applyTransform(fullVideo, w, h, false); } }); }
            @Override public void onFirstFrame() { ui.post(() -> { tileStatus[ch].setText(""); tileImage[ch].setImageDrawable(null); }); }
            @Override public void onError(String m) { ui.post(() -> tileFailed(ch, m)); }
        });
        tileClient[ch] = c;
        c.start(visible ? tileSurface[ch] : null);
    }

    private void stopTile(int ch) {
        if (isLive(ch)) return;             // the Baseus and Eufy streams are stopped as a whole
        RtspHevcClient c = tileClient[ch];
        tileClient[ch] = null;
        if (c != null) worker.execute(c::stop);
    }

    // ------------------------------------------------------------ Baseus camera (two lenses, one stream)

    private void startBaseusTile(int ch) {
        ensureBaseus();
        boolean visible = isOpen() && !fullMode;
        AnnexBDecoder d = lensSnap[lensOf(ch)];
        if (d != null && visible && tileSurface[ch] != null) d.attach(tileSurface[ch]);
    }

    private void ensureBaseus() {
        if (!baseusOn) return;
        for (int l = 0; l < 2; l++) {
            if (lensDec[l] != null) continue;
            final int tile = channels + 1 + l;
            lensSnap[l] = new AnnexBDecoder("snap" + l, true, new AnnexBDecoder.Listener() {
                @Override public void onSize(int w, int h) {
                    ui.post(() -> { tileSize[tile][0] = w; tileSize[tile][1] = h; applyTransform(tileVideo[tile], w, h, true); });
                }
                @Override public void onFirstFrame() {
                    ui.post(() -> { tileStatus[tile].setText(""); tileImage[tile].setImageDrawable(null); });
                }
            });
            lensDec[l] = new AnnexBDecoder("live" + l, false, new AnnexBDecoder.Listener() {
                @Override public void onSize(int w, int h) {
                    ui.post(() -> { if (fullMode && current == tile) { fullSize[0] = w; fullSize[1] = h; applyTransform(fullVideo, w, h, false); } });
                }
                @Override public void onFirstFrame() { }
            });
        }
        if (baseus != null && baseus.isRunning()) return;
        baseusState = "Connecting…";
        baseus = new BaseusStream(prefs, 0, new BaseusStream.Listener() {
            @Override public void onFrame(int lens, byte[] d, boolean key) {
                AnnexBDecoder snap = lensSnap[lens], live = lensDec[lens];
                if (snap != null) snap.feed(d, key);
                if (live != null) live.feed(d, key);
            }
            @Override public void onState(String st) {
                baseusState = st;
                if (st.equals("Live")) return;
                ui.post(() -> { for (int l = 0; l < 2; l++) if (tileStatus[channels + 1 + l] != null) tileStatus[channels + 1 + l].setText(st + "…"); });
            }
            @Override public void onError(String m) {
                ui.post(() -> { for (int l = 0; l < 2; l++) if (tileStatus[channels + 1 + l] != null) tileStatus[channels + 1 + l].setText("Baseus: " + m); });
            }
        });
        baseus.start();
    }

    private void stopBaseus() {
        final BaseusStream b = baseus;
        baseus = null;
        if (b != null) worker.execute(b::stop);      // closing the sockets must not run on the UI thread
        for (int l = 0; l < 2; l++) { if (lensDec[l] != null) lensDec[l].reset(); if (lensSnap[l] != null) lensSnap[l].reset(); }
    }

    // ------------------------------------------------------------ Eufy cameras behind a HomeBase (one stream, several cameras)

    private int euTileOf(int cam) {
        for (int i = 0; i < euN; i++) if (euCh[i] == cam) return channels + (baseusOn ? 2 : 0) + 1 + i;
        return -1;
    }

    private int firstEufyAvailable() {
        for (int i = 0; i < euN; i++) if (!euUnavailable[channels + (baseusOn ? 2 : 0) + 1 + i]) return euCh[i];
        return -1;
    }

    private int euRotPos = -1;

    /** The HomeBase streams one camera at a time, so the grid shows each available camera in turn. */
    private final Runnable euRotate = new Runnable() {
        @Override public void run() {
            ui.removeCallbacks(this);
            if (!eufyOn || euN == 0 || eufy == null || !isOpen() || fullMode) return;
            int pick = -1;
            for (int k = 1; k <= euN; k++) {
                int i = ((euRotPos + k) % euN + euN) % euN;
                if (!euUnavailable[channels + (baseusOn ? 2 : 0) + 1 + i]) { pick = i; break; }
            }
            if (pick < 0) return;                                   // nothing available to show
            euRotPos = pick;
            for (int i = 0; i < euN; i++) {
                int tile = channels + (baseusOn ? 2 : 0) + 1 + i;
                if (euSnap[i] == null) continue;
                if (i == pick && tileSurface[tile] != null) euSnap[i].attach(tileSurface[tile]); else euSnap[i].detach();
            }
            eufy.watch(euCh[pick]);
            ui.postDelayed(this, 9_000);
        }
    };

    private void startEufyTile(int ch) {
        ensureEufy();
        if (euUnavailable[ch] && tileStatus[ch] != null) tileStatus[ch].setText("Camera unavailable");
        ui.removeCallbacks(euRotate);
        ui.post(euRotate);
    }

    private void ensureEufy() {
        if (!eufyOn || euN == 0) return;
        for (int i = 0; i < euN; i++) {
            if (euDec[i] != null) continue;
            final int tile = channels + (baseusOn ? 2 : 0) + 1 + i;
            euSnap[i] = new AnnexBDecoder("eusnap" + i, true, new AnnexBDecoder.Listener() {
                @Override public void onSize(int w, int h) {
                    ui.post(() -> { tileSize[tile][0] = w; tileSize[tile][1] = h; applyTransform(tileVideo[tile], w, h, true); });
                }
                @Override public void onFirstFrame() {
                    ui.post(() -> { tileStatus[tile].setText(""); tileImage[tile].setImageDrawable(null); });
                }
            });
            euSnap[i].setKeyframesOnly(true);
            euDec[i] = new AnnexBDecoder("eulive" + i, false, new AnnexBDecoder.Listener() {
                @Override public void onSize(int w, int h) {
                    ui.post(() -> { if (fullMode && current == tile) { fullSize[0] = w; fullSize[1] = h; applyTransform(fullVideo, w, h, false); } });
                }
                @Override public void onFirstFrame() { }
            });
        }
        if (eufy != null && eufy.isRunning()) return;
        eufy = new EufyStream(prefs, firstEufyAvailable(), new EufyStream.Listener() {
            @Override public void onFrame(int cam, byte[] d, boolean key, boolean hevc, int w, int h) {
                for (int i = 0; i < euN; i++) {
                    if (euCh[i] != cam) continue;
                    AnnexBDecoder sn = euSnap[i], lv = euDec[i];
                    if (sn != null) { sn.setStreamInfo(hevc, w, h); sn.feed(d, key); }
                    if (lv != null) { lv.setStreamInfo(hevc, w, h); lv.feed(d, key); }
                }
            }
            @Override public void onCameraResult(int cam, int rc) {
                final int tile = euTileOf(cam);
                if (tile < 0) return;
                euUnavailable[tile] = rc != 0;
                java.util.Set<String> bad = new java.util.HashSet<>(java.util.Arrays.asList(prefs.getString("eu_bad", "").split(",")));
                bad.remove("");
                if (rc != 0) bad.add(String.valueOf(cam)); else bad.remove(String.valueOf(cam));
                prefs.edit().putString("eu_bad", android.text.TextUtils.join(",", bad)).apply();
                if (rc != 0) ui.post(euRotate);                   // skip straight to the next camera
                ui.post(() -> { if (rc != 0 && tileStatus[tile] != null) tileStatus[tile].setText("Camera unavailable"); });
            }
            @Override public void onState(String st) {
                if (st.equals("Live")) return;
                ui.post(() -> { for (int i = 0; i < euN; i++) { int t = channels + (baseusOn ? 2 : 0) + 1 + i; if (tileStatus[t] != null && !euUnavailable[t]) tileStatus[t].setText(st + "…"); } });
            }
            @Override public void onError(String m) {
                ui.post(() -> { for (int i = 0; i < euN; i++) { int t = channels + (baseusOn ? 2 : 0) + 1 + i; if (tileStatus[t] != null) tileStatus[t].setText("Eufy: " + m); } });
            }
        });
        eufy.start();
    }

    private void stopEufy() {
        ui.removeCallbacks(euRotate);
        final EufyStream e = eufy;
        eufy = null;
        if (e != null) worker.execute(e::stop);
        for (int i = 0; i < euN; i++) { if (euDec[i] != null) euDec[i].reset(); if (euSnap[i] != null) euSnap[i].reset(); }
    }

    private void tileFailed(final int ch, String msg) {
        tileClient[ch] = null;
        if (!isOpen() || fullMode) return;
        Log.w(TAG, "tile " + ch + " stream failed: " + msg + " -> snapshots");
        tileClient[ch] = null;
        tileStatus[ch].setText(NvrClient.ERR_LOGIN.equals(msg) ? "Login failed — check NVR password" : "");
        synchronized (snapChannels) { snapChannels.add(ch); }
        ensureSnapshotLoop();
        // try the live stream again later
        ui.postDelayed(() -> {
            if (isOpen() && !fullMode && tileClient[ch] == null && !NvrClient.ERR_LOGIN.equals(msg)) {
                synchronized (snapChannels) { snapChannels.remove(ch); }
                startTile(ch);
            }
        }, 30_000);
    }

    // ------------------------------------------------------------ full screen

    private void enterFull(int ch) {
        current = ch;
        fullMode = true;
        if (isLive(ch)) for (int i = 1; i <= channels; i++) stopTile(i);   // free Wi-Fi and CPU for the camera
        for (int i = 1; i <= MAX; i++) detachTile(i);
        synchronized (snapChannels) { snapChannels.clear(); }
        gridBox.setVisibility(GONE);
        fullBox.setVisibility(VISIBLE);
        fullImage.setImageDrawable(null);
        fullLabel.setText(tileName(ch));
        fullVideo.requestFocus();
        fullSubtype = 0;
        fullSnap = false;
        showPreviewThenMain();
    }

    /** Full screen opens instantly on the already-connected sub-stream, then upgrades to the main stream. */
    private void showPreviewThenMain() {
        ui.removeCallbacks(upgradeCheck);
        fullPreview = null;
        if (isLive(current)) {                       // Baseus lens or Eufy camera: attach its decoder to the full-screen surface
            if (isBs(current)) ensureBaseus(); else { ensureEufy(); ui.removeCallbacks(euRotate); if (eufy != null) eufy.watch(euCh[euIdx(current)]); }
            AnnexBDecoder d = liveDecoder(current);
            fullSize[0] = d.width(); fullSize[1] = d.height();
            applyTransform(fullVideo, fullSize[0], fullSize[1], false);
            fullStatus.setText((isBs(current) ? "Baseus " + (lensOf(current) == 0 ? "left" : "right") + " lens"
                    : "Eufy " + euName[euIdx(current)]) + "   ·   ◀ ▶ switch   ·   Back: grid");
            if (fullSurface != null) d.attach(fullSurface);
            return;
        }
        RtspHevcClient tile = tileClient[current];
        if (tile != null && fullSurface != null && host != null) {
            fullPreview = tile;
            fullStatus.setText("Sub stream — loading main stream…");
            tile.attach(fullSurface);
            final int ch = current;
            RtspHevcClient main = new RtspHevcClient(host, user, pass, ch, 0, new RtspHevcClient.Listener() {
                @Override public void onVideoSize(int w, int h) { ui.post(() -> { if (fullPreview == null && ch == current) { fullSize[0] = w; fullSize[1] = h; applyTransform(fullVideo, w, h, false); } }); }
                @Override public void onFirstFrame() { ui.post(() -> { if (ch == current) fullStatus.setText("Main stream   ·   ◀ ▶ switch   ·   Back: grid"); }); }
                @Override public void onError(String m) { ui.post(() -> { if (ch == current && fullClient != null && fullPreview != null) { fullClient = null; fullStatus.setText("Sub stream   ·   ◀ ▶ switch   ·   Back: grid"); } }); }
            });
            fullClient = main;
            main.start(null);
            ui.postDelayed(upgradeCheck, 100);
        } else {
            startFullClient();
        }
    }

    private final Runnable upgradeCheck = new Runnable() {
        @Override public void run() {
            RtspHevcClient m = fullClient;
            if (!fullMode || m == null || fullPreview == null) return;
            if (m.hasGop() && fullSurface != null) {
                RtspHevcClient prev = fullPreview;
                fullPreview = null;
                prev.detach();
                m.attach(fullSurface);
            } else {
                ui.postDelayed(this, 100);
            }
        }
    };

    private void leaveFull() {
        hideDoorbell();
        fullMode = false;
        ui.removeCallbacks(upgradeCheck);
        fullPreview = null;
        stopFullClient();
        fullSnap = false;
        fullBox.setVisibility(GONE);
        gridBox.setVisibility(VISIBLE);
        { AnnexBDecoder ld2 = liveDecoder(current); if (ld2 != null) ld2.detach(); }
        View f = tileFrame[current];
        if (f != null) f.requestFocus();
        for (int ch = 1; ch <= tiles; ch++) startTile(ch);
        ui.post(euRotate);
    }

    private void switchTo(int ch) {
        { AnnexBDecoder ld2 = liveDecoder(current); if (ld2 != null) ld2.detach(); }
        current = ch;
        RtspHevcClient oldPreview = fullPreview;
        ui.removeCallbacks(upgradeCheck);
        fullPreview = null;
        if (oldPreview != null) oldPreview.detach();
        stopFullClient();
        fullLabel.setText(tileName(ch));
        fullImage.setImageDrawable(null);
        fullSubtype = 0;
        fullSnap = false;
        showPreviewThenMain();
    }

    private void startFullClient() {
        stopFullClient();
        if (fullSurface == null || host == null) { fullStatus.setText("Loading…"); return; }
        fullStatus.setText("Connecting…");
        final int ch = current, sub = fullSubtype;
        RtspHevcClient c = new RtspHevcClient(host, user, pass, ch, sub, new RtspHevcClient.Listener() {
            @Override public void onVideoSize(int w, int h) { ui.post(() -> { fullSize[0] = w; fullSize[1] = h; applyTransform(fullVideo, w, h, false); }); }
            @Override public void onFirstFrame() { ui.post(() -> fullStatus.setText((sub == 0 ? "Main stream" : "Sub stream") + "   ·   ◀ ▶ switch   ·   Back: grid")); }
            @Override public void onError(String m) { ui.post(() -> fullFailed(ch, sub, m)); }
        });
        fullClient = c;
        c.start(fullSurface);
    }

    private void stopFullClient() {
        RtspHevcClient c = fullClient;
        fullClient = null;
        if (c != null) worker.execute(c::stop);
    }

    private void fullFailed(int ch, int sub, String msg) {
        if (!fullMode || ch != current || sub != fullSubtype) return;
        Log.w(TAG, "full ch" + ch + " subtype " + sub + " failed: " + msg);
        fullClient = null;
        if (sub == 0) { // main stream too heavy or unavailable: try the sub-stream
            fullSubtype = 1;
            startFullClient();
        } else {
            fullSnap = true;
            fullStatus.setText("Live stream unavailable — showing snapshots");
            ensureSnapshotLoop();
        }
    }

    // ------------------------------------------------------------ snapshot fallback

    private void ensureSnapshotLoop() {
        if (snapRunning) return;
        snapRunning = true;
        final int gen = ++snapGen;
        worker.execute(() -> {
            int rr = 0;
            while (gen == snapGen) {
                int target = -1;
                boolean full = false;
                if (fullMode && fullSnap) { target = current; full = true; }
                else if (!fullMode) {
                    synchronized (snapChannels) {
                        if (!snapChannels.isEmpty()) {
                            Integer[] a = snapChannels.toArray(new Integer[0]);
                            target = a[rr++ % a.length];
                        }
                    }
                }
                if (target < 0) { sleep(500); continue; }
                try {
                    byte[] jpg = snap.snapshot(target);
                    Bitmap bmp = decode(jpg, full ? 1536 : 640);
                    final int t = target;
                    final boolean f = full;
                    if (gen == snapGen && bmp != null) ui.post(() -> {
                        if (f) fullImage.setImageBitmap(bmp);
                        else { tileImage[t].setImageBitmap(bmp); tileStatus[t].setText(""); }
                    });
                } catch (Exception e) {
                    sleep(2000);
                }
                sleep(full ? 150 : 300);
            }
            snapRunning = false;
        });
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private static Bitmap decode(byte[] jpg, int targetWidth) {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpg, 0, jpg.length, b);
        int sample = 1;
        while (b.outWidth / (sample * 2) >= targetWidth) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(jpg, 0, jpg.length, o);
    }

    // ------------------------------------------------------------ video geometry

    /** Scale the (stretched-to-view) video so it keeps its aspect: fill=true crops, fill=false letterboxes. */
    private void applyTransform(TextureView tv, int vw, int vh, boolean fill) {
        int tw = tv.getWidth(), th = tv.getHeight();
        if (tw == 0 || th == 0 || vw == 0 || vh == 0) return;
        float s = fill ? Math.max((float) tw / vw, (float) th / vh) : Math.min((float) tw / vw, (float) th / vh);
        Matrix m = new Matrix();
        m.setScale(vw * s / tw, vh * s / th, tw / 2f, th / 2f);
        tv.setTransform(m);
    }
}
