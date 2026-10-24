#!/bin/bash
# Sends your Baseus login to the TV launcher (stored privately on the TV, never in this folder).
# Usage:  ./baseus-setup.sh [adb-serial]        then:  ./baseus-setup.sh test [adb-serial] [cloud|wake|connect|video]
#         ./baseus-setup.sh clear [adb-serial]   removes the saved login from the TV
# Run from a normal Terminal. The password is typed hidden and is not shown or logged.
set -euo pipefail
cmd="${1:-setup}"; serial="${2:-}"; what="cloud"
case "$serial" in cloud|wake|connect|video|video1) what="$serial"; serial="" ;; esac
[ -n "${3:-}" ] && what="$3"
[ "$cmd" != setup ] && [ "$cmd" != test ] && [ "$cmd" != clear ] && { serial="$cmd"; cmd=setup; }
A=(adb); [ -n "$serial" ] && A=(adb -s "$serial")
start() { "${A[@]}" shell "am start -n com.nabil.tvhome/.HomeActivity $*" | tail -1; }
case "$cmd" in
  setup)
    read -r -p "Baseus email or phone: " U
    read -r -s -p "Baseus password (hidden): " P; echo
    read -r -p "Country letters, e.g. AU for Australia [AU]: " C; C=${C:-AU}
    B=$(printf '%s|%s|%s' "$U" "$P" "$C" | base64 | tr -d '\n')
    start "--es baseus_b64 '$B'"; unset P B ;;
  test)  "${A[@]}" logcat -c; start "--es baseus_test $what"
         for _ in $(seq 1 60); do sleep 2; "${A[@]}" logcat -d -s BaseusTest | grep -qE "PASSED|FAILED" && break; done
         "${A[@]}" logcat -d -s BaseusTest | grep -v "^-" | tail -22 ;;
  clear) start "--es baseus_b64 clear" ;;
esac
