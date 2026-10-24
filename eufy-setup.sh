#!/bin/bash
# Sends your Eufy login to the TV launcher (stored privately on the TV, never in this folder) and drives the
# sign-in. Run from a normal Terminal. The password is typed hidden and is never shown or logged.
#   ./eufy-setup.sh              store the login (use the SECOND Eufy account the cameras are shared to)
#   ./eufy-setup.sh test         sign in and list the HomeBase and cameras
#   ./eufy-setup.sh test connect connect to the HomeBase over the LAN
#   ./eufy-setup.sh captcha      fetch the captcha picture, open it, and send your answer
#   ./eufy-setup.sh code 123456  send the verification code Eufy emailed you
#   ./eufy-setup.sh hide 0       hide a camera channel from the TV (use 'none' to show all again)
#   ./eufy-setup.sh clear        remove the login from the TV
set -euo pipefail
cmd="${1:-setup}"; arg="${2:-}"
SER=$(adb devices | awk 'NR==2 && $2=="device"{print $1}'); [ -n "$SER" ] || { echo "No TV connected via adb."; exit 1; }
A=(adb -s "$SER")
start() { "${A[@]}" shell "am start -n com.nabil.tvhome/.HomeActivity $*" >/dev/null; }
run_test() {  # $1 = test string
  "${A[@]}" logcat -c; start "--es eufy_test '$1'"
  for _ in $(seq 1 90); do sleep 1; "${A[@]}" logcat -d -s EufyTest | grep -qE "PASSED|FAILED|REQUIRED" && break; done
  "${A[@]}" logcat -d -s EufyTest | grep -v "^-" | cut -d: -f4- | tail -20
}
case "$cmd" in
  setup)
    read -r -p "Eufy account email: " U
    read -r -s -p "Eufy account password (hidden): " P; echo
    read -r -p "Country letters, e.g. AU [AU]: " C; C=${C:-AU}
    B=$(printf '%s|%s|%s' "$U" "$P" "$C" | base64 | tr -d '\n')
    start "--es eufy_b64 '$B'"; unset P B; echo "Login sent to the TV." ;;
  test) run_test "${arg:-cloud}" ;;
  captcha)
    F=/sdcard/Android/data/com.nabil.tvhome/files/eufy-captcha.png
    "${A[@]}" pull "$F" /tmp/eufy-captcha.png >/dev/null && open /tmp/eufy-captcha.png
    read -r -p "Type the characters in the picture: " T
    run_test "captcha:$T"; rm -f /tmp/eufy-captcha.png ;;
  code) [ -n "$arg" ] || { echo "Usage: $0 code <digits>"; exit 1; }; run_test "code:$arg" ;;
  hide) [ -n "$arg" ] || { echo "Usage: $0 hide <channel[,channel]|none>"; exit 1; }; start "--es eufy_hide '$arg'"; echo "Hidden Eufy channels: $arg" ;;
  clear) start "--es eufy_b64 clear"; echo "Removed." ;;
esac
