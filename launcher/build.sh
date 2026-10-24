#!/bin/bash
# Builds launcher/out/tvhome.apk with plain javac + aapt2 + d8 (no Gradle needed).
# GeckoView (the doorbell's in-app browser) and its libraries come from ./fetch-gecko.py into gecko/.
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$SDK/build-tools/34.0.0
D8=$SDK/build-tools/36.1.0/d8                 # newer d8 reads the libraries' Java 17 class files
JAR=$SDK/platforms/android-34/android.jar
[ -d gecko ] || { echo "Run ./fetch-gecko.py first (downloads GeckoView)."; exit 1; }
rm -rf out && mkdir -p out/classes out/dex out/gen out/aar out/stage/lib/armeabi-v7a
# the doorbell extension's script must parse (checked with macOS's JavaScript engine)
for js in assets/doorbell/*.js; do
  osascript -l JavaScript -e "ObjC.import('Foundation'); new Function(\$.NSString.stringWithContentsOfFileEncodingError('$js', 4, null).js)" >/dev/null \
    || { echo "Syntax error in $js"; exit 1; }
done

# libraries: unpack the AARs, compile their resources, collect jars, manifests and native code
LIBS=(); RES=(); PKGS=(); MANIFESTS=()
for a in gecko/*.aar; do
  d=out/aar/$(basename "$a" .aar); mkdir -p "$d"; unzip -qo "$a" -d "$d"
  [ -f "$d/classes.jar" ] && LIBS+=("$d/classes.jar")
  for j in "$d"/libs/*.jar; do [ -f "$j" ] && LIBS+=("$j"); done
  if [ -d "$d/res" ] && [ -n "$(ls -A "$d/res")" ]; then
    $BT/aapt2 compile --dir "$d/res" -o "$d/res.zip"; RES+=(-R "$d/res.zip")
  fi
  PKGS+=("$(sed -n 's/.*package="\([^"]*\)".*/\1/p' "$d/AndroidManifest.xml" | head -1)")
  MANIFESTS+=("$d/AndroidManifest.xml")
  [ -d "$d/jni/armeabi-v7a" ] && cp "$d"/jni/armeabi-v7a/*.so out/stage/lib/armeabi-v7a/
  [ -d "$d/assets" ] && mkdir -p out/stage/assets && cp -r "$d"/assets/. out/stage/assets/
done
for j in gecko/*.jar; do [ "$(unzip -l "$j" | grep -c '\.class$')" -gt 0 ] && LIBS+=("$j"); done
CP=$(IFS=:; echo "$JAR:${LIBS[*]}")

python3 merge-manifest.py AndroidManifest.xml out/AndroidManifest.xml "${MANIFESTS[@]}"
$BT/aapt2 link -o out/base.apk --manifest out/AndroidManifest.xml -I "$JAR" --min-sdk-version 26 --target-sdk-version 34 \
  "${RES[@]}" --auto-add-overlay --java out/gen --extra-packages "$(IFS=:; echo "${PKGS[*]}")"
javac --release 11 -nowarn -Xlint:-deprecation -cp "$CP" -d out/classes $(find src out/gen -name '*.java')
$D8 --min-api 26 --lib "$JAR" --output out/dex $(find out/classes -name '*.class') "${LIBS[@]}"
(cd out/dex && zip -q -u ../base.apk classes*.dex)
(cd out/stage && zip -q -r ../base.apk lib)
# assets stored, not re-compressed (jpgs; Gecko reads omni.ja in place)
cp -r assets/. out/stage/assets/ && rm -f out/stage/assets/CREDITS.txt
(cd out/stage && zip -q -0 -r ../base.apk assets)
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias tvhome -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tvhome" >/dev/null 2>&1
$BT/zipalign -f -p 4 out/base.apk out/aligned.apk
$BT/apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --out out/tvhome.apk out/aligned.apk
ls -la out/tvhome.apk
