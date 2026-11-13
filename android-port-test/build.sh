#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")" && pwd)
BUILD_DIR="$ROOT_DIR/build-$(date +%Y%m%d%H%M%S)"
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/lib/android-sdk}}"
PLATFORM="$SDK_ROOT/platforms/${ANDROID_PLATFORM:-android-23}"
BUILD_TOOLS="$SDK_ROOT/build-tools/35.0.0"
NATIVE_BUILD_TOOLS="$SDK_ROOT/build-tools/debian"
NATIVE_LIBS_DIR="${APINEX_NATIVE_LIBS_DIR:-}"
PORT_ASSET="${APINEX_PORT_ASSET:-}"
AAPT2="${AAPT2:-$BUILD_TOOLS/aapt2}"
ZIPALIGN="${ZIPALIGN:-$BUILD_TOOLS/zipalign}"
APKSIGNER="${APKSIGNER:-$BUILD_TOOLS/apksigner}"
if [ "$(uname -m)" != "x86_64" ] && [ -x "$NATIVE_BUILD_TOOLS/aapt2" ]; then AAPT2="$NATIVE_BUILD_TOOLS/aapt2"; fi
if [ "$(uname -m)" != "x86_64" ] && [ -x "/usr/bin/zipalign" ]; then ZIPALIGN="/usr/bin/zipalign"; fi
if [ "$(uname -m)" != "x86_64" ] && [ -x "/usr/bin/apksigner" ]; then APKSIGNER="/usr/bin/apksigner"; fi
if [ ! -x "$AAPT2" ] && [ -x "$NATIVE_BUILD_TOOLS/aapt2" ]; then AAPT2="$NATIVE_BUILD_TOOLS/aapt2"; fi
if [ ! -x "$ZIPALIGN" ] && [ -x "/usr/bin/zipalign" ]; then ZIPALIGN="/usr/bin/zipalign"; fi
if [ ! -x "$APKSIGNER" ] && [ -x "/usr/bin/apksigner" ]; then APKSIGNER="/usr/bin/apksigner"; fi

mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$BUILD_DIR/apk"

RESOURCE_ZIP="$BUILD_DIR/resources.flat.zip"
if [ -d "$ROOT_DIR/res" ]; then
  "$AAPT2" compile --dir "$ROOT_DIR/res" -o "$RESOURCE_ZIP"
fi

javac -source 8 -target 8 -classpath "$PLATFORM/android.jar" \
  -d "$BUILD_DIR/classes" \
  "$ROOT_DIR/src/com/apinex/porttest/MainActivity.java"

jar --create --file "$BUILD_DIR/classes.jar" -C "$BUILD_DIR/classes" .

"$BUILD_TOOLS/d8" --lib "$PLATFORM/android.jar" \
  --output "$BUILD_DIR/dex" "$BUILD_DIR/classes.jar"

"$AAPT2" link \
  -o "$BUILD_DIR/apk/port-test-unsigned.apk" \
  -I "$PLATFORM/android.jar" \
  --min-sdk-version 23 \
  --target-sdk-version 28 \
  --manifest "$ROOT_DIR/AndroidManifest.compat.xml" \
  "$RESOURCE_ZIP"

unzip -q -j "$BUILD_DIR/dex/classes.dex" -d "$BUILD_DIR/apk" 2>/dev/null || cp "$BUILD_DIR/dex/classes.dex" "$BUILD_DIR/apk/classes.dex"
if [ ! -f "$BUILD_DIR/apk/classes.dex" ]; then
  cp "$BUILD_DIR/dex/classes.dex" "$BUILD_DIR/apk/classes.dex"
fi

if [ -n "$NATIVE_LIBS_DIR" ]; then
  test -f "$NATIVE_LIBS_DIR/libretro.so"
  test -f "$NATIVE_LIBS_DIR/libapinex_host.so"
  mkdir -p "$BUILD_DIR/apk/lib/arm64-v8a"
  cp "$NATIVE_LIBS_DIR/libretro.so" "$BUILD_DIR/apk/lib/arm64-v8a/libretro.so"
  cp "$NATIVE_LIBS_DIR/libapinex_host.so" "$BUILD_DIR/apk/lib/arm64-v8a/libapinex_host.so"
fi

if [ -n "$PORT_ASSET" ]; then
  test -f "$PORT_ASSET"
  mkdir -p "$BUILD_DIR/apk/assets/games"
  cp "$PORT_ASSET" "$BUILD_DIR/apk/assets/games/$(basename "$PORT_ASSET")"
fi

if [ -n "${APINEX_PORT_ASSET_DIR:-}" ]; then
  test -d "$APINEX_PORT_ASSET_DIR"
  mkdir -p "$BUILD_DIR/apk/assets/games"
  cp -a "$APINEX_PORT_ASSET_DIR"/. "$BUILD_DIR/apk/assets/games/"
fi

cd "$BUILD_DIR/apk"
zip -q -u port-test-unsigned.apk classes.dex
if [ -d lib ]; then zip -q -r -u port-test-unsigned.apk lib; fi
if [ -d assets ]; then zip -q -r -u port-test-unsigned.apk assets; fi

KEYSTORE="${HOME:-/root}/.android/debug.keystore"
mkdir -p "$(dirname "$KEYSTORE")"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -v -keystore "$KEYSTORE" -storepass android \
    -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Android Debug,O=Android,C=US" >/dev/null
fi

"$ZIPALIGN" -f 4 port-test-unsigned.apk port-test-aligned.apk
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass pass:android \
  --ks-key-alias androiddebugkey --key-pass pass:android \
  --out port-test-debug.apk port-test-aligned.apk
"$APKSIGNER" verify --verbose port-test-debug.apk
printf 'APK=%s\n' "$BUILD_DIR/apk/port-test-debug.apk"
