#!/bin/bash
# Connects the TV launcher's Solar screen to a Solis inverter (and its battery) through the SolisCloud API.
# Request API access once in SolisCloud (Account > Basic Settings > API Management); it gives a KeyID and a KeySecret.
# The secret is typed hidden, checked against SolisCloud here, and sent only to the TV (it signs every request).
#   ./solis-setup.sh            set up (asks for the KeyID and KeySecret)
#   ./solis-setup.sh test       log what the TV reads from SolisCloud
#   ./solis-setup.sh demo       show made-up data (to preview the screen)
#   ./solis-setup.sh clear      remove the Solar screen
set -euo pipefail
cmd="${1:-setup}"
SER=$(adb devices | awk 'NR==2 && $2=="device"{print $1}'); [ -n "$SER" ] || { echo "No TV connected via adb."; exit 1; }
A=(adb -s "$SER")
start() { "${A[@]}" shell "am start -n com.nabil.tvhome/.HomeActivity $*" >/dev/null; }
case "$cmd" in
  clear) start "--es solis clear"; echo "Removed."; exit 0 ;;
  demo)  start "--es solis demo"; start "--es show solar"; echo "Showing demo data."; exit 0 ;;
  test)
    "${A[@]}" logcat -c; start "--es solis_test now"
    for _ in $(seq 1 20); do sleep 1; "${A[@]}" logcat -d -s Solis | grep -qE "today:|test:" && break; done
    "${A[@]}" logcat -d -s Solis | grep -v "^-" | cut -d: -f4- | tail -80; exit 0 ;;
esac

read -r -p "SolisCloud API KeyID: " SOLIS_ID
read -r -s -p "SolisCloud API KeySecret (hidden): " SOLIS_SECRET; echo
export SOLIS_ID SOLIS_SECRET
OUT=$(python3 -I - <<'PY'
import base64, email.utils, hashlib, hmac, json, os, sys, urllib.request, urllib.error

KEY, SECRET = os.environ["SOLIS_ID"].strip(), os.environ["SOLIS_SECRET"].strip()
TYPE = "application/json;charset=UTF-8"

def call(path, body):
    b = json.dumps(body).encode()
    md5 = base64.b64encode(hashlib.md5(b).digest()).decode()
    date = email.utils.formatdate(usegmt=True)
    sign = base64.b64encode(hmac.new(SECRET.encode(), f"POST\n{md5}\n{TYPE}\n{date}\n{path}".encode(), hashlib.sha1).digest()).decode()
    req = urllib.request.Request("https://www.soliscloud.com:13333" + path, data=b, headers={
        "Content-Type": TYPE, "Content-MD5": md5, "Date": date, "Authorization": f"API {KEY}:{sign}"})
    try:
        r = json.load(urllib.request.urlopen(req, timeout=20))
    except urllib.error.HTTPError as e:
        sys.exit(f"SolisCloud refused the key (HTTP {e.code}): {e.read().decode()[:200]}")
    if not r.get("success") and str(r.get("code")) != "0":
        sys.exit(f"SolisCloud: {r.get('code')} {r.get('msg')}")
    return r["data"]

st = call("/v1/api/userStationList", {"pageNo": 1, "pageSize": 20})
recs = (st.get("page") or {}).get("records") or []
if not recs:
    sys.exit("No plants found on this SolisCloud account.")
if len(recs) > 1:
    print("Plants: " + ", ".join(r.get("stationName", "?") for r in recs) + " (using the first)", file=sys.stderr)
s = recs[0]
sid, name = str(s["id"]), s.get("stationName") or "Solar"
d = call("/v1/api/stationDetail", {"id": sid})
print(f"OK|{sid}|{name}|{d.get('power')} {d.get('powerStr')}|{d.get('batteryPercent')}", end="")
PY
)
IFS='|' read -r ok sid name pv soc <<<"$OUT"
[ "$ok" = "OK" ] || { echo "$OUT"; exit 1; }
B=$(printf '%s|%s|%s|%s' "$SOLIS_ID" "$SOLIS_SECRET" "$sid" "$name" | base64 | tr -d '\n')
unset SOLIS_SECRET
start "--es solis '$B'"; unset B
echo "Found '$name' (solar $pv, battery ${soc}%). Sent to the TV; the Solar button is in the dock."
