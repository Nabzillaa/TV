package com.nabil.tvhome;

import android.content.Context;
import android.util.Log;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;

import java.util.List;

/**
 * The Google doorbell inside the Cameras screen: Google Home for web rendered by GeckoView (Google refuses
 * account sign-in from Android's WebView, and the battery doorbell only streams through Google). The built-in
 * extension in assets/doorbell does the clicking and shows the video full window.
 *
 * The page is loaded once (when the Cameras screen opens) and then only steered with the URL fragment:
 * #live starts the stream, #idle closes it and waits on the camera list. Fragment changes don't reload the
 * page, so opening the doorbell only costs the camera's wake-up.
 */
final class DoorbellView {
    private static final String TAG = "DoorbellView";
    private static GeckoRuntime runtime;                  // one per process, started on first use

    final GeckoView view;
    private final GeckoSession session;
    private final String url;
    private String location;                              // the page's current address, without fragment
    private boolean showing;

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
        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override public void onLocationChange(GeckoSession s, String u, List<GeckoSession.PermissionDelegate.ContentPermission> p, Boolean gesture) {
                if (u == null || !u.startsWith("https://home.google.com/")) { location = null; return; }
                int hash = u.indexOf('#');
                location = hash < 0 ? u : u.substring(0, hash);
            }
        });
        session.open(runtime);
        view = new GeckoView(c);
        view.setSession(session);
    }

    private void steer(String mode) {
        session.loadUri((location != null ? location : url) + "#" + mode);
    }

    private void recover() {
        Log.w(TAG, "page process ended, reopening");
        location = null;
        if (session.isOpen()) session.close();
        session.open(runtime);
        steer(showing ? "live" : "idle");
    }

    /** Load the page in the background (Cameras screen opened) so the doorbell itself opens quickly. */
    void warm() {
        if (showing || location != null) return;
        session.setActive(true);                          // inactive pages are throttled to a crawl
        steer("idle");
    }

    void show() {
        showing = true;
        session.setActive(true);
        steer("live");
    }

    /** Cameras screen shut down: drop the page to give its memory back. */
    void unload() {
        showing = false;
        location = null;
        session.loadUri("about:blank");
        session.setActive(false);
    }

    /** Back to the camera list on the page, which ends the stream so the battery doorbell isn't kept awake. */
    void hide() {
        showing = false;
        steer("idle");
        session.setActive(false);
    }
}
