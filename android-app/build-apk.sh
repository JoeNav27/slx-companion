#!/bin/bash
# Build the SLX Companion APK directly with Android build-tools
# (aapt2 + javac + d8 + zipalign + apksigner). No Gradle: Java-based
# downloads don't survive this VM's egress proxy, and the app has no
# dependencies beyond android.jar, so the direct pipeline is exact.
set -euo pipefail

TOOLS="$HOME/workspace/.tools"
export JAVA_HOME="$TOOLS/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
BT="$TOOLS/android-sdk/build-tools/34.0.0"
ANDROID_JAR="$TOOLS/android-sdk/platforms/android-34/android.jar"

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC="$APP_DIR/app/src/main"
OUT="$APP_DIR/build-manual"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

# Debug keystore (throwaway, for sideloading only)
KS="$APP_DIR/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi

echo "[1/5] aapt2 compile + link (manifest+res -> base APK)"
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" \
  --manifest "$SRC/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --version-code 55 --version-name 0.55 "$OUT/res.zip"

echo "[2/5] javac"
find "$SRC/java" -name '*.java' > "$OUT/sources.txt"
find "$OUT/gen" -name '*.java' >> "$OUT/sources.txt"
javac -encoding UTF-8 -source 17 -target 17 -cp "$ANDROID_JAR" -d "$OUT/classes" @"$OUT/sources.txt"

echo "[3/5] d8 (bytecode -> dex)"
"$BT/d8" --lib "$ANDROID_JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "[4/5] package + zipalign"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X "../unsigned.apk" classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "[5/5] sign + verify"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/slx-companion.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify --print-certs "$OUT/slx-companion.apk" | head -3
"$BT/aapt2" dump badging "$OUT/slx-companion.apk" | head -4
ls -la "$OUT/slx-companion.apk"
echo "BUILD_OK $OUT/slx-companion.apk"
