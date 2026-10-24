package com.nabil.tvhome;

import android.content.Context;
import android.util.Log;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;


/**
 * The Google doorbell inside the Cameras screen: Google Home for web rendered by GeckoView (Google refuses
 * account sign-in from Android's WebView, and the battery doorbell only streams through Google). The built-in
 * extension in assets/doorbell does the clicking and shows the video full window.
 *
 * The page is loaded once (when the Cameras screen opens) and then steered over the extension's native-messaging
 * port: {live:true} starts the stream, {live:false} closes it and waits on the camera list. The page never
 * reloads between views, so opening the doorbell only costs the camera's wake-up.
 */
final class DoorbellView {
    private static final String TAG = "DoorbellView";
    private static GeckoRuntime runtime;                  // one per process, started on first use
    private static WebExtension extension;                // the built-in doorbell extension, once installed
    private static DoorbellView instance;

    final GeckoView view;
    private final GeckoSession session;
    private final String url;
    private WebExtension.Port port;                       // to the page's content script, while it's loaded
    private boolean showing, loaded;

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
                    .consoleOutput(true).configFilePath(cfg.getAbsolutePath()).build());
            runtime.getWebExtensionController().ensureBuiltIn("resource://android/assets/doorbell/", "doorbell@tvhome.nabil")
                    .accept(ext -> {
                        extension = ext;
                        if (instance != null) instance.listen();
                    }, e -> Log.e(TAG, "doorbell extension failed", e));
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
        instance = this;
        if (extension != null) listen();
    }

    private void listen() {
        session.getWebExtensionController().setMessageDelegate(extension, new WebExtension.MessageDelegate() {
            @Override public void onConnect(WebExtension.Port p) {
                port = p;
                p.setDelegate(new WebExtension.PortDelegate() {
                    @Override public void onDisconnect(WebExtension.Port gone) { if (port == gone) port = null; }
                });
                tell();
            }
        }, "doorbell");
    }

    /** Tell the page whether to stream; sent again whenever the page (re)connects. */
    private void tell() {
        if (port == null) return;
        try { port.postMessage(new org.json.JSONObject().put("live", showing)); } catch (org.json.JSONException ignored) { }
    }

    private void load() {
        loaded = true;
        // keep the page's process at foreground priority while it's loaded: otherwise Android kills it first when
        // the camera streams need memory, and every open becomes a full reload
        session.setPriorityHint(GeckoSession.PRIORITY_HIGH);
        session.setActive(true);                          // inactive pages are throttled to a crawl
        session.loadUri(url);
    }

    private void recover() {
        Log.w(TAG, "page process ended, reopening");
        port = null;
        if (session.isOpen()) session.close();
        session.open(runtime);
        if (loaded) load();
    }

    /** Load the page in the background (Cameras screen opened) so the doorbell itself opens quickly. */
    void warm() {
        if (!loaded) load();
    }

    void show() {
        showing = true;
        session.setActive(true);
        if (!loaded) load();
        tell();
    }

    /** Back to the camera list on the page, which ends the stream so the battery doorbell isn't kept awake. */
    void hide() {
        showing = false;
        tell();
        view.postDelayed(() -> { if (!showing) session.setActive(false); }, 3000);   // let the page close the stream first
    }

    /** Cameras screen shut down: drop the page to give its memory back. */
    void unload() {
        showing = false;
        loaded = false;
        port = null;
        session.loadUri("about:blank");
        session.setActive(false);
        session.setPriorityHint(GeckoSession.PRIORITY_DEFAULT);
    }

}
