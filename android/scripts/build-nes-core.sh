#!/usr/bin/env bash
# Rebuild the bundled ARM64 MIT NES core from its matching source archive.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
: "${ANDROID_NDK_ROOT:?Set ANDROID_NDK_ROOT to Android NDK r27c}"
CC="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang"
OUT=${1:-"$ROOT/android/jniLibs/arm64-v8a/libnes_libretro.so"}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
tar -xzf "$ROOT/android/third_party/nesturbator-core-source-ab9c2e6.tar.gz" -C "$WORK"
mkdir -p "$(dirname "$OUT")"
"$CC" -std=c17 -O2 -fPIC -fvisibility=hidden -shared \
  -I"$WORK/include" -I"$WORK/host" -I"$WORK/libretro" \
  "$WORK/libretro/libretro.c" "$WORK/host/convert.c" "$WORK"/src/*.c \
  -Wl,-z,max-page-size=16384 -o "$OUT"
echo "NES core: $OUT"
