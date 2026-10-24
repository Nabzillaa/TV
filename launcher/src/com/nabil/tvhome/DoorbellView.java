package com.nabil.tvhome;

import android.content.Context;
import android.util.Log;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;

/**
 * The Google doorbell inside the Cameras screen: Google Home for web rendered by GeckoView (Google refuses
 * account sign-in from Android's WebView, and the battery doorbell only streams through Google). The built-in
 * extension in assets/doorbell presses "Live video" and shows the video full window.
 */
final class DoorbellView {
    private static final String TAG = "DoorbellView";
    private static GeckoRuntime runtime;                  // one per process, started on first use

    final GeckoView view;
    private final GeckoSession session;
    private final String url;

    DoorbellView(Context c, String url) {
        this.url = url;
        if (runtime == null) {
            // Passkeys (WebAuthn) go through Play-services FIDO, which isn't bundled; Google's sign-in page probes it
            // and Gecko crashes on the missing Java class, so switch WebAuthn off.
            java.io.File cfg = new java.io.File(c.getFilesDir(), "geckoview-config.yaml");
            try (java.io.FileWriter w = new java.io.FileWriter(cfg)) {
                // and one page process, no per-site isolation: the TV has 1.8 GB and only ever shows this one site
                w.write("prefs:\n  security.webauth.webauthn: false\n  security.webauthn.enable_android_fido2: false\n"
                        + "  fission.autostart: false\n  dom.ipc.processCount: 1\n  browser.cache.memory.capacity: 16384\n");
            } catch (java.io.IOException e) { Log.w(TAG, "config: " + e); }
            runtime = GeckoRuntime.create(c.getApplicationContext(), new GeckoRuntimeSettings.Builder()
                    .consoleOutput(false).configFilePath(cfg.getAbsolutePath()).build());
            runtime.getWebExtensionController().ensureBuiltIn("resource://android/assets/doorbell/", "doorbell@tvhome.nabil")
                    .accept(ext -> Log.i(TAG, "doorbell extension ready"), e -> Log.e(TAG, "doorbell extension failed", e));
        }
        session = new GeckoSession(new GeckoSessionSettings.Builder()
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_DESKTOP)
                .viewportMode(GeckoSessionSettings.VIEWPORT_MODE_DESKTOP).build());
        session.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
            @Override public GeckoResult<Integer> onContentPermissionRequest(GeckoSession s, ContentPermission p) {
                boolean autoplay = p.permission == PERMISSION_AUTOPLAY_AUDIBLE || p.permission == PERMISSION_AUTOPLAY_INAUDIBLE;
                return GeckoResult.fromValue(autoplay ? ContentPermission.VALUE_ALLOW : ContentPermission.VALUE_DENY);
            }
        });
        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            // Android may kill the page process when memory runs short; reopen instead of leaving a blank page
            @Override public void onKill(GeckoSession s) { recover(); }
            @Override public void onCrash(GeckoSession s) { recover(); }
        });
        session.open(runtime);
        view = new GeckoView(c);
        view.setSession(session);
    }

    private boolean showing;

    private void recover() {
        Log.w(TAG, "page process ended, reopening");
        if (session.isOpen()) session.close();
        session.open(runtime);
        if (showing) session.loadUri(url);
    }

    void show() {
        showing = true;
        session.setActive(true);
        session.loadUri(url);
    }

    /** Leaving the page ends the stream, so the battery doorbell isn't kept awake. */
    void hide() {
        showing = false;
        session.loadUri("about:blank");
        session.setActive(false);
    }
}
