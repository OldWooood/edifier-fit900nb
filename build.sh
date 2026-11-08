#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="${BT_DIR:-$SDK/build-tools/36.1.0}"
PLATFORM="${PLATFORM:-$SDK/platforms/android-36/android.jar}"
JAVAC="${JAVAC:-javac}"
KEYTOOL="${KEYTOOL:-keytool}"

if [[ ! -x "$BT/aapt2" ]]; then
  echo "找不到 aapt2：$BT/aapt2" >&2
  echo "请设置 ANDROID_HOME 或 BT_DIR" >&2
  exit 1
fi
if [[ ! -f "$PLATFORM" ]]; then
  echo "找不到 android.jar：$PLATFORM" >&2
  exit 1
fi

rm -rf build
mkdir -p build/classes build/dex build/gen dist

echo "==> compile resources"
"$BT/aapt2" compile --dir app/src/main/res -o build/res.zip

echo "==> link resources"
"$BT/aapt2" link \
  -o build/app-unsigned.apk \
  -I "$PLATFORM" \
  --manifest app/src/main/AndroidManifest.xml \
  --java build/gen \
  --min-sdk-version 26 \
  --target-sdk-version 36 \
  --version-code 1 \
  --version-name 1.0.0 \
  build/res.zip

echo "==> compile Java"
find app/src/main/java -name '*.java' -print0 \
  | xargs -0 "$JAVAC" -encoding UTF-8 -source 17 -target 17 \
      -classpath "$PLATFORM" -d build/classes
"$JAVAC" -encoding UTF-8 -source 17 -target 17 \
  -classpath "$PLATFORM" -d build/classes \
  build/gen/com/deatrg/fit900nb/R.java

echo "==> dex"
jar cf build/classes.jar -C build/classes .
"$BT/d8" --release --lib "$PLATFORM" --min-api 26 \
  --output build/dex build/classes.jar

echo "==> package"
cp build/app-unsigned.apk build/app-with-dex.apk
(cd build/dex && zip -q -j ../app-with-dex.apk classes.dex)
"$BT/zipalign" -f -p 4 build/app-with-dex.apk build/app-aligned.apk

echo "==> sign"
KS="$ROOT/keystore/debug.keystore"
mkdir -p "$ROOT/keystore"
if [[ ! -f "$KS" ]]; then
  "$KEYTOOL" -genkeypair \
    -keystore "$KS" \
    -storepass android -keypass android \
    -alias androiddebugkey \
    -dname "CN=Android Debug,O=Android,C=US" \
    -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out dist/Fit900NB.apk build/app-aligned.apk
"$BT/apksigner" verify --print-certs dist/Fit900NB.apk | head -5

ls -lh dist/Fit900NB.apk
echo "OK: $ROOT/dist/Fit900NB.apk"
