#!/usr/bin/env bash
# Source this file after setting ANDROID_ABI to a supported Android ABI.
set -euo pipefail
: "${ANDROID_NDK_ROOT:?Set ANDROID_NDK_ROOT to Android NDK r27c}"
ANDROID_ABI=${ANDROID_ABI:-arm64-v8a}
case "$ANDROID_ABI" in
  arm64-v8a)   ANDROID_TRIPLE=aarch64-linux-android ;;
  armeabi-v7a) ANDROID_TRIPLE=armv7a-linux-androideabi ;;
  x86)         ANDROID_TRIPLE=i686-linux-android ;;
  x86_64)      ANDROID_TRIPLE=x86_64-linux-android ;;
  *) echo "Unsupported Android ABI: $ANDROID_ABI" >&2; return 1 2>/dev/null || exit 1 ;;
esac
ANDROID_LLVM="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
ANDROID_CC="$ANDROID_LLVM/${ANDROID_TRIPLE}24-clang"
[ -x "$ANDROID_CC" ] || { echo "Missing compiler: $ANDROID_CC" >&2; return 1 2>/dev/null || exit 1; }
export ANDROID_ABI ANDROID_TRIPLE ANDROID_LLVM ANDROID_CC
