#!/bin/bash
# Reversibly disables (never uninstalls) packages in packages_to_disable.txt, plus one component
cd "$(dirname "$0")"
[ -n "$1" ] && export ANDROID_SERIAL="$1"
grep -v '^#' packages_to_disable.txt | awk '{print $1}' | while read -r p; do
  [ -z "$p" ] && continue
  echo -n "$p: "; adb shell pm disable-user --user 0 "$p" </dev/null 2>&1 | tail -1
done
