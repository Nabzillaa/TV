#!/bin/bash
# Adds Amber Electric prices and billed usage to the TV launcher's Solar screen (money earned and spent).
# Make a token in the Amber app: Settings > Amber for Developers > Generate new token. It's typed hidden, checked
# against Amber here, and sent only to the TV.
#   ./amber-setup.sh          set up (asks for the token)
#   ./amber-setup.sh test     log what the TV reads from Amber
#   ./amber-setup.sh clear    remove it (the Solar screen goes back to estimates)
set -euo pipefail
cmd="${1:-setup}"
SER=$(adb devices | awk 'NR==2 && $2=="device"{print $1}'); [ -n "$SER" ] || { echo "No TV connected via adb."; exit 1; }
A=(adb -s "$SER")
start() { "${A[@]}" shell "am start -n com.nabil.tvhome/.HomeActivity $*" >/dev/null; }
case "$cmd" in
  clear) start "--es amber clear"; echo "Removed."; exit 0 ;;
  test)
    "${A[@]}" logcat -c; start "--es amber_test now"
    for _ in $(seq 1 30); do sleep 1; "${A[@]}" logcat -d -s Amber | grep -qE "now:|test:" && break; done
    "${A[@]}" logcat -d -s Amber | grep -v "^-" | cut -d: -f4- | tail -10; exit 0 ;;
esac
read -r -s -p "Amber API token (hidden): " AMBER_TOKEN; echo
export AMBER_TOKEN
OUT=$(python3 -I - <<'PY'
import json, os, sys, urllib.request, urllib.error
req = urllib.request.Request("https://api.amber.com.au/v1/sites", headers={"Authorization": "Bearer " + os.environ["AMBER_TOKEN"].strip()})
try:
    sites = json.load(urllib.request.urlopen(req, timeout=20))
except urllib.error.HTTPError as e:
    sys.exit(f"Amber refused the token (HTTP {e.code})")
live = [s for s in sites if s.get("status") != "closed"]
if not live:
    sys.exit("No active Amber site on this account.")
s = live[0]
print(f"OK|{s.get('network', '')}|{', '.join(c.get('type', '') for c in s.get('channels', []))}", end="")
PY
)
IFS='|' read -r ok network channels <<<"$OUT"
[ "$ok" = "OK" ] || { echo "$OUT"; exit 1; }
start "--es amber '$(printf %s "$AMBER_TOKEN" | tr -d "'\n ")'"; unset AMBER_TOKEN
echo "Amber site found ($network; $channels). Sent to the TV; open Solar and pick the money tile."
