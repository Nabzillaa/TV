package com.nabil.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Recorded video for an NVR channel, drawn over the full-screen camera: a 24 hour timeline of the day's recordings
 * (motion and other events in orange) from mediaFileFind.cgi, played over RTSP /cam/playback. All times are the
 * NVR's own clock, which runs ahead of the TV's and is what the picture's timestamp shows.
 *   timeline open: ◀ ▶ scrub (hold to speed up), ▲ ▼ previous/next motion, OK play from there, Back live
 *   playing:       any arrow or OK brings the timeline back, play/pause pauses, rewind/ffwd skip 30 s, Back live
 */
final class NvrPlayback extends View {
    private static final String TAG = "Playback";
    private static final long MIN = 60_000, HOUR = 60 * MIN, DAY = 24 * HOUR;

    /** The full-screen view the recording plays in. */
    interface Host {
        Surface surface();
        void videoSize(int w, int h);
        void stopLive();
        void startLive();
        void status(String s);
        /** The timeline covers the bottom of the picture: hide the live hints there. */
        void barShown(boolean shown);
    }

    private final Host host;
    private final Handler ui;
    private final float dp;
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private NvrClient nvr;
    private String nvrHost, user, pass;

    private int ch;
    private long offset;                 // NVR clock minus TV clock
    private boolean offsetKnown;
    private long day = -1, cursor;       // shown day (NVR midnight), cursor time
    private final Map<Long, List<long[]>> days = new HashMap<>();   // day -> recordings, for this channel
    private boolean loading;
    private String note;                 // replaces the hints for a moment ("No recordings after 14:30")
    private boolean bar;
    private int gen;

    private RtspHevcClient client;
    private long playFrom;
    private boolean paused, started;

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US), hms = new SimpleDateFormat("HH:mm:ss", Locale.US),
            dayName = new SimpleDateFormat("EEE d MMM", Locale.US), url = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US);

    NvrPlayback(Context c, Handler ui, Host host) {
        super(c);
        this.ui = ui;
        this.host = host;
        dp = c.getResources().getDisplayMetrics().density;
        setVisibility(GONE);
    }

    void setNvr(String nvrHost, String user, String pass) {
        stop();
        this.nvrHost = nvrHost; this.user = user; this.pass = pass;
        nvr = new NvrClient(nvrHost, user, pass);
        offsetKnown = false;
        days.clear();
    }

    /** Timeline open or a recording playing: arrows, OK and Back belong here. */
    boolean active() { return bar || client != null; }

    private long now() { return System.currentTimeMillis() + offset; }

    // ------------------------------------------------------------ open / close

    /** Open the timeline for an NVR channel, the cursor a minute before now. The live picture keeps running. */
    void open(int channel) {
        if (nvr == null) return;
        if (channel != ch) { ch = channel; days.clear(); }
        days.remove(midnight(now()));        // today keeps growing
        cursor = now() - MIN;
        showBar();
        if (!offsetKnown) {
            loading = true;
            final int g = gen;
            net.execute(() -> {
                long off = 0;
                try { off = nvr.clockOffset(); Log.i(TAG, "NVR clock is " + off / 1000 + " s ahead"); }
                catch (Exception e) { Log.w(TAG, "NVR time: " + e.getMessage()); }
                final long o = off;
                ui.post(() -> {
                    offset = o;
                    offsetKnown = true;
                    if (g == gen && client == null) cursor = now() - MIN;
                    load(midnight(cursor));
                });
            });
        } else {
            load(midnight(cursor));
        }
    }

    /** Back: leave playback for the live picture. False if there was nothing to leave. */
    boolean back() {
        if (!active()) return false;
        boolean wasPlaying = client != null;
        stop();
        if (wasPlaying) host.startLive();
        return true;
    }

    /** Drop the recording and the timeline (leaving full screen, switching camera, closing Cameras). */
    void stop() {
        gen++;
        stopClient();
        bar = false;
        note = null;
        host.barShown(false);
        ui.removeCallbacks(autoHide);
        ui.removeCallbacks(tick);
        setVisibility(GONE);
    }

    private void stopClient() {
        RtspHevcClient c = client;
        client = null;
        paused = false;
        if (c != null) {
            c.detach();                     // free the decoder on the surface now; the socket closes in the background
            net.execute(c::stop);
        }
    }

    private void showBar() {
        bar = true;
        host.barShown(true);
        setVisibility(VISIBLE);
        armAutoHide();
        invalidate();
    }

    private final Runnable autoHide = this::hideBar;

    /** While a recording plays the timeline gets out of the way after a few seconds. */
    private void hideBar() {
        if (client == null || paused) return;
        bar = false;
        note = null;
        host.barShown(false);
        invalidate();
    }

    private void armAutoHide() {
        ui.removeCallbacks(autoHide);
        if (client != null) ui.postDelayed(autoHide, 5000);
    }

    // ------------------------------------------------------------ recordings

    private void load(final long d) {
        day = d;
        if (days.containsKey(d)) { loading = false; invalidate(); return; }
        loading = true;
        invalidate();
        final int c = ch;
        net.execute(() -> {
            List<long[]> list;
            try { list = nvr.recordings(c, d, d + DAY); }
            catch (Exception e) { Log.w(TAG, "ch" + c + " recordings: " + e.getMessage()); list = null; }
            final List<long[]> l = list;
            ui.post(() -> {
                if (c != ch) return;
                if (l != null) days.put(d, l);
                else if (d == day) flash("Couldn't load the recordings");
                if (d == day) { loading = false; invalidate(); }
            });
        });
    }

    private List<long[]> segs() {
        List<long[]> l = days.get(day);
        return l != null ? l : new ArrayList<>();
    }

    private static long midnight(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private void moveCursor(long t) {
        cursor = Math.min(t, now());
        long d = midnight(cursor);
        if (d != day) load(d);
        invalidate();
    }

    /** Start of the previous/next motion recording; an hour back/forward when there is none on this day. */
    private long jump(boolean forward) {
        List<long[]> l = segs();
        if (forward) {
            for (long[] s : l) if (s[2] == 1 && s[0] > cursor + 1000) return s[0];
            return cursor + HOUR;
        }
        for (int i = l.size() - 1; i >= 0; i--) if (l.get(i)[2] == 1 && l.get(i)[0] < cursor - 1000) return l.get(i)[0];
        return cursor - HOUR;
    }

    // ------------------------------------------------------------ playing

    private void play(long t) {
        if (!loading && days.containsKey(day)) {            // in a gap: start at the next recording
            long next = -1;
            for (long[] s : segs()) {
                if (s[1] > t) { next = Math.max(s[0], t); break; }
            }
            if (next < 0) { flash("No recordings after " + hm.format(new Date(t))); return; }
            t = next;
        }
        if (t > now() - 10_000) { back(); return; }         // that's now: live is the same picture, sooner
        Surface s = host.surface();
        if (s == null) return;
        host.stopLive();
        stopClient();
        playFrom = t;
        cursor = t;
        started = false;
        host.status("Loading " + hms.format(new Date(t)) + "…");
        final int g = ++gen;
        RtspHevcClient c = new RtspHevcClient(nvrHost, user, pass, ch, 0, new RtspHevcClient.Listener() {
            @Override public void onVideoSize(int w, int h) { ui.post(() -> { if (g == gen) host.videoSize(w, h); }); }
            @Override public void onFirstFrame() { ui.post(() -> { if (g == gen) { started = true; host.status(""); invalidate(); } }); }
            @Override public void onError(String m) { ui.post(() -> { if (g == gen) ended(m); }); }
        }).playback(url.format(new Date(t)), url.format(new Date(now())));
        client = c;
        c.start(s);
        ui.removeCallbacks(tick);
        ui.post(tick);
        armAutoHide();
        invalidate();
    }

    private void ended(String m) {
        Log.i(TAG, "ch" + ch + " playback stopped: " + m);
        long at = position();
        stopClient();
        cursor = at;
        host.status("");
        flash(started ? "End of recording" : "Couldn't play this recording");
        showBar();
    }

    private long position() {
        RtspHevcClient c = client;
        return c == null ? cursor : playFrom + c.mediaMs();
    }

    private void togglePause() {
        final RtspHevcClient c = client;
        if (c == null) return;
        paused = !paused;
        final boolean pz = paused;
        net.execute(() -> c.pause(pz));
        if (pz) showBar(); else armAutoHide();
        invalidate();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (client == null) return;
            invalidate();
            ui.postDelayed(this, 500);
        }
    };

    private void flash(String s) {
        note = s;
        invalidate();
        ui.postDelayed(() -> { if (s.equals(note)) { note = null; invalidate(); } }, 3500);
    }

    // ------------------------------------------------------------ remote

    boolean key(int code, KeyEvent e) {
        boolean playing = client != null;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                if (!bar) { cursor = position(); showBar(); }
                int n = e.getRepeatCount();
                long step = n < 6 ? MIN : n < 16 ? 5 * MIN : 15 * MIN;    // hold to go faster
                moveCursor(cursor + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -step : step));
                armAutoHide();
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!bar) { cursor = position(); showBar(); }
                moveCursor(jump(code == KeyEvent.KEYCODE_DPAD_DOWN));
                armAutoHide();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() > 0) return true;
                if (!bar) { cursor = position(); showBar(); return true; }
                play(cursor);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_SPACE:
                if (playing) togglePause(); else play(cursor);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                play((playing ? position() : cursor) + (code == KeyEvent.KEYCODE_MEDIA_REWIND ? -30_000 : 30_000));
                return true;
        }
        return false;
    }

    // ------------------------------------------------------------ drawing

    private float px(float v) { return v * dp; }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (client != null) drawBadge(c, w, bar ? h - px(150) - px(64) : h - px(62));
        if (!bar) return;

        float barH = px(150), top = h - barH;
        p.setStyle(Paint.Style.FILL);
        p.setShader(new LinearGradient(0, top - px(70), 0, h, 0x00000000, 0xE6000000, Shader.TileMode.CLAMP));
        c.drawRect(0, top - px(70), w, h, p);
        p.setShader(null);

        float left = px(48), right = w - px(48), trackTop = top + px(58), trackH = px(26);
        float span = right - left;
        long now = now();

        // title and hints
        p.setColor(Color.WHITE);
        p.setTextSize(px(20));
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        String title = "Playback · " + dayLabel(day) + (loading ? "   ·   loading…" : "");
        c.drawText(title, left, top + px(18), p);
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(px(14));
        p.setColor(note != null ? 0xFFFFB340 : 0xB3FFFFFF);
        String hint = note != null ? note : client != null
                ? "◀ ▶ move   ·   ▲ ▼ motion   ·   OK play from here   ·   ⏯ pause   ·   Back: live"
                : "◀ ▶ move (hold for faster)   ·   ▲ ▼ motion   ·   OK play   ·   Back: live";
        c.drawText(hint, right - p.measureText(hint), top + px(18), p);

        // track: recorded in blue, events in orange, the future dimmed
        r.set(left, trackTop, right, trackTop + trackH);
        p.setColor(0x26FFFFFF);
        c.drawRoundRect(r, px(6), px(6), p);
        c.save();
        c.clipRect(r);
        List<long[]> l = segs();
        for (int pass = 0; pass < 2; pass++) {
            p.setColor(pass == 0 ? 0xFF2F7CF6 : 0xFFFF9F0A);
            for (long[] s : l) {
                if ((pass == 1) != (s[2] == 1)) continue;
                float x0 = left + span * (s[0] - day) / DAY, x1 = left + span * (s[1] - day) / DAY;
                if (x1 - x0 < px(2)) x1 = x0 + px(2);
                c.drawRect(x0, trackTop + (pass == 1 ? px(5) : 0), x1, trackTop + trackH - (pass == 1 ? px(5) : 0), p);
            }
        }
        if (now < day + DAY) {
            float xn = left + span * Math.max(0, now - day) / DAY;
            p.setColor(0x99000000);
            c.drawRect(xn, trackTop, right, trackTop + trackH, p);
        }
        c.restore();
        if (!loading && l.isEmpty() && days.containsKey(day)) {
            p.setColor(0xCCFFFFFF);
            p.setTextSize(px(13));
            String s = "No recordings on this day";
            c.drawText(s, left + (span - p.measureText(s)) / 2, trackTop + trackH / 2 + px(5), p);
        }

        // hour labels
        p.setTextSize(px(12));
        for (int hr = 0; hr <= 24; hr += 3) {
            float x = left + span * hr / 24f;
            p.setColor(0x40FFFFFF);
            c.drawRect(x - px(0.5f), trackTop + trackH + px(3), x + px(0.5f), trackTop + trackH + px(9), p);
            p.setColor(0x99FFFFFF);
            String s = String.format(Locale.US, "%02d:00", hr % 24 == 0 && hr > 0 ? 24 : hr);
            float tw = p.measureText(s);
            c.drawText(s, Math.max(left, Math.min(right - tw, x - tw / 2)), trackTop + trackH + px(24), p);
        }

        // where the recording is (red) and the cursor (white, with its time)
        if (client != null && midnight(position()) == day) {
            float xp = left + span * (position() - day) / DAY;
            p.setColor(0xFFFF453A);
            c.drawRect(xp - px(1.5f), trackTop - px(4), xp + px(1.5f), trackTop + trackH + px(4), p);
        }
        if (midnight(cursor) == day) {
            float xc = left + span * (cursor - day) / DAY;
            p.setColor(Color.WHITE);
            p.setShadowLayer(px(6), 0, 0, 0x99000000);
            c.drawRoundRect(new RectF(xc - px(2), trackTop - px(8), xc + px(2), trackTop + trackH + px(8)), px(2), px(2), p);
            p.setTextSize(px(15));
            p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            String s = hm.format(new Date(cursor));
            float tw = p.measureText(s), bw = tw + px(18), bx = Math.max(left - px(8), Math.min(right + px(8) - bw, xc - bw / 2));
            r.set(bx, trackTop - px(38), bx + bw, trackTop - px(12));
            c.drawRoundRect(r, px(13), px(13), p);
            p.clearShadowLayer();
            p.setColor(0xFF111111);
            c.drawText(s, bx + px(9), trackTop - px(19.5f), p);
            p.setTypeface(Typeface.DEFAULT);
        }
    }

    /** Bottom centre (clear of the camera's own timestamp) while a recording plays: ● PLAYBACK  Sun 1 Nov  14:32:10. */
    private void drawBadge(Canvas c, int w, float y) {
        long t = position();
        String s = (paused ? "PAUSED   " : "PLAYBACK   ") + dayLabel(midnight(t)) + "   " + hms.format(new Date(t));
        p.setTextSize(px(15));
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        float tw = p.measureText(s), x0 = (w - tw - px(46)) / 2, x1 = x0 + tw + px(46);
        c.save();
        c.translate(0, y - px(14));
        r.set(x0, px(14), x1, px(46));
        p.setColor(0xB3000000);
        c.drawRoundRect(r, px(16), px(16), p);
        p.setColor(paused ? 0xFFFFB340 : 0xFFFF453A);
        if (paused) {
            c.drawRect(x0 + px(13), px(23), x0 + px(17), px(37), p);
            c.drawRect(x0 + px(21), px(23), x0 + px(25), px(37), p);
        } else {
            c.drawCircle(x0 + px(19), px(30), px(6), p);
        }
        p.setColor(Color.WHITE);
        c.drawText(s, x0 + px(32), px(35.5f), p);
        c.restore();
        p.setTypeface(Typeface.DEFAULT);
    }

    private String dayLabel(long d) {
        long today = midnight(now());
        String name = dayName.format(new Date(d));
        if (d == today) return "Today, " + name;
        if (d == midnight(today - DAY / 2)) return "Yesterday, " + name;
        return name;
    }
}
