#!/bin/bash
# Builds launcher/out/tvhome.apk with plain javac + aapt2 + d8 (no Gradle needed).
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$SDK/build-tools/34.0.0
JAR=$SDK/platforms/android-34/android.jar
rm -rf out && mkdir -p out/classes out/dex
javac --release 11 -Xlint:-deprecation -cp "$JAR" -d out/classes $(find src -name '*.java')
$BT/d8 --min-api 26 --lib "$JAR" --output out/dex $(find out/classes -name '*.class')
$BT/aapt2 link -o out/base.apk --manifest AndroidManifest.xml -I "$JAR" --min-sdk-version 26 --target-sdk-version 34
cp out/dex/classes.dex out/classes.dex
(cd out && zip -q -u base.apk classes.dex)
# assets (stored, not re-compressed jpgs)
mkdir -p out/stage && rm -rf out/stage/assets && cp -r assets out/stage/assets && rm out/stage/assets/CREDITS.txt
(cd out/stage && zip -q -0 -r ../base.apk assets)
[ -f debug.keystore ] || keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
  -alias tvhome -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=tvhome" >/dev/null 2>&1
$BT/zipalign -f -p 4 out/base.apk out/aligned.apk
$BT/apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android --out out/tvhome.apk out/aligned.apk
ls -la out/tvhome.apk
