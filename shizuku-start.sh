#!/bin/bash
# Starts Shizuku on the TV, which gives the launcher's Clean up button adb-level rights (close background apps, clear
# every app's cache). Needed again after each TV restart. Run with the TV connected over adb.
set -euo pipefail
SER=$(adb devices | awk 'NR==2 && $2=="device"{print $1}'); [ -n "$SER" ] || { echo "No TV connected via adb."; exit 1; }
DIR=$(adb -s "$SER" shell pm path moe.shizuku.privileged.api | head -1 | sed 's/package://; s/base.apk//' | tr -d '\r')
[ -n "$DIR" ] || { echo "Shizuku isn't installed on the TV."; exit 1; }
adb -s "$SER" shell "${DIR}lib/arm/libshizuku.so" | tail -2
adb -s "$SER" shell pm grant com.nabil.tvhome moe.shizuku.manager.permission.API_V23 2>/dev/null || true
