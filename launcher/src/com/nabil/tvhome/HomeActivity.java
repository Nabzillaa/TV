package com.nabil.tvhome;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONObject;
import java.io.FileReader;
import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal Apple TV-style home with nerd extras: Ken Burns wallpapers with parallax, a magnifying
 * dock, a live system HUD, a neofetch panel (Up / Menu), ambient mode and a Konami-code Matrix rain.
 */
public class HomeActivity extends Activity {
    private static final String[] DEFAULT_DOCK = {
        "com.mega.ibo", "com.cltv.hybrid", "tv.twitch.android.app",
        "au.net.abc.iview", "com.netflix.ninja", "com.google.android.youtube.tv"
    };
    private static final String[] CAPTIONS = {
        "Rainbow over Washfold, England · Kreuzschnabel",
        "Swaledale from Kisdon Hill, England · Kreuzschnabel",
        "Vallée de Gavarnie, Pyrenees · Moahim",
        "Alone in the wilderness · David Marcu",
        "Dawn on Lake Titicaca, Peru · Diego Delso",
        "Berchtesgaden Alps, Germany · Dmytro Balkhovitin",
        "Sunrise, Bieszczady, Poland · Pudelek"
    };
    private static final int[] KONAMI = {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT
    };
    private static final String SETTINGS_PKG = "com.android.tv.settings";
    private static final int WALLPAPER_COUNT = 7;
    private static final long ROTATE_MS = 60_000;
    private static final long FADE_MS = 1_800;
    private static final long IDLE_MS = 90_000;
    private static final int CYAN = 0xFF64D2FF, GREEN = 0xFF30D158, ORANGE = 0xFFFF9F0A, RED = 0xFFFF453A;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Random random = new Random();

    // wallpaper
    private FrameLayout wallRoot;
    private ImageView wallA, wallB, wallFront;
    private int wallIndex = -1;
    private int panDir = 1;
    private boolean rotating;
    private TextView caption;

    // chrome
    private LinearLayout dock;
    private View hud;
    private TextView greeting;
    private FrameLayout allAppsLayer, neofetchLayer;
    private GridView grid;
    private AppsAdapter adapter;
    private MatrixView matrix;
    private TextView banner;
    private CameraLayer cameras;
    private View cameraDockItem;
    private TextView wxIcon, wxTemp, wxDetail;
    private static final long WX_REFRESH_MS = 30 * 60_000L;
    private boolean ambient;

    // HUD widgets
    private GraphView cpuGraph;
    private TextView cpuVal, ramVal, netVal, upVal, tempVal;
    private BarView ramBar;
    private int konamiPos;

    private Map<String, AppEntry> apps = new LinkedHashMap<>();
    private final Map<String, Drawable> iconCache = new HashMap<>();
    private SharedPreferences prefs;
    private float dp;

    static class AppEntry {
        String pkg;
        CharSequence label;
        ComponentName component;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        buildUi();
    }

    private int dp(float v) { return Math.round(v * dp); }

    // ------------------------------------------------------------------ UI

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);

        wallRoot = new FrameLayout(this);
        wallA = newWall();
        wallB = newWall();
        wallRoot.addView(wallA, new FrameLayout.LayoutParams(-1, -1));
        wallRoot.addView(wallB, new FrameLayout.LayoutParams(-1, -1));
        wallFront = wallA;
        root.addView(wallRoot, new FrameLayout.LayoutParams(-1, -1));

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x99000000}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, dp(220), Gravity.BOTTOM));
        View topScrim = new View(this);
        topScrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x66000000, 0x00000000}));
        root.addView(topScrim, new FrameLayout.LayoutParams(-1, dp(120), Gravity.TOP));

        buildClock(root);
        buildHud(root);

        caption = new TextView(this);
        caption.setTextColor(0xCCFFFFFF);
        caption.setTextSize(15);
        caption.setShadowLayer(dp(4), 0, dp(1), 0xCC000000);
        caption.setAlpha(0f);
        FrameLayout.LayoutParams capLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        capLp.setMargins(dp(40), 0, 0, dp(28));
        root.addView(caption, capLp);

        buildDock(root);
        buildAllAppsLayer(root);
        buildNeofetch(root);
        cameras = new CameraLayer(this, ui, prefs);
        cameras.reload();
        root.addView(cameras, new FrameLayout.LayoutParams(-1, -1));

        matrix = new MatrixView(this);
        matrix.setVisibility(View.GONE);
        root.addView(matrix, new FrameLayout.LayoutParams(-1, -1));

        banner = new TextView(this);
        banner.setTextColor(Color.BLACK);
        banner.setTextSize(54);
        banner.setTypeface(Typeface.create("sans-serif-black", Typeface.BOLD));
        banner.setGravity(Gravity.CENTER);
        banner.setPadding(dp(30), dp(18), dp(30), dp(18));
        GradientDrawable bb = new GradientDrawable();
        bb.setColor(0xFFFFD60A);
        bb.setCornerRadius(dp(18));
        bb.setStroke(dp(4), Color.BLACK);
        banner.setBackground(bb);
        banner.setElevation(dp(16));
        banner.setVisibility(View.GONE);
        root.addView(banner, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        setContentView(root);
        handleBanner(getIntent());
    }

    /** Usage: adb shell am start -n com.nabil.tvhome/.HomeActivity --es banner "text" */
    private void handleNvr(Intent i) {
        BaseusSetup.handle(this, prefs, i);
        boolean eufyHideChange = i != null && i.hasExtra("eufy_hide");
        EufySetup.handle(this, prefs, i);
        if (eufyHideChange) { cameras.reload(); rebuildDock(); }
        String show = i == null ? null : i.getStringExtra("show");      // --es show cameras|doorbell
        if (show != null) {
            i.removeExtra("show");
            ui.post(() -> { if (show.equals("doorbell")) cameras.showDoorbell(); else cameras.open(); });
        }
        String db = i == null ? null : i.getStringExtra("doorbell");     // --es doorbell "Name|https://home.google.com/..." (or clear)
        if (db != null) {
            i.removeExtra("doorbell");
            String[] d = db.split("\\|", 2);
            if (db.equals("clear")) prefs.edit().remove("db_name").remove("db_url").apply();
            else if (d.length == 2 && d[1].startsWith("https://")) prefs.edit().putString("db_name", d[0]).putString("db_url", d[1]).apply();
            cameras.reload();
        }
        String v = i == null ? null : i.getStringExtra("nvr");
        if (v == null || v.isEmpty()) return;
        i.removeExtra("nvr");
        if (v.equals("clear")) {
            prefs.edit().remove("nvr_host").remove("nvr_user").remove("nvr_pass").remove("nvr_n").apply();
        } else {
            String[] f = v.split("\\|", -1);
            if (f.length < 3) return;
            int n = 4;
            try { if (f.length > 3) n = Integer.parseInt(f[3]); } catch (NumberFormatException ignored) { }
            prefs.edit().putString("nvr_host", f[0]).putString("nvr_user", f[1]).putString("nvr_pass", f[2]).putInt("nvr_n", n).apply();
        }
        cameras.reload();
        rebuildDock();
        Toast.makeText(this, cameras.configured() ? "NVR saved" : "NVR settings cleared", Toast.LENGTH_SHORT).show();
    }

    private void handleBanner(Intent i) {
        handleNvr(i);
        String t = i == null ? null : i.getStringExtra("banner");
        if (t == null || t.isEmpty()) return;
        i.removeExtra("banner");
        ui.postDelayed(() -> showBanner(t), 400);
    }

    private void showBanner(String text) {
        banner.setText("\uD83D\uDEA8 " + text.toUpperCase() + " \uD83D\uDEA8");
        banner.setRotation(-3f);
        banner.setTranslationY(-dp(500));
        banner.setScaleX(1f);
        banner.setScaleY(1f);
        banner.setVisibility(View.VISIBLE);
        banner.animate().translationY(0).setDuration(700).setInterpolator(new android.view.animation.BounceInterpolator())
                .withEndAction(this::pulseBanner).start();
        ui.removeCallbacks(hideBanner);
        ui.postDelayed(hideBanner, 30_000);
    }

    private void pulseBanner() {
        if (banner.getVisibility() != View.VISIBLE) return;
        banner.animate().scaleX(1.06f).scaleY(1.06f).rotation(3f).setDuration(450).withEndAction(() -> {
            if (banner.getVisibility() != View.VISIBLE) return;
            banner.animate().scaleX(1f).scaleY(1f).rotation(-3f).setDuration(450).withEndAction(this::pulseBanner).start();
        }).start();
    }

    private final Runnable hideBanner = () -> {
        banner.animate().cancel();
        banner.animate().translationY(-dp(500)).setDuration(400).withEndAction(() -> banner.setVisibility(View.GONE)).start();
    };

    private ImageView newWall() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        v.setAlpha(0f);
        return v;
    }

    private void buildClock(FrameLayout root) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.END);
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(64);
        clock.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setShadowLayer(dp(6), 0, dp(1), 0x99000000);
        TextClock date = new TextClock(this);
        date.setFormat12Hour("EEEE d MMMM");
        date.setFormat24Hour("EEEE d MMMM");
        date.setTextColor(0xEEFFFFFF);
        date.setTextSize(18);
        date.setShadowLayer(dp(5), 0, dp(1), 0x99000000);
        greeting = new TextView(this);
        greeting.setTextColor(0xBBFFFFFF);
        greeting.setTextSize(15);
        greeting.setGravity(Gravity.END);
        greeting.setShadowLayer(dp(5), 0, dp(1), 0x99000000);
        clock.setGravity(Gravity.END);
        date.setGravity(Gravity.END);
        box.addView(clock, new LinearLayout.LayoutParams(-1, -2));
        box.addView(date, new LinearLayout.LayoutParams(-1, -2));
        box.addView(greeting, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout wx = new LinearLayout(this);
        wx.setOrientation(LinearLayout.HORIZONTAL);
        wx.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        wx.setPadding(dp(14), dp(8), dp(16), dp(8));
        GradientDrawable wxBg = new GradientDrawable();
        wxBg.setColor(0x59000000);
        wxBg.setCornerRadius(dp(16));
        wxBg.setStroke(Math.max(1, dp(0.5f)), 0x33FFFFFF);
        wx.setBackground(wxBg);
        wxIcon = new TextView(this);
        wxIcon.setTextSize(34);
        wxIcon.setText("\u2601\uFE0F");
        wxTemp = new TextView(this);
        wxTemp.setTextColor(Color.WHITE);
        wxTemp.setTextSize(34);
        wxTemp.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        wxTemp.setText("--\u00b0");
        wxTemp.setPadding(dp(8), 0, dp(12), 0);
        wxDetail = new TextView(this);
        wxDetail.setTextColor(0xDDFFFFFF);
        wxDetail.setTextSize(13);
        wxDetail.setLineSpacing(0, 1.1f);
        wxDetail.setText("Loading weather\u2026");
        wx.addView(wxIcon);
        wx.addView(wxTemp);
        wx.addView(wxDetail);
        LinearLayout.LayoutParams wxLp = new LinearLayout.LayoutParams(-2, -2);
        wxLp.topMargin = dp(10);
        wxLp.gravity = Gravity.END;
        box.addView(wx, wxLp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        lp.setMargins(0, dp(24), dp(40), 0);
        root.addView(box, lp);
    }

    private TextView mono(String text, int color, float sp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setTypeface(Typeface.MONOSPACE);
        t.setSingleLine(true);
        return t;
    }

    private LinearLayout hudRow(String label, View... parts) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = mono(label, CYAN, 12);
        r.addView(l, new LinearLayout.LayoutParams(dp(34), -2));
        for (View p : parts) r.addView(p);
        return r;
    }

    private void buildHud(FrameLayout root) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(14), dp(8));
        GradientDrawable bgd = new GradientDrawable();
        bgd.setColor(0x66000000);
        bgd.setCornerRadius(dp(12));
        bgd.setStroke(Math.max(1, dp(0.5f)), 0x33FFFFFF);
        box.setBackground(bgd);

        TextView title = mono("nabil@chiq", GREEN, 12);
        box.addView(title);

        cpuGraph = new GraphView(this);
        cpuVal = mono("--%", Color.WHITE, 12);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(56), dp(14));
        gp.rightMargin = dp(6);
        box.addView(hudRow("CPU", wrap(cpuGraph, gp), cpuVal));

        ramBar = new BarView(this);
        ramVal = mono("--", Color.WHITE, 12);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(56), dp(8));
        bp.rightMargin = dp(6);
        box.addView(hudRow("RAM", wrap(ramBar, bp), ramVal));

        netVal = mono("--", Color.WHITE, 12);
        box.addView(hudRow("NET", netVal));
        tempVal = mono("--", Color.WHITE, 12);
        box.addView(hudRow("DSK", tempVal));
        upVal = mono("--", Color.WHITE, 12);
        box.addView(hudRow("UP", upVal));

        hud = box;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lp.setMargins(dp(24), dp(24), 0, 0);
        root.addView(box, lp);
    }

    private View wrap(View v, LinearLayout.LayoutParams lp) { v.setLayoutParams(lp); return v; }

    private void buildDock(FrameLayout root) {
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        dock.setPadding(dp(20), dp(14), dp(20), dp(8));
        GradientDrawable dockBg = new GradientDrawable();
        dockBg.setColor(0x73262628);
        dockBg.setCornerRadius(dp(28));
        dockBg.setStroke(Math.max(1, dp(0.5f)), 0x33FFFFFF);
        dock.setBackground(dockBg);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(28);
        root.addView(dock, lp);
    }

    private void buildAllAppsLayer(FrameLayout root) {
        allAppsLayer = new FrameLayout(this);
        allAppsLayer.setBackgroundColor(0xF2101012);
        allAppsLayer.setVisibility(View.GONE);

        TextView title = new TextView(this);
        title.setText("All apps");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tp.setMargins(dp(48), dp(28), 0, 0);
        allAppsLayer.addView(title, tp);

        TextView hint = new TextView(this);
        hint.setText("Hold OK on an app to add it to / remove it from the dock");
        hint.setTextColor(0x99FFFFFF);
        hint.setTextSize(15);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        hp.setMargins(0, dp(40), dp(48), 0);
        allAppsLayer.addView(hint, hp);

        grid = new GridView(this);
        grid.setNumColumns(7);
        grid.setVerticalSpacing(dp(10));
        grid.setHorizontalSpacing(dp(6));
        grid.setClipToPadding(false);
        GradientDrawable sel = new GradientDrawable();
        sel.setColor(0x40FFFFFF);
        sel.setCornerRadius(dp(16));
        grid.setSelector(sel);
        grid.setPadding(dp(40), dp(12), dp(40), dp(24));
        adapter = new AppsAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> launch(adapter.getItem(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> { toggleDock(adapter.getItem(pos)); return true; });
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(-1, -1);
        gp.topMargin = dp(88);
        allAppsLayer.addView(grid, gp);
        root.addView(allAppsLayer, new FrameLayout.LayoutParams(-1, -1));
    }

    private static final String[] ANDROID_ART = {
        "    ;,           ,;   ",
        "     ';,.-----.,;'    ",
        "    ,'           ',   ",
        "   /    O     O    \\  ",
        "  |                 | ",
        "  '-----------------' ",
        "   ,-----------------.",
        "  /|                 |",
        " / |                 |",
        "   |                 |",
        "   |                 |",
        "   '-----------------'",
    };

    private void buildNeofetch(FrameLayout root) {
        neofetchLayer = new FrameLayout(this);
        neofetchLayer.setBackgroundColor(0xEE06100A);
        neofetchLayer.setVisibility(View.GONE);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag("row");
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        neofetchLayer.addView(row, rp);
        root.addView(neofetchLayer, new FrameLayout.LayoutParams(-1, -1));
    }

    private void fillNeofetch() {
        LinearLayout row = (LinearLayout) neofetchLayer.findViewWithTag("row");
        row.removeAllViews();
        StringBuilder art = new StringBuilder();
        for (String l : ANDROID_ART) art.append(l).append('\n');
        TextView a = mono(art.toString(), GREEN, 17);
        a.setSingleLine(false);
        a.setLineSpacing(0, 1.05f);
        row.addView(a);

        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        long total = fs.getTotalBytes(), free = fs.getAvailableBytes();
        int frozen = 0, installed = 0;
        for (ApplicationInfo ai : getPackageManager().getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)) {
            installed++;
            if (!ai.enabled) frozen++;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        line(sb, "", "nabil", "@", "chiq");
        sb.append("-------------\n");
        line(sb, "OS", "Android " + Build.VERSION.RELEASE + " (Google TV, API " + Build.VERSION.SDK_INT + ")");
        line(sb, "Host", Build.MANUFACTURER + " " + Build.MODEL);
        line(sb, "Kernel", System.getProperty("os.version", "?"));
        line(sb, "Uptime", uptime());
        line(sb, "Packages", installed + " installed, " + frozen + " frozen");
        line(sb, "Shell", "tvhome 1.1 (Java, " + (Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?") + ")");
        line(sb, "Display", getResources().getDisplayMetrics().widthPixels + "x" + getResources().getDisplayMetrics().heightPixels
                + " @" + Math.round(getResources().getDisplayMetrics().density * 160) + "dpi");
        line(sb, "Memory", mb(mi.totalMem - mi.availMem) + " / " + mb(mi.totalMem));
        line(sb, "Disk", gb(total - free) + " / " + gb(total));
        line(sb, "Network", netString());
        line(sb, "Ads", "0 (blocked by vibes)");
        sb.append('\n');
        for (int c : new int[]{0xFFFF453A, ORANGE, 0xFFFFD60A, GREEN, CYAN, 0xFF0A84FF, 0xFFBF5AF2, Color.WHITE}) {
            int s = sb.length();
            sb.append("███");
            sb.setSpan(new ForegroundColorSpan(c), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView info = mono("", Color.WHITE, 16);
        info.setSingleLine(false);
        info.setText(sb);
        info.setPadding(dp(28), 0, 0, 0);
        row.addView(info);
    }

    private void line(SpannableStringBuilder sb, String key, String... parts) {
        if (!key.isEmpty()) {
            int s = sb.length();
            sb.append(key).append(": ");
            sb.setSpan(new ForegroundColorSpan(GREEN), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append(parts[0]).append('\n');
        } else {
            int s = sb.length();
            sb.append(parts[0]);
            sb.setSpan(new ForegroundColorSpan(GREEN), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s = sb.length();
            sb.append(parts[1]).append(parts[2]).append('\n');
            sb.setSpan(new ForegroundColorSpan(Color.WHITE), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        loadApps();
        rebuildDock();
        adapter.notifyDataSetChanged();
        updateGreeting();
        startWallpapers();
        startHud();
        startWeather();
        resetIdle();
        if (allAppsLayer.getVisibility() != View.VISIBLE && neofetchLayer.getVisibility() != View.VISIBLE) {
            if (returnToClean != null) returnToClean.requestFocus();         // back from the Shizuku prompt
            else focusDock(0);
        }
        returnToClean = null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        rotating = false;
        ui.removeCallbacksAndMessages(null);
        matrix.stop();
        cameras.shutdown();
        wallA.animate().cancel();
        wallB.animate().cancel();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        closeOverlays();
        focusDock(0);
        handleBanner(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bg.shutdownNow();
    }

    private void closeOverlays() {
        showAllApps(false);
        neofetchLayer.setVisibility(View.GONE);
        cameras.close();
        if (matrix.getVisibility() == View.VISIBLE) matrix.stop();
        exitAmbient();
    }

    @Override
    public void onBackPressed() {
        if (matrix.getVisibility() == View.VISIBLE) matrix.stop();
        else if (cameras.isOpen()) {
            cameras.back();
            if (!cameras.isOpen() && cameraDockItem != null) cameraDockItem.requestFocus(); // back on the Cameras icon
        }
        else if (neofetchLayer.getVisibility() == View.VISIBLE) neofetchLayer.setVisibility(View.GONE);
        else if (allAppsLayer.getVisibility() == View.VISIBLE) showAllApps(false);
        // on the home screen Back does nothing
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            resetIdle();
            if (banner.getVisibility() == View.VISIBLE) { ui.removeCallbacks(hideBanner); hideBanner.run(); return true; }
            if (matrix.getVisibility() == View.VISIBLE) { matrix.stop(); return true; }
            trackKonami(e.getKeyCode());
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        boolean base = allAppsLayer.getVisibility() != View.VISIBLE && neofetchLayer.getVisibility() != View.VISIBLE && !cameras.isOpen();
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (!base && allAppsLayer.getVisibility() == View.VISIBLE) {
                int pos = grid.getSelectedItemPosition();
                if (pos >= 0) toggleDock(adapter.getItem(pos));
            } else if (base) {
                showNeofetch();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && base) {
            if (konamiPos < 2) showNeofetch(); // the first two Ups of the Konami code won't pop it open twice
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    private void showNeofetch() {
        if (neofetchLayer.getVisibility() == View.VISIBLE) return;
        fillNeofetch();
        neofetchLayer.setAlpha(0f);
        neofetchLayer.setVisibility(View.VISIBLE);
        neofetchLayer.animate().alpha(1f).setDuration(180).start();
    }

    // ------------------------------------------------------------------ konami / matrix / ambient

    private void trackKonami(int code) {
        if (code == KONAMI[konamiPos]) {
            konamiPos++;
            if (konamiPos == KONAMI.length) {
                konamiPos = 0;
                neofetchLayer.setVisibility(View.GONE);
                matrix.start();
            }
        } else {
            konamiPos = code == KONAMI[0] ? 1 : 0;
        }
    }

    private final Runnable idleRunnable = this::enterAmbient;

    private void resetIdle() {
        ui.removeCallbacks(idleRunnable);
        if (ambient) exitAmbient();
        ui.postDelayed(idleRunnable, IDLE_MS);
    }

    private void enterAmbient() {
        if (allAppsLayer.getVisibility() == View.VISIBLE || neofetchLayer.getVisibility() == View.VISIBLE || cameras.isOpen()) {
            ui.postDelayed(idleRunnable, IDLE_MS);
            return;
        }
        ambient = true;
        dock.animate().alpha(0f).translationY(dp(30)).setDuration(900).start();
        hud.animate().alpha(0f).setDuration(900).start();
        caption.animate().alpha(1f).setDuration(1200).start();
    }

    private void exitAmbient() {
        if (!ambient && dock.getAlpha() == 1f) return;
        ambient = false;
        dock.animate().alpha(1f).translationY(0).setDuration(250).start();
        hud.animate().alpha(1f).setDuration(250).start();
        caption.animate().alpha(0f).setDuration(250).start();
    }

    private void updateGreeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String g = h < 5 ? "Good night" : h < 12 ? "Good morning" : h < 18 ? "Good afternoon" : "Good evening";
        greeting.setText(g + ", Nabil");
    }

    // ------------------------------------------------------------------ apps & dock

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Map<String, AppEntry> found = new HashMap<>();
        for (String cat : new String[]{Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER}) {
            List<ResolveInfo> l = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0);
            for (ResolveInfo ri : l) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName())) continue;
                if (found.containsKey(pkg) && !cat.equals(Intent.CATEGORY_LEANBACK_LAUNCHER)) continue;
                AppEntry a = new AppEntry();
                a.pkg = pkg;
                a.label = ri.loadLabel(pm);
                a.component = new ComponentName(pkg, ri.activityInfo.name);
                found.put(pkg, a);
            }
        }
        List<AppEntry> sorted = new ArrayList<>(found.values());
        Collections.sort(sorted, (x, y) -> x.label.toString().compareToIgnoreCase(y.label.toString()));
        Map<String, AppEntry> m = new LinkedHashMap<>();
        for (AppEntry a : sorted) m.put(a.pkg, a);
        apps = m;
        adapter.setData(sorted);
    }

    private List<String> dockPackages() {
        String saved = prefs.getString("dock", null);
        List<String> out = new ArrayList<>();
        if (saved == null) Collections.addAll(out, DEFAULT_DOCK);
        else if (!saved.isEmpty()) Collections.addAll(out, saved.split(","));
        return out;
    }

    private void toggleDock(AppEntry a) {
        List<String> d = dockPackages();
        if (d.contains(a.pkg)) {
            d.remove(a.pkg);
            Toast.makeText(this, a.label + " removed from dock", Toast.LENGTH_SHORT).show();
        } else if (d.size() >= 8) {
            Toast.makeText(this, "Dock is full (8). Remove one first.", Toast.LENGTH_SHORT).show();
            return;
        } else {
            d.add(a.pkg);
            Toast.makeText(this, a.label + " added to dock", Toast.LENGTH_SHORT).show();
        }
        prefs.edit().putString("dock", String.join(",", d)).apply();
        rebuildDock();
        adapter.notifyDataSetChanged();
    }

    private Drawable icon(AppEntry a) {
        Drawable d = iconCache.get(a.pkg);
        if (d == null) {
            try {
                d = getPackageManager().getApplicationIcon(a.pkg);
            } catch (PackageManager.NameNotFoundException e) {
                d = new GradientDrawable();
            }
            iconCache.put(a.pkg, d);
        }
        return d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
    }

    private void launch(AppEntry a) {
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).setComponent(a.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (Exception e) {
            Toast.makeText(this, "Can't open " + a.label, Toast.LENGTH_SHORT).show();
        }
    }

    private void rebuildDock() {
        dock.removeAllViews();
        for (String pkg : dockPackages()) {
            AppEntry a = apps.get(pkg);
            if (a == null) continue;
            dock.addView(dockItem(icon(a), a.label, () -> launch(a)));
        }
        cameraDockItem = null;
        if (cameras != null && cameras.configured())
            dock.addView(cameraDockItem = dockItem(new android.graphics.drawable.BitmapDrawable(getResources(), cameraGlyph()),
                    "Cameras", () -> cameras.open(), () -> ui.postDelayed(() -> { if (dock.findFocus() != null && cameras != null) cameras.prewarm(); }, 250)));
        View clean = dockItem(new android.graphics.drawable.BitmapDrawable(getResources(), cleanGlyph()), "Clean up", null);
        clean.setOnClickListener(v -> cleanUp(v));
        dock.addView(clean);
        dock.addView(dockItem(new android.graphics.drawable.BitmapDrawable(getResources(), gridGlyph()),
                "All apps", () -> showAllApps(true)));
        AppEntry s = apps.get(SETTINGS_PKG);
        if (s != null) dock.addView(dockItem(icon(s), "Settings", () -> launch(s)));
    }

    private View dockItem(Drawable icon, CharSequence label, Runnable action) { return dockItem(icon, label, action, null); }

    private View dockItem(Drawable icon, CharSequence label, Runnable action, Runnable onFocus) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setClipChildren(false);
        item.setFocusable(true);
        item.setPadding(dp(5), 0, dp(5), 0);

        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14)); }
        });
        iv.setClipToOutline(true);
        item.addView(iv, new LinearLayout.LayoutParams(dp(64), dp(64)));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13);
        tv.setAlpha(0f);
        tv.setTag("label");
        tv.setShadowLayer(dp(4), 0, dp(1), 0xCC000000);
        item.addView(tv, new LinearLayout.LayoutParams(dp(70), dp(24)));

        item.setOnClickListener(v -> {
            v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(70).withEndAction(() -> {
                v.animate().scaleX(1.22f).scaleY(1.22f).setInterpolator(new OvershootInterpolator(3f)).setDuration(220).start();
                action.run();
            }).start();
        });
        item.setOnFocusChangeListener((v, focused) -> { if (focused) { magnify(dock.indexOfChild(v)); if (onFocus != null) onFocus.run(); } });
        return item;
    }

    /** macOS-style magnification: focused icon biggest, immediate neighbours slightly raised. */
    private void magnify(int focusIdx) {
        int n = dock.getChildCount();
        for (int i = 0; i < n; i++) {
            View c = dock.getChildAt(i);
            int d = Math.abs(i - focusIdx);
            float s = d == 0 ? 1.22f : d == 1 ? 1.08f : 1f;
            float ty = d == 0 ? -dp(8) : d == 1 ? -dp(3) : 0;
            c.animate().scaleX(s).scaleY(s).translationY(ty)
                    .setInterpolator(d == 0 ? new OvershootInterpolator(2.2f) : new LinearInterpolator())
                    .setDuration(d == 0 ? 220 : 140).start();
            View label = c.findViewWithTag("label");
            if (label != null) label.animate().alpha(d == 0 ? 1f : 0f).setDuration(140).start();
            c.setElevation(d == 0 ? dp(8) : 0);
        }
        // parallax: the wallpaper drifts opposite to the focus position
        float mid = (n - 1) / 2f;
        wallRoot.animate().translationX(-(focusIdx - mid) * dp(5)).setDuration(450).start();
    }

    /** Blue gradient tile with a glossy top, a white video camera with a lens and a red REC dot. Drawn at 2x so it stays sharp when magnified. */
    private Bitmap cameraGlyph() {
        int s = dp(64) * 2;
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new android.graphics.LinearGradient(0, 0, 0, s, 0xFF5AC8FA, 0xFF0A4FC8, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, s, s, p);
        p.setShader(new android.graphics.RadialGradient(s * 0.5f, -s * 0.15f, s * 0.75f, 0x38FFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, s, s, p);
        p.setShader(null);

        // camera body + viewfinder, with a soft drop shadow
        android.graphics.Path cam = new android.graphics.Path();
        cam.addRoundRect(new RectF(s * 0.15f, s * 0.31f, s * 0.65f, s * 0.69f), s * 0.09f, s * 0.09f, android.graphics.Path.Direction.CW);
        cam.addRect(s * 0.60f, s * 0.44f, s * 0.72f, s * 0.56f, android.graphics.Path.Direction.CW);
        android.graphics.Path finder = new android.graphics.Path();
        finder.moveTo(s * 0.69f, s * 0.45f); finder.lineTo(s * 0.85f, s * 0.35f); finder.lineTo(s * 0.85f, s * 0.65f); finder.lineTo(s * 0.69f, s * 0.55f); finder.close();
        p.setColor(Color.WHITE);
        p.setStyle(Paint.Style.FILL_AND_STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(s * 0.035f);
        p.setShadowLayer(s * 0.035f, 0, s * 0.025f, 0x59001446);
        c.drawPath(cam, p);
        c.drawPath(finder, p);
        p.clearShadowLayer();
        p.setStyle(Paint.Style.FILL);

        // lens
        float cx = s * 0.40f, cy = s * 0.50f;
        p.setColor(0xFF2D8BE0);
        c.drawCircle(cx, cy, s * 0.13f, p);
        p.setColor(0xFF0C286E);
        c.drawCircle(cx, cy, s * 0.085f, p);
        p.setColor(0xFF46A6EB);
        c.drawCircle(cx, cy, s * 0.045f, p);
        p.setColor(0xC8FFFFFF);
        c.drawCircle(cx - s * 0.043f, cy - s * 0.052f, s * 0.018f, p);

        p.setColor(0xFFFF3B30);
        c.drawCircle(s * 0.215f, s * 0.39f, s * 0.028f, p);
        return bmp;
    }

    // ------------------------------------------------------------------ clean up

    private boolean cleaning;
    private View returnToClean;                           // keep focus on Clean up across Shizuku's permission prompt

    /**
     * Free memory and storage without losing anything: with Shizuku running (adb-level rights), `am kill-all` stops the
     * processes Android itself considers safe to stop (idle, cached) and `pm trim-caches` empties every app's cache;
     * data and sign-ins are untouched, apps just cold-start next time. Without Shizuku, Android 14 only lets the launcher
     * free its own memory and cache.
     */
    private void cleanUp(View item) {
        if (cleaning) return;
        int shizuku = shizukuState();
        if (shizuku == SHIZUKU_ASK) {
            returnToClean = item;
            Toast.makeText(this, "Allow Home in the Shizuku prompt, then press Clean up again", Toast.LENGTH_LONG).show();
            return;
        }
        cleaning = true;
        ((ViewGroup) item).getChildAt(0).animate().rotationBy(360).setDuration(1200).start();   // spin the icon
        final ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        final long memBefore = availableMemory(am);
        final long diskBefore = getFilesDir().getUsableSpace();
        cameras.release();
        bg.execute(() -> {
            if (shizuku == SHIZUKU_READY) {
                shell("am kill-all");
                shell("pm trim-caches 999G");
            } else {
                clearDir(getCacheDir());
            }
            Runtime.getRuntime().gc();
            ui.postDelayed(() -> {                      // let the system reclaim the memory before measuring
                cleaning = false;
                long mem = Math.max(0, availableMemory(am) - memBefore) / (1024 * 1024);
                long disk = Math.max(0, getFilesDir().getUsableSpace() - diskBefore) / (1024 * 1024);
                String msg = "Cleaned up · " + (mem >= 10 ? mem + " MB memory" : "memory already tidy")
                        + (disk >= 1 ? " · " + disk + " MB storage" : "");
                if (shizuku == SHIZUKU_OFF) msg += "\nStart Shizuku to also close background apps and clear their caches";
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            }, 1500);
        });
    }

    private static final int SHIZUKU_OFF = 0, SHIZUKU_ASK = 1, SHIZUKU_READY = 2;

    /** Is Shizuku running, and may the launcher use it? Asks for permission (once) when it's running but not granted. */
    private int shizukuState() {
        try {
            if (!rikka.shizuku.Shizuku.pingBinder()) return SHIZUKU_OFF;
            if (rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) return SHIZUKU_READY;
            rikka.shizuku.Shizuku.requestPermission(1);
            return SHIZUKU_ASK;
        } catch (Throwable t) {
            return SHIZUKU_OFF;
        }
    }

    /** Run a command as the shell user through Shizuku (its newProcess isn't public API, hence reflection). */
    private static void shell(String cmd) {
        try {
            java.lang.reflect.Method m = rikka.shizuku.Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Process p = (Process) m.invoke(null, new String[]{"sh", "-c", cmd}, null, null);
            p.waitFor();
        } catch (Throwable t) {
            android.util.Log.w("CleanUp", cmd + ": " + t);
        }
    }

    private static long availableMemory(ActivityManager am) {
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem;
    }

    /** Delete the launcher's own cache files; the browser engine's folders are left alone while it may be running. */
    private static long clearDir(java.io.File dir) {
        long freed = 0;
        java.io.File[] list = dir.listFiles();
        if (list == null) return 0;
        for (java.io.File f : list) {
            String n = f.getName().toLowerCase(java.util.Locale.ROOT);
            if (n.contains("mozilla") || n.contains("gecko")) continue;
            if (f.isDirectory()) { freed += clearDir(f); f.delete(); }
            else { long len = f.length(); if (f.delete()) freed += len; }
        }
        return freed;
    }

    /** Teal-to-green tile with a white broom and sparkles. */
    private Bitmap cleanGlyph() {
        int s = dp(64) * 2;
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new android.graphics.LinearGradient(0, 0, 0, s, 0xFF5EE6C8, 0xFF13A86B, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, s, s, p);
        p.setShader(new android.graphics.RadialGradient(s * 0.5f, -s * 0.15f, s * 0.75f, 0x38FFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP));
        c.drawRect(0, 0, s, s, p);
        p.setShader(null);
        p.setColor(Color.WHITE);
        p.setShadowLayer(s * 0.03f, 0, s * 0.02f, 0x4D00402A);
        // handle
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(s * 0.06f);
        c.drawLine(s * 0.66f, s * 0.20f, s * 0.47f, s * 0.55f, p);
        // bristles
        p.setStyle(Paint.Style.FILL);
        android.graphics.Path head = new android.graphics.Path();
        head.moveTo(s * 0.40f, s * 0.52f);
        head.lineTo(s * 0.56f, s * 0.60f);
        head.lineTo(s * 0.46f, s * 0.82f);
        head.lineTo(s * 0.22f, s * 0.70f);
        head.close();
        c.drawPath(head, p);
        p.clearShadowLayer();
        p.setColor(0x6613A86B);
        p.setStrokeWidth(s * 0.015f);
        p.setStyle(Paint.Style.STROKE);
        for (int i = 1; i <= 3; i++) {
            float t = i / 4f;
            c.drawLine(s * (0.40f + 0.16f * t), s * (0.52f + 0.08f * t), s * (0.22f + 0.24f * t), s * (0.70f + 0.12f * t), p);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.WHITE);
        sparkle(c, p, s * 0.72f, s * 0.62f, s * 0.08f);
        sparkle(c, p, s * 0.28f, s * 0.30f, s * 0.055f);
        sparkle(c, p, s * 0.80f, s * 0.38f, s * 0.04f);
        return bmp;
    }

    /** Four-point star. */
    private static void sparkle(Canvas c, Paint p, float x, float y, float r) {
        android.graphics.Path star = new android.graphics.Path();
        float k = r * 0.28f;
        star.moveTo(x, y - r);
        star.quadTo(x + k, y - k, x + r, y);
        star.quadTo(x + k, y + k, x, y + r);
        star.quadTo(x - k, y + k, x - r, y);
        star.quadTo(x - k, y - k, x, y - r);
        star.close();
        c.drawPath(star, p);
    }

    private Bitmap gridGlyph() {
        int s = dp(64);
        Bitmap bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF2C2C2E);
        c.drawRect(0, 0, s, s, p);
        p.setColor(0xFFF2F2F7);
        for (int r = 0; r < 3; r++)
            for (int col = 0; col < 3; col++) {
                float left = s * 0.2f + col * s * 0.22f;
                float top = s * 0.2f + r * s * 0.22f;
                c.drawRoundRect(new RectF(left, top, left + s * 0.16f, top + s * 0.16f), s * 0.04f, s * 0.04f, p);
            }
        return bmp;
    }

    private void focusDock(int index) {
        if (dock.getChildCount() > index) dock.getChildAt(index).requestFocus();
    }

    private void showAllApps(boolean show) {
        allAppsLayer.setVisibility(show ? View.VISIBLE : View.GONE);
        dock.setVisibility(show ? View.INVISIBLE : View.VISIBLE);
        if (show) {
            grid.requestFocus();
            grid.setSelection(0);
        } else {
            focusDock(0);
        }
    }

    // ------------------------------------------------------------------ wallpapers (Ken Burns)

    private void startWallpapers() {
        if (rotating) return;
        rotating = true;
        wallIndex = random.nextInt(WALLPAPER_COUNT);
        loadWallpaper(wallIndex, false);
        scheduleNext();
    }

    private void scheduleNext() {
        ui.postDelayed(() -> {
            if (!rotating) return;
            wallIndex = (wallIndex + 1) % WALLPAPER_COUNT;
            loadWallpaper(wallIndex, true);
            scheduleNext();
        }, ROTATE_MS);
    }

    private void loadWallpaper(int idx, boolean fade) {
        bg.execute(() -> {
            Bitmap bmp = null;
            try (java.io.InputStream in = getAssets().open("nature" + (idx + 1) + ".jpg")) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.ARGB_8888;
                bmp = BitmapFactory.decodeStream(in, null, o);
            } catch (Exception ignored) { }
            final Bitmap next = bmp;
            if (next == null) return;
            ui.post(() -> showWallpaper(next, idx, fade));
        });
    }

    private void kenBurns(ImageView v) {
        panDir = -panDir;
        v.animate().cancel();
        v.setScaleX(1.05f);
        v.setScaleY(1.05f);
        v.setTranslationX(-panDir * dp(12));
        v.animate().scaleX(1.13f).scaleY(1.13f).translationX(panDir * dp(12))
                .setDuration(ROTATE_MS + FADE_MS * 2).setInterpolator(new LinearInterpolator()).start();
    }

    private void showWallpaper(Bitmap next, int idx, boolean fade) {
        if (!rotating) return;
        final ImageView out = wallFront;
        final ImageView in = out == wallA ? wallB : wallA;
        in.setImageBitmap(next);
        kenBurns(in);
        caption.setText(CAPTIONS[idx]);
        if (!fade) {
            in.setAlpha(1f);
            out.setAlpha(0f);
            wallFront = in;
            return;
        }
        in.setAlpha(0f);
        in.bringToFront();
        in.animate().alpha(1f).setDuration(FADE_MS).withEndAction(() -> {
            out.animate().cancel();
            out.setAlpha(0f);
            out.setImageDrawable(null);
        }).start();
        wallFront = in;
    }

    // ------------------------------------------------------------------ weather (Open-Meteo)

    private void startWeather() {
        showCachedWeather();
        ui.post(new Runnable() {
            @Override public void run() {
                long age = System.currentTimeMillis() - prefs.getLong("wx_time", 0);
                if (age >= WX_REFRESH_MS) bg.execute(HomeActivity.this::fetchWeather);
                ui.postDelayed(this, 5 * 60_000L);
            }
        });
    }

    private static String httpGet(String url) throws java.io.IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private void fetchWeather() {
        try {
            // location: cached for a day; IP geolocation (approximate), override with prefs wx_lat/wx_lon/wx_city
            if (!prefs.contains("wx_lat") || System.currentTimeMillis() - prefs.getLong("wx_loc_time", 0) > 86_400_000L) {
                JSONObject g = new JSONObject(httpGet("https://ipwho.is/"));
                if (g.optBoolean("success", false) && !prefs.getBoolean("wx_pinned", false)) {
                    prefs.edit().putFloat("wx_lat", (float) g.getDouble("latitude"))
                            .putFloat("wx_lon", (float) g.getDouble("longitude"))
                            .putString("wx_city", g.optString("city", ""))
                            .putLong("wx_loc_time", System.currentTimeMillis()).apply();
                }
            }
            if (!prefs.contains("wx_lat")) return;
            String url = "https://api.open-meteo.com/v1/forecast?latitude=" + prefs.getFloat("wx_lat", 0)
                    + "&longitude=" + prefs.getFloat("wx_lon", 0)
                    + "&current=temperature_2m,apparent_temperature,weather_code,is_day,wind_speed_10m"
                    + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                    + "&timezone=auto&forecast_days=1";
            JSONObject j = new JSONObject(httpGet(url));
            JSONObject cur = j.getJSONObject("current");
            JSONObject day = j.getJSONObject("daily");
            prefs.edit()
                    .putFloat("wx_t", (float) cur.getDouble("temperature_2m"))
                    .putFloat("wx_feels", (float) cur.getDouble("apparent_temperature"))
                    .putInt("wx_code", cur.getInt("weather_code"))
                    .putBoolean("wx_day", cur.getInt("is_day") == 1)
                    .putFloat("wx_wind", (float) cur.getDouble("wind_speed_10m"))
                    .putFloat("wx_hi", (float) day.getJSONArray("temperature_2m_max").getDouble(0))
                    .putFloat("wx_lo", (float) day.getJSONArray("temperature_2m_min").getDouble(0))
                    .putInt("wx_rain", day.getJSONArray("precipitation_probability_max").optInt(0, 0))
                    .putLong("wx_time", System.currentTimeMillis()).apply();
            ui.post(this::showCachedWeather);
        } catch (Exception e) {
            ui.post(() -> { if (!prefs.contains("wx_time")) wxDetail.setText("Weather unavailable"); });
        }
    }

    private void showCachedWeather() {
        if (!prefs.contains("wx_time")) return;
        int code = prefs.getInt("wx_code", 3);
        wxIcon.setText(wxEmoji(code, prefs.getBoolean("wx_day", true)));
        wxTemp.setText(Math.round(prefs.getFloat("wx_t", 0)) + "\u00b0");
        String city = prefs.getString("wx_city", "");
        wxDetail.setText((city.isEmpty() ? "" : city + " \u00b7 ") + wxText(code) + "\n"
                + "H " + Math.round(prefs.getFloat("wx_hi", 0)) + "\u00b0  L " + Math.round(prefs.getFloat("wx_lo", 0)) + "\u00b0"
                + "  \u00b7  Feels " + Math.round(prefs.getFloat("wx_feels", 0)) + "\u00b0\n"
                + "Rain " + prefs.getInt("wx_rain", 0) + "%  \u00b7  Wind " + Math.round(prefs.getFloat("wx_wind", 0)) + " km/h");
    }

    private static String wxEmoji(int c, boolean day) {
        if (c == 0) return day ? "\u2600\uFE0F" : "\uD83C\uDF19";
        if (c <= 2) return day ? "\uD83C\uDF24\uFE0F" : "\u2601\uFE0F";
        if (c == 3) return "\u2601\uFE0F";
        if (c == 45 || c == 48) return "\uD83C\uDF2B\uFE0F";
        if (c >= 51 && c <= 57) return "\uD83C\uDF26\uFE0F";
        if (c >= 61 && c <= 67) return "\uD83C\uDF27\uFE0F";
        if (c >= 71 && c <= 77) return "\u2744\uFE0F";
        if (c >= 80 && c <= 82) return "\uD83C\uDF26\uFE0F";
        if (c == 85 || c == 86) return "\uD83C\uDF28\uFE0F";
        if (c >= 95) return "\u26C8\uFE0F";
        return "\u2601\uFE0F";
    }

    private static String wxText(int c) {
        if (c == 0) return "Clear";
        if (c == 1) return "Mostly clear";
        if (c == 2) return "Partly cloudy";
        if (c == 3) return "Overcast";
        if (c == 45 || c == 48) return "Fog";
        if (c >= 51 && c <= 57) return "Drizzle";
        if (c >= 61 && c <= 67) return "Rain";
        if (c >= 71 && c <= 77) return "Snow";
        if (c >= 80 && c <= 82) return "Showers";
        if (c == 85 || c == 86) return "Snow showers";
        if (c >= 95) return "Thunderstorm";
        return "Cloudy";
    }

    // ------------------------------------------------------------------ HUD

    private void startHud() {
        ui.post(new Runnable() {
            @Override public void run() {
                bg.execute(() -> {
                    final float cpu = sampleCpu();
                    ui.post(() -> updateHud(cpu));
                });
                ui.postDelayed(this, 2000);
            }
        });
    }

    private void updateHud(float cpu) {
        if (hud.getAlpha() == 0f) return;
        if (cpu >= 0) {
            cpuGraph.push(cpu);
            cpuVal.setText(String.format("%.2fGHz", cpuKhz / 1000000.0));
        } else {
            cpuVal.setText("n/a");
        }
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        float used = 1f - (float) mi.availMem / mi.totalMem;
        ramBar.set(used);
        ramVal.setText(mb(mi.totalMem - mi.availMem) + "/" + mb(mi.totalMem));
        netVal.setText(netString());
        tempVal.setText(diskString());
        upVal.setText(uptime());
    }

    private long cpuKhz;

    /** CPU "load" 0..1 as current/max core frequency (DVFS tracks load; /proc/stat is blocked for apps). */
    private float sampleCpu() {
        try {
            long cur = readLong("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq");
            long max = readLong("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq");
            long min = readLong("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_min_freq");
            if (cur <= 0 || max <= 0) return -1;
            cpuKhz = cur;
            return Math.max(0f, Math.min(1f, (float) (cur - min) / Math.max(1, max - min)));
        } catch (Exception e) {
            return -1;
        }
    }

    private static long readLong(String path) throws java.io.IOException {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return Long.parseLong(r.readLine().trim());
        }
    }

    private String diskString() {
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        return gb(fs.getTotalBytes() - fs.getAvailableBytes()) + "/" + gb(fs.getTotalBytes());
    }

    private String netString() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
            String ip = "offline";
            if (lp != null) for (LinkAddress la : lp.getLinkAddresses())
                if (la.getAddress() instanceof Inet4Address) { ip = la.getAddress().getHostAddress(); break; }
            WifiInfo wi = ((WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE)).getConnectionInfo();
            if (wi != null && wi.getLinkSpeed() > 0) ip += "  " + wi.getLinkSpeed() + "Mbps " + wi.getRssi() + "dBm";
            return ip;
        } catch (Exception e) {
            return "n/a";
        }
    }

    private String uptime() {
        long s = SystemClock.elapsedRealtime() / 1000;
        return (s / 86400) + "d " + (s % 86400 / 3600) + "h " + (s % 3600 / 60) + "m";
    }

    private static String mb(long b) { return b >= (1L << 30) ? String.format("%.1fG", b / 1073741824.0) : (b >> 20) + "M"; }
    private static String gb(long b) { return String.format("%.1fG", b / 1073741824.0); }

    // ------------------------------------------------------------------ custom views

    /** Scrolling CPU bar sparkline, green -> orange -> red. */
    private static class GraphView extends View {
        private final float[] s = new float[24];
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        GraphView(Context c) { super(c); }
        void push(float v) { System.arraycopy(s, 1, s, 0, s.length - 1); s[s.length - 1] = v; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth() / (float) s.length, h = getHeight();
            for (int i = 0; i < s.length; i++) {
                float v = s[i];
                p.setColor(v < 0.5f ? GREEN : v < 0.8f ? ORANGE : RED);
                float bh = Math.max(h * 0.08f, h * v);
                c.drawRect(i * w + 1, h - bh, (i + 1) * w - 1, h, p);
            }
        }
    }

    /** Thin rounded usage bar. */
    private static class BarView extends View {
        private float f;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        BarView(Context c) { super(c); }
        void set(float v) { f = v; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float h = getHeight(), r = h / 2;
            p.setColor(0x33FFFFFF);
            c.drawRoundRect(new RectF(0, 0, getWidth(), h), r, r, p);
            p.setColor(f < 0.7f ? GREEN : f < 0.88f ? ORANGE : RED);
            c.drawRoundRect(new RectF(0, 0, Math.max(h, getWidth() * f), h), r, r, p);
        }
    }

    /** Konami-code easter egg: falling hex glyphs. Self-dismisses after 14s or on any key. */
    private static class MatrixView extends View {
        private static final String CH = "0123456789ABCDEF<>/\\{}[]#$%&*+=";
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Random rnd = new Random();
        private int cols, cell, rows;
        private float[] head, speed;
        private boolean running;
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                if (!running) return;
                invalidate();
                postDelayed(this, 55);
            }
        };

        MatrixView(Context c) {
            super(c);
            p.setTypeface(Typeface.MONOSPACE);
            setBackgroundColor(0xE6000000);
        }

        void start() {
            cell = Math.max(24, getResources().getDisplayMetrics().widthPixels / 52);
            p.setTextSize(cell);
            cols = getResources().getDisplayMetrics().widthPixels / cell + 1;
            rows = getResources().getDisplayMetrics().heightPixels / cell + 1;
            head = new float[cols];
            speed = new float[cols];
            for (int i = 0; i < cols; i++) { head[i] = -rnd.nextInt(rows); speed[i] = 0.35f + rnd.nextFloat() * 0.9f; }
            running = true;
            setVisibility(VISIBLE);
            postDelayed(tick, 55);
            postDelayed(this::stop, 14000);
        }

        void stop() {
            running = false;
            removeCallbacks(tick);
            setVisibility(GONE);
        }

        @Override protected void onDraw(Canvas c) {
            if (!running) return;
            for (int i = 0; i < cols; i++) {
                head[i] += speed[i];
                if (head[i] - 14 > rows) { head[i] = -rnd.nextInt(8); speed[i] = 0.35f + rnd.nextFloat() * 0.9f; }
                int hy = (int) head[i];
                for (int t = 0; t < 12; t++) {
                    int y = hy - t;
                    if (y < 0 || y > rows) continue;
                    p.setColor(t == 0 ? 0xFFE0FFE0 : Color.argb(Math.max(30, 230 - t * 20), 0, 255, 70));
                    c.drawText(String.valueOf(CH.charAt(rnd.nextInt(CH.length()))), i * cell, y * cell, p);
                }
            }
        }
    }

    // ------------------------------------------------------------------ all-apps grid

    private class AppsAdapter extends BaseAdapter {
        private List<AppEntry> data = new ArrayList<>();

        void setData(List<AppEntry> d) { data = d; }
        @Override public int getCount() { return data.size(); }
        @Override public AppEntry getItem(int i) { return data.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            AppEntry a = data.get(pos);
            LinearLayout cell;
            ImageView iv;
            TextView tv;
            if (convert == null) {
                cell = new LinearLayout(HomeActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(dp(6), dp(10), dp(6), dp(8));
                iv = new ImageView(HomeActivity.this);
                iv.setTag("icon");
                iv.setOutlineProvider(new ViewOutlineProvider() {
                    @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(14)); }
                });
                iv.setClipToOutline(true);
                cell.addView(iv, new LinearLayout.LayoutParams(dp(64), dp(64)));
                tv = new TextView(HomeActivity.this);
                tv.setTag("label");
                tv.setSingleLine(true);
                tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                tv.setGravity(Gravity.CENTER);
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(13);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dp(6);
                cell.addView(tv, lp);
            } else {
                cell = (LinearLayout) convert;
                iv = (ImageView) cell.findViewWithTag("icon");
                tv = (TextView) cell.findViewWithTag("label");
            }
            iv.setImageDrawable(icon(a));
            tv.setText(a.label + (dockPackages().contains(a.pkg) ? "  ★" : ""));
            return cell;
        }
    }
}
