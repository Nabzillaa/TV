// Google Home for web, inside the TV's Cameras screen: open the doorbell's single-camera view, press "Live video"
// (again whenever Google's five-minute battery limit stops the stream) and show the stream on a full-window video
// layer above the page. Until it plays, a black "waking" curtain hides the page.
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
  const curtain = document.createElement("div");
  curtain.textContent = "Waking the doorbell…";
  curtain.style.cssText = "position:fixed;inset:0;background:#000;color:#fff;z-index:2147483646;display:flex;" +
    "align-items:center;justify-content:center;font:28px sans-serif;opacity:.85";
  const started = Date.now();

  const textOf = e => (e.textContent || e.getAttribute("aria-label") || "").trim();
  const button = re => [...document.querySelectorAll("button,[role=button],a,[role=tab],[role=link]")].find(e => re.test(textOf(e)));

  function tick() {
    if (!document.body) return;
    if (!overlay.isConnected) document.body.append(curtain, overlay);
    // nothing playing after a minute: show the page, in case Google is asking for something
    curtain.style.display = Date.now() - started < 60000 ? "flex" : "none";
    const notice = [...document.querySelectorAll("[role=dialog],[role=alertdialog]")]
      .map(d => [...d.querySelectorAll("button")].find(b => /^(ok|got it|dismiss|close)$/i.test(textOf(b)))).find(Boolean);
    if (notice) { notice.click(); return; }
    if (!location.pathname.includes("/cameras")) {                 // landed on the home page: open Cameras
      const cams = button(/^cameras$/i);
      if (cams) cams.click();
      return;
    }
    const single = location.pathname.endsWith("/grid") && button(/in single camera view$/i);
    if (single) { single.click(); return; }                         // full-size stream instead of the grid preview
    const playing = [...document.querySelectorAll("video")].find(v => v !== overlay && !v.paused && v.readyState >= 2 && v.srcObject);
    if (playing) {
      if (overlay.srcObject !== playing.srcObject) { overlay.srcObject = playing.srcObject; overlay.play().catch(() => {}); }
      playing.muted = true;                                          // sound comes from the overlay
      overlay.style.display = "block";
      curtain.style.display = "none";
      return;
    }
    overlay.style.display = "none";
    overlay.srcObject = null;
    const live = button(/^(live video|watch live)$/i);
    if (live) live.click();
  }

  setInterval(tick, 1000);
})();
