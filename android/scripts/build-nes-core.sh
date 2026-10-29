#!/usr/bin/env bash
# Rebuild the bundled MIT NES core for the selected Android ABI.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
source "$ROOT/android/scripts/abi-env.sh"
OUT=${1:-"$ROOT/android/jniLibs/$ANDROID_ABI/libnes_libretro.so"}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
tar -xzf "$ROOT/android/third_party/nesturbator-core-source-ab9c2e6.tar.gz" -C "$WORK"
patch -s -d "$WORK" -p1 < "$ROOT/android/patches/nesturbator-android-32bit.patch"
mkdir -p "$(dirname "$OUT")"
"$ANDROID_CC" -std=c17 -O2 -fPIC -fvisibility=hidden -shared \
  -I"$WORK/include" -I"$WORK/host" -I"$WORK/libretro" \
  "$WORK/libretro/libretro.c" "$WORK/host/convert.c" "$WORK"/src/*.c \
  -Wl,-z,max-page-size=16384 -o "$OUT"
"$ANDROID_LLVM/llvm-strip" --strip-unneeded "$OUT"
echo "NES core ($ANDROID_ABI): $OUT"
