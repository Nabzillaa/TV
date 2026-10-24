// Google Home for web, inside the TV's Cameras screen. The launcher steers it with the URL fragment:
//   #live  open the doorbell's single-camera view, press "Live video" (again whenever Google's five-minute battery
//          limit stops the stream) and show the stream on a full-window video layer; a black "waking" curtain
//          hides the page until it plays, and a start stuck for 45 s reloads once
//   #idle  close the single-camera view (ends the stream) and wait on the camera list
(function () {
  // signed out: Google shows its welcome page; go to sign-in, then come back to the home
  if (location.pathname.startsWith("/welcome")) {
    location.href = "https://accounts.google.com/ServiceLogin?continue=" + encodeURIComponent("https://home.google.com/u/0/home");
    return;
  }
  if (window.top !== window) return;

  const overlay = document.createElement("video");
  overlay.autoplay = true;
  overlay.playsInline = true;
  overlay.style.cssText = "position:fixed;inset:0;width:100vw;height:100vh;object-fit:contain;background:#000;" +
    "z-index:2147483647;display:none";
  // Google's notices open in the top layer, above everything; keep them invisible while they're dismissed
  const quiet = document.createElement("style");
  quiet.textContent = "dialog,[role=dialog],[role=alertdialog]{opacity:0!important}dialog::backdrop{opacity:0!important}";
  const curtain = document.createElement("div");
  curtain.style.cssText = "position:fixed;inset:0;background:#000;color:#fff;z-index:2147483646;display:none;" +
    "align-items:center;justify-content:center;font:28px sans-serif";

  // the SPA drops the fragment when it changes route, so remember the mode instead of reading it each time
  let live = location.hash === "#live", since = Date.now(), lastPlay = 0;
  window.addEventListener("hashchange", () => {
    const now = location.hash === "#live";
    if (now !== live) { live = now; since = Date.now(); lastPlay = 0; }
  });

  const textOf = e => (e.textContent || e.getAttribute("aria-label") || "").trim();
  const button = re => [...document.querySelectorAll("button,[role=button],a,[role=tab],[role=link]")].find(e => re.test(textOf(e)));
  const singleView = () => location.pathname.includes("/cameras/") && !location.pathname.endsWith("/grid");

  function tick() {
    if (!document.body) return;
    if (!overlay.isConnected) { document.body.append(curtain, overlay); document.head.append(quiet); }
    const notice = [...document.querySelectorAll("[role=dialog],[role=alertdialog]")]
      .map(d => [...d.querySelectorAll("button")].find(b => /^(ok|got it|dismiss|close)$/i.test(textOf(b)))).find(Boolean);
    if (notice) { notice.click(); return; }
    if (!location.pathname.includes("/cameras")) {                 // landed on the home page: open Cameras
      const cams = button(/^cameras$/i);
      if (cams) cams.click();
      return;
    }

    if (!live) {
      overlay.style.display = curtain.style.display = "none";
      overlay.srcObject = null;
      if (singleView()) { const close = button(/^close$/i); if (close) close.click(); }
      return;
    }

    const playing = [...document.querySelectorAll("video")].find(v => v !== overlay && !v.paused && v.readyState >= 2 && v.srcObject);
    if (playing) {
      lastPlay = Date.now();
      if (overlay.srcObject !== playing.srcObject) { overlay.srcObject = playing.srcObject; overlay.play().catch(() => {}); }
      playing.muted = true;                                          // sound comes from the overlay
      overlay.style.display = "block";
      curtain.style.display = "none";
      sessionStorage.removeItem("tvReloaded");                       // a later stall may reload again
      return;
    }
    // Google swaps the video element when it raises the quality: keep the last picture through short gaps
    if (Date.now() - lastPlay < 4000) return;

    overlay.style.display = "none";
    curtain.style.display = "flex";
    const waited = Date.now() - since;
    curtain.textContent = waited > 45000 ? "Still waking the doorbell…  press Back to cancel" : "Waking the doorbell…";
    const single = !singleView() && button(/in single camera view$/i);
    if (single) { single.click(); return; }                         // full-size stream instead of the grid preview
    const start = button(/^(live video|watch live)$/i);
    if (start) start.click();
    else if (waited > 45000 && !sessionStorage.getItem("tvReloaded")) {      // stuck: reload once, still live
      sessionStorage.setItem("tvReloaded", "1");
      history.replaceState(null, "", location.pathname + "#live");
      location.reload();
    }
  }

  setInterval(tick, 250);
})();
