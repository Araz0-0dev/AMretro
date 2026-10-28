#!/usr/bin/env bash
# Rebuild the firmware-free ARM64 Mega Drive core from AMretro's source archive.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
: "${ANDROID_NDK_ROOT:?Set ANDROID_NDK_ROOT to Android NDK r27c}"
command -v cmake >/dev/null && command -v ninja >/dev/null && command -v g++ >/dev/null || { echo 'CMake >=3.28, Ninja and a host C++ compiler are required.' >&2; exit 1; }
OUT=${1:-"$ROOT/android/jniLibs/arm64-v8a/libares_md_libretro.so"}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
tar -xzf "$ROOT/android/third_party/ares-md-source-449b937-amretro.tar.gz" -C "$WORK"
SRC="$WORK/ares-clean"; BUILD="$WORK/target"
if find "$SRC" -type f \( -iname '*.rom' -o -iname '*.mrom' -o -iname '*.srom' -o -iname '*.bios' \) | grep -q .; then
  echo 'Unexpected firmware-like file in source archive.' >&2; exit 1
fi
# Resource generation runs a host executable during the Android cross build.
g++ -std=c++20 -O1 -DNALL_HEADER_ONLY -I"$SRC" -I"$SRC/nall" "$SRC/tools/sourcery/sourcery.cpp" -o "$WORK/sourcery"
export PATH="$WORK:$PATH"
cmake -S "$SRC" -B "$BUILD" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DARES_CROSSCOMPILING=ON \
  -DARES_ENABLE_CHD=OFF -DARES_BUILD_LOCAL=OFF -DARES_ENABLE_MINIMUM_CPU=OFF -DENABLE_IPO=OFF \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS_RELEASE='-O2 -DNDEBUG' -DCMAKE_C_FLAGS_RELEASE='-O2 -DNDEBUG' \
  -DCMAKE_SHARED_LINKER_FLAGS='-Wl,-z,max-page-size=16384'
cmake --build "$BUILD" --target ares_md_libretro -j "${AMRETRO_BUILD_JOBS:-1}"
CORE=$(find "$BUILD" -name libares_md_libretro.so -type f -print -quit)
[ -n "$CORE" ] || { echo 'Core not found after build.' >&2; exit 1; }
mkdir -p "$(dirname "$OUT")"
"$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded -o "$OUT" "$CORE"
echo "Firmware-free Mega Drive core: $OUT"
