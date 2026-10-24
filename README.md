# ChiQ TV: debloat + custom home

Target: ChiQ "AI PONT" (Changhong), Android 14 / Google TV, 1.7 GB RAM, armeabi-v7a, MediaTek SoC with
hardware H.264/H.265 decoders.

| Path | What |
|---|---|
| `backup-*/` | Pre-change backup: package lists (enabled/disabled/system/user), settings dumps, getprop, baseline screenshot, and `shots/` (screenshots of every step) |
| `packages_to_disable.txt` | Packages disabled with `pm disable-user` (reversible, nothing uninstalled) |
| `debloat.sh [ip:port]` | Re-applies the debloat |
| `restore.sh [ip:port]` | Undoes everything: re-enables all packages, restores the stock home, removes the custom launcher (and with it the saved NVR login), resets animation scales |
| `banner.sh "text" [ip:port]` | Pops a bouncing banner on the TV home screen |
| `launcher/` | Source for `com.nabil.tvhome`; build with `launcher/build.sh` (javac + d8 + aapt2, no Gradle) |

## Connecting
Wireless debugging resets on every TV reboot, so re-pair each time:
Developer options > Wireless debugging > Pair device with pairing code, then
`adb pair <ip>:<pairing-port> <code>` and `adb connect <ip>:<debugging-port>`.

If the machine running the tooling can't reach the LAN (macOS Local Network permission for the app hosting it,
for example), start the ADB server from a normal Terminal (`adb kill-server && adb start-server`); everything else
talks to that server.

## Launcher features
- Rotating nature wallpapers with Ken Burns pan/zoom and dock parallax (credits: `launcher/assets/CREDITS.txt`)
- Magnifying dock; hold OK on an app in **All apps** to pin/unpin it (max 8)
- Live HUD (CPU clock graph, RAM, IP/Wi-Fi, disk, uptime); time-of-day greeting
- Weather card (Open-Meteo; location from an IP lookup, cached for a day; refreshed every 30 min)
- **Cameras** dock tile (see below)
- Up or Menu: neofetch panel. Konami code (Up Up Down Down Left Right Left Right): Matrix rain
- 90 s idle: ambient mode (dock and HUD fade out, photo caption appears)

## Cameras (Dahua-family NVR)
Configure once (the launcher stores the login privately on the TV; it is not in this repo):

    adb shell "am start -n com.nabil.tvhome/.HomeActivity --es nvr 'HOST|USER|PASSWORD|CHANNELS'"
    adb shell "am start -n com.nabil.tvhome/.HomeActivity --es nvr clear"     # remove it

Use a view-only NVR account, not admin.

- 2x2 grid of live sub-streams; OK opens a camera full screen (instant sub-stream, upgrading to the main stream
  after about a second); Left/Right switches camera; Back goes full screen > grid > home.
- Video is H.265 over RTSP (TCP interleaved), parsed by `RtspHevcClient` and decoded by the TV's hardware decoder
  into a `TextureView`. The NVR's digest nonce is bound to the connection, so auth is recomputed per request.
- Hovering the Cameras icon pre-connects the streams; each keeps its latest keyframe so the picture is instant.
  Streams stay warm for 3 minutes after you close the screen and are dropped when you leave the launcher.
- A stream that fails falls back to JPEG snapshots (`NvrClient`), retrying live video every 30 s. Two rejected
  logins in a row pause all login attempts for 5 minutes so a wrong password cannot lock the NVR.
- Plain HTTP is allowed for the app (`usesCleartextTraffic`) because the NVR serves snapshots over HTTP on the LAN.

## Why the stock launcher and setup wizard are disabled
ChiQ's TV agent starts `com.google.android.apps.tv.launcherx` directly on every Home press, bypassing the
default-home setting. With it disabled, Home resolves to `com.google.android.tungsten.setupwraith/.RecoveryActivity`
(higher priority than ours) and `pm disable` on that single component is denied to ADB, so the whole setup-wizard
package is disabled too. Both are re-enabled by `restore.sh`.

## Baseus X1 Pro (S00035): two lenses as extra Cameras tiles
Works through Baseus's own cloud rendezvous, then a direct UDP link on the LAN (all in Java, no Python on the TV):
`BaseusCloud` (sign-in, device list) > `BaseusWake` (MQTT wake, held open) > `Ppcs` (rendezvous + punch + reliable
channels) > `XmCodec` (camera login, AES, frame reassembly) > `BaseusStream` > `AnnexBDecoder` (H.265).
- Setup (login is stored on the TV only): `./baseus-setup.sh`, then `./baseus-setup.sh test cloud|wake|connect|video`.
  Remove with `./baseus-setup.sh clear`.
- The grid shows keyframe thumbnails decoded in software; full screen uses a hardware decoder for live video.
  The TV has only a few hardware HEVC decoders, so four NVR tiles plus two 2560x1440 lenses will not all fit.
- Quirk handled in `Ppcs`: the camera may reply to the punch from its LAN address, so private-network senders are
  accepted when the device ID matches. A new Baseus password login elsewhere invalidates the TV's token; the TV
  signs in again by itself.
- Experiments and captures live in `baseus-test/` (contain logins/tokens; delete when done).

## Eufy cameras behind a HomeBase 2 (eufyCam 2C / 2 Pro, "Safe 2K")
Direct LAN link to the HomeBase using Eufy's P2P protocol (no Eufy video through the cloud). Classes: `EufyCloud`
(sign-in with ECDH/AES, captcha and emailed-code handling, station list, cipher/DSK requests), `Ppcs.connectLan`
(local lookup on UDP 32108 + cam check), `EufyXzyh` (command/message layer), `EufyStream` (command key, start/stop
livestream, RSA/AES video-frame container), `AnnexBDecoder` (H.264 and H.265).
- Setup (login stored on the TV only; a second Eufy account the cameras are shared to is recommended):
  `./eufy-setup.sh`, then `./eufy-setup.sh test`, `captcha`, `code <digits>` as asked; `test connect` and
  `test video<channel>` are self-tests; `hide <channel>` removes a camera tile (e.g. a camera switched off on
  purpose); `clear` removes the login.
- The HomeBase streams ONE camera at a time. The grid therefore rotates through the available cameras every 9 s
  (keyframe thumbnails); full screen on a Eufy tile locks onto that camera and streams it live. Switching takes
  a few seconds because battery cameras wake on demand. Streams stop 45 s after leaving the Cameras screen.
- Start-command return code -201 means the camera is unavailable (offline / switched off); such cameras are
  remembered (`eu_bad`) and skipped.
- The cloud had no command cipher for this HomeBase, so commands use the level-1 key (serial + device ID), as the
  community library does in that case.

## Google Nest Doorbell (battery): last Cameras tile, Google Home for web inside the launcher
The battery doorbell only streams over WebRTC through Google's servers, and the free routes on this TV don't work
(Device Access costs US$5; the Assistant's camera viewer, `com.google.android.tv.bali`, isn't on this TV). So OK on the
tile opens Google Home for web full screen in an embedded GeckoView (`DoorbellView`); Back returns to the grid.
Android's WebView can't be used: Google refuses account sign-in from it.
- A built-in web extension (`launcher/assets/doorbell/`) presses "Live video" (again after Google's five-minute battery
  limit), dismisses notices, and plays the stream on a full-window video layer.
- The page is preloaded when the Cameras screen opens and then steered by URL fragment (`#live` opens the single
  camera view and starts the stream, `#idle` closes it, which ends the stream), so OK only waits for the doorbell to
  wake (~12 s). The page is unloaded when the Cameras streams are shut down.
- GeckoView and its libraries: `./launcher/fetch-gecko.py` downloads them into `launcher/gecko/` (git-ignored, ~90 MB);
  `build.sh` unpacks the AARs, merges their manifests (`merge-manifest.py`) and resources, and bundles the native code.
  Play-services FIDO and media3 are left out, so WebAuthn is switched off in `DoorbellView`'s Gecko config (Google's
  sign-in page probes it and Gecko crashes without FIDO). One content process, no Fission: the TV has 1.8 GB.
- While the doorbell is open the NVR, Baseus and Eufy streams stop to free memory; they restart on Back.
- One-time setup: sign in to Google inside the doorbell view (it redirects to sign-in when signed out).
- Configure: `adb shell am start -n com.nabil.tvhome/.HomeActivity --es doorbell "'Front door doorbell|https://home.google.com/u/0/home/<home-id>/cameras/grid'"`
  (`--es doorbell clear` removes the tile). Open directly: `--es show doorbell` (or `--es show cameras`).
- Don't re-enable `com.google.android.apps.tv.launcherx`: it outranks the custom home screen.

## Known loose ends
- YouTube (`com.google.android.youtube.tv`) was disabled by something other than this project; re-enable with
  `adb shell pm enable com.google.android.youtube.tv` if you want it back.
- The NVR clock runs about 12 minutes ahead of the TV.
