#!/usr/bin/env bash
# Build the TV app without Gradle: scripts/build.sh  ->  build/HdmiBoot.apk
#
# Required environment:
#   JAVA_HOME    a JDK 17
#   BUILD_TOOLS  an Android build-tools directory (aapt2, d8, zipalign, apksigner), e.g. r34
#   ANDROID_JAR  android.jar of platform 28 or newer
# Optional:
#   KEYSTORE     signing keystore (default: ./debug.keystore, created if missing)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${JAVA_HOME:?set JAVA_HOME to a JDK 17}"
: "${BUILD_TOOLS:?set BUILD_TOOLS to an Android build-tools directory}"
: "${ANDROID_JAR:?set ANDROID_JAR to android.jar of platform 28 or newer}"
export PATH="$JAVA_HOME/bin:$PATH"

KEYSTORE="${KEYSTORE:-$ROOT/debug.keystore}"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias key -keyalg RSA -keysize 2048 -validity 3650 -dname "CN=local" >/dev/null
fi

WORK="$ROOT/build"
rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/dex"

"$BUILD_TOOLS/aapt2" compile --dir "$ROOT/tv-app/res" -o "$WORK/res.zip"
"$BUILD_TOOLS/aapt2" link -o "$WORK/base.apk" -I "$ANDROID_JAR" \
  --manifest "$ROOT/tv-app/AndroidManifest.xml" \
  --min-sdk-version 21 --target-sdk-version 25 "$WORK/res.zip"
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -d "$WORK/classes" \
  $(find "$ROOT/tv-app/src" -name '*.java') 2>&1 | grep -v -E '^(warning|Note)|bootstrap' || true
"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --min-api 21 --output "$WORK/dex" \
  $(find "$WORK/classes" -name '*.class')

cp "$WORK/base.apk" "$WORK/app.apk"
(cd "$WORK/dex" && zip -q -j ../app.apk classes.dex)
"$BUILD_TOOLS/zipalign" -f 4 "$WORK/app.apk" "$WORK/aligned.apk"
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$WORK/HdmiBoot.apk" "$WORK/aligned.apk"
"$BUILD_TOOLS/apksigner" verify "$WORK/HdmiBoot.apk"
echo "built $WORK/HdmiBoot.apk"
