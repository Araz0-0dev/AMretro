#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
APP="$ROOT/android"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to an Android SDK containing platform 35}"
: "${ANDROID_NDK_ROOT:?Set ANDROID_NDK_ROOT to Android NDK r27c}"
: "${AMRETRO_KEYSTORE:?Set AMRETRO_KEYSTORE to your private Android signing keystore}"
: "${AMRETRO_STOREPASS_FILE:?Set AMRETRO_STOREPASS_FILE to a private file containing the keystore password}"
BT=${ANDROID_BUILD_TOOLS_DIR:-"$ANDROID_SDK_ROOT/build-tools/35.0.0"}
PLATFORM=${ANDROID_PLATFORM_JAR:-"$ANDROID_SDK_ROOT/platforms/android-35/android.jar"}
TOOLCHAIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"
BUILD=${AMRETRO_ANDROID_BUILD_DIR:-"$APP/.build-local"}
OUT=${AMRETRO_ANDROID_APK:-"$ROOT/releases/AMretro-Android-0.1.0-beta-arm64.apk"}
[ -f "$PLATFORM" ] && [ -x "$BT/aapt" ] && [ -x "$TOOLCHAIN/bin/aarch64-linux-android24-clang" ] || { echo 'Android SDK / NDK paths are invalid.' >&2; exit 1; }
mkdir -p "$BUILD" "$(dirname "$OUT")"
rm -rf "$BUILD/classes" "$BUILD/dex" "$BUILD/apk" "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/apk/lib/arm64-v8a"
"$TOOLCHAIN/bin/aarch64-linux-android24-clang" -std=c11 -D_POSIX_C_SOURCE=200809L -O2 -fPIC -shared -Wall -Wextra -Werror \
  -Wl,-z,max-page-size=16384 -o "$BUILD/apk/lib/arm64-v8a/libamretro.so" \
  "$APP/native/core_host.c" "$APP/native/android_jni.c" -landroid -llog -ldl
cp "$APP/jniLibs/arm64-v8a/libmgba_libretro.so" "$BUILD/apk/lib/arm64-v8a/"
"$BT/aapt" package -f -M "${AMRETRO_ANDROID_MANIFEST:-$APP/AndroidManifest.xml}" -S "$APP/res" -A "$APP/assets" -I "$PLATFORM" -F "$BUILD/unsigned.apk"
find "${AMRETRO_ANDROID_SRC_DIR:-$APP/src}" -name '*.java' -print > "$BUILD/java-sources.txt"
javac -source 8 -target 8 -classpath "$PLATFORM" -encoding UTF-8 -d "$BUILD/classes" @"$BUILD/java-sources.txt"
find "$BUILD/classes" -name '*.class' -print > "$BUILD/class-files.txt"
"$BT/d8" --min-api 24 --lib "$PLATFORM" --output "$BUILD/dex" @"$BUILD/class-files.txt"
(cd "$BUILD/dex" && zip -q -0 "$BUILD/unsigned.apk" classes.dex)
(cd "$BUILD/apk" && zip -q -0 -r "$BUILD/unsigned.apk" lib)
"$BT/zipalign" -f -P 16 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
"$BT/apksigner" sign --ks "$AMRETRO_KEYSTORE" --ks-key-alias amretro --ks-pass "file:$AMRETRO_STOREPASS_FILE" --out "$OUT" "$BUILD/aligned.apk"
"$BT/apksigner" verify --verbose "$OUT"
"$BT/zipalign" -c -P 16 4 "$OUT"
echo "APK: $OUT"
