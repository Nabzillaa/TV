#!/bin/bash
# Connects the TV launcher to a Meross smart garage door opener (MSG100/MSG200) on the local network.
# Signs in to Meross once, here on the Mac (the password is typed hidden and never stored or sent to the TV), to get
# the account key the opener checks on every local command; then sends the TV only that key and the opener's
# address. Run from a normal Terminal with the TV connected over adb.
#   ./meross-setup.sh                set up (asks for the Meross login)
#   ./meross-setup.sh test           read the door state through the TV launcher's settings
#   ./meross-setup.sh clear          remove the garage door from the TV
set -euo pipefail
cmd="${1:-setup}"
SER=$(adb devices | awk 'NR==2 && $2=="device"{print $1}'); [ -n "$SER" ] || { echo "No TV connected via adb."; exit 1; }
A=(adb -s "$SER")
start() { "${A[@]}" shell "am start -n com.nabil.tvhome/.HomeActivity $*" >/dev/null; }
case "$cmd" in
  clear) start "--es garage clear"; echo "Removed."; exit 0 ;;
  test)
    "${A[@]}" logcat -c; start "--es garage_test state"
    for _ in $(seq 1 15); do sleep 1; "${A[@]}" logcat -d -s Garage | grep -qE "state|FAILED" && break; done
    "${A[@]}" logcat -d -s Garage | grep -v "^-" | cut -d: -f4- | tail -5; exit 0 ;;
esac

read -r -p "Meross account email: " MEROSS_EMAIL
read -r -s -p "Meross account password (hidden): " MEROSS_PASSWORD; echo
export MEROSS_EMAIL MEROSS_PASSWORD
OUT=$(python3 -I - <<'PY'
import base64, hashlib, json, os, random, string, sys, time, urllib.request

SECRET = "23x17ahWarFH6w29"   # the Meross app's public request-signing constant
HEADERS = {"AppVersion": "0.4.9.4", "Vender": "meross", "AppType": "MerossIOT", "AppLanguage": "EN",
           "User-Agent": "MerossIOT/0.4.9.4", "Content-Type": "application/json"}

def call(base, path, params, token=None):
    enc = base64.b64encode(json.dumps(params).encode()).decode()
    nonce = "".join(random.choice(string.ascii_uppercase + string.digits) for _ in range(16))
    ts = int(time.time() * 1000)
    sign = hashlib.md5((SECRET + str(ts) + nonce + enc).encode()).hexdigest()
    h = dict(HEADERS, Authorization="Basic " + (token or ""))
    req = urllib.request.Request(base + path, data=json.dumps({"params": enc, "sign": sign, "timestamp": ts, "nonce": nonce}).encode(), headers=h)
    return json.load(urllib.request.urlopen(req, timeout=20))

def local(ip, key, ns, method="GET", payload=None):
    mid = os.urandom(16).hex(); ts = int(time.time())
    body = {"header": {"messageId": mid, "namespace": ns, "method": method, "payloadVersion": 1, "from": "/app/tv/subscribe",
                       "timestamp": ts, "sign": hashlib.md5((mid + key + str(ts)).encode()).hexdigest()}, "payload": payload or {}}
    req = urllib.request.Request(f"http://{ip}/config", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=8))

login = {"email": os.environ["MEROSS_EMAIL"], "password": hashlib.md5(os.environ["MEROSS_PASSWORD"].encode()).hexdigest(),
         "encryption": 1, "accountCountryCode": "", "mfaCode": None, "agree": 0,
         "mobileInfo": {"deviceModel": "tv", "mobileOsVersion": "14", "mobileOs": "Android", "uuid": "tvhome", "carrier": ""}}
base = "https://iotx-ap.meross.com"
r = call(base, "/v1/Auth/signIn", login)
if r.get("apiStatus") == 1030 and r.get("data", {}).get("domain"):        # account lives in another region
    base = r["data"]["domain"]; r = call(base, "/v1/Auth/signIn", login)
if r.get("apiStatus") != 0:
    sys.exit("Meross sign-in failed: %s %s" % (r.get("apiStatus"), r.get("info") or r.get("data")))
data = r["data"]; key, token = data["key"], data["token"]
base = data.get("domain") or base
devs = call(base, "/v1/Device/devList", {}, token).get("data", [])
gd = [d for d in devs if d.get("deviceType", "").lower().startswith("msg")]
if not gd:
    sys.exit("No garage door opener found on this Meross account. Devices: " + ", ".join(f"{d.get('devName')} ({d.get('deviceType')})" for d in devs))
d = gd[0]
uuid, name = d["uuid"], d.get("devName") or "Garage door"
# find it on the LAN by MAC and check that the key works locally
mac = uuid.lower()[-12:]                                   # Meross uuids end with the device's MAC
ip = None
for line in os.popen("arp -an").read().splitlines():       # macOS drops leading zeros: 48:e1:e9:31:f4:4
    parts = line.split()
    if len(parts) > 3 and "(" in parts[1]:
        if "".join(b.zfill(2) for b in parts[3].split(":")).lower() == mac:
            ip = parts[1].strip("()")
if not ip:                                                  # not in the ARP cache: ask every address on the LAN
    import concurrent.futures, socket
    me = socket.gethostbyname(socket.gethostname()).rsplit(".", 1)[0]
    def probe(a):
        try:
            r = local(a, key, "Appliance.System.All")
            return a if r["payload"]["all"]["system"]["hardware"]["uuid"] == uuid else None
        except Exception:
            return None
    with concurrent.futures.ThreadPoolExecutor(64) as ex:
        ip = next((a for a in ex.map(probe, [f"{me}.{i}" for i in range(1, 255)]) if a), None)
if not ip:
    sys.exit("Couldn't find the opener (MAC %s) on the network; is it online?" % mac)
st = local(ip, key, "Appliance.GarageDoor.State")
if st["header"].get("method") == "ERROR":
    sys.exit("The opener at %s rejected the key: %s" % (ip, st.get("payload")))
states = st["payload"]["state"]
ch = states[0].get("channel", 0)
print("OK|%s|%s|%s|%d|%s|%s" % (ip, key, uuid, ch, name, "open" if states[0].get("open") else "closed"), end="")
PY
)
unset MEROSS_PASSWORD MEROSS_EMAIL
IFS='|' read -r ok ip key uuid ch name state <<<"$OUT"
[ "$ok" = "OK" ] || { echo "$OUT"; exit 1; }
B=$(printf '%s|%s|%s|%s|%s' "$ip" "$key" "$uuid" "$ch" "$name" | base64 | tr -d '\n')
start "--es garage '$B'"; unset key B
echo "Found '$name' at $ip (door is $state). Sent to the TV."
