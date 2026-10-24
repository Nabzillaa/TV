#!/bin/bash
# Restores the TV to its pre-debloat state: re-enables every package this project disabled,
# restores the stock Google TV home screen, and removes the custom launcher's settings.
# Usage: ./restore.sh [ip:port]     (connect first: adb connect <ip>:<port>)
cd "$(dirname "$0")"
[ -n "$1" ] && export ANDROID_SERIAL="$1"
echo "Re-enabling disabled packages..."
grep -v '^#' packages_to_disable.txt | awk '{print $1}' | while read -r p; do
  [ -z "$p" ] && continue
  echo -n "$p: "; adb shell pm enable "$p" </dev/null 2>&1 | tail -1
done
echo "Restoring stock home screen..."
adb shell pm enable com.google.android.apps.tv.launcherx 2>&1 | tail -1
adb shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity 2>&1 | tail -1
# Launcher removal (uninstalls only our own app, never a system one)
adb shell pm uninstall --user 0 com.nabil.tvhome 2>&1 | tail -1
adb shell settings put global window_animation_scale 1.0 2>/dev/null
adb shell settings put global transition_animation_scale 1.0 2>/dev/null
adb shell settings put global animator_duration_scale 1.0 2>/dev/null
echo "Done. Reboot the TV for a clean state: adb reboot"
