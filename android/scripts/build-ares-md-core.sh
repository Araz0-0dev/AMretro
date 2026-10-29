#!/usr/bin/env bash
# Rebuild the firmware-free Mega Drive core for the selected Android ABI.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
source "$ROOT/android/scripts/abi-env.sh"
command -v cmake >/dev/null && command -v ninja >/dev/null && command -v g++ >/dev/null || { echo 'CMake >=3.28, Ninja and a host C++ compiler are required.' >&2; exit 1; }
OUT=${1:-"$ROOT/android/jniLibs/$ANDROID_ABI/libares_md_libretro.so"}
# An optional per-ABI work directory lets an interrupted cross-build resume.
# A cache with a different source/patch/ABI/NDK is rejected, never silently reused.
if [ -n "${AMRETRO_ARES_WORKDIR:-}" ]; then
  WORK="$AMRETRO_ARES_WORKDIR"; mkdir -p "$WORK"
else
  WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
fi
SRC="$WORK/ares-clean"; BUILD="$WORK/target"
ARCHIVE="$ROOT/android/third_party/ares-md-source-449b937-amretro.tar.gz"
PATCH="$ROOT/android/patches/ares-android-compat.patch"
FINGERPRINT=$({ sha256sum "$ARCHIVE" "$PATCH"; printf '%s\n%s\n' "$ANDROID_ABI" "$ANDROID_NDK_ROOT"; } | sha256sum | cut -d' ' -f1)
if [ -e "$WORK/.amretro-fingerprint" ]; then
  [ "$(cat "$WORK/.amretro-fingerprint")" = "$FINGERPRINT" ] || { echo "Stale ares work directory: $WORK" >&2; exit 1; }
else
  [ ! -e "$SRC" ] || { echo "Unverified existing source directory: $SRC" >&2; exit 1; }
  tar -xzf "$ARCHIVE" -C "$WORK"
  # Count conversion is checked/bounded on 32-bit; no diagnostics suppressed.
  patch -s -d "$SRC" -p1 < "$PATCH"
  printf '%s\n' "$FINGERPRINT" > "$WORK/.amretro-fingerprint"
fi
if find "$SRC" -type f \( -iname '*.rom' -o -iname '*.mrom' -o -iname '*.srom' -o -iname '*.bios' \) | grep -q .; then
  echo 'Unexpected firmware-like file in source archive.' >&2; exit 1
fi
# Resource generation runs a host executable during the Android cross build.
g++ -std=c++20 -O1 -DNALL_HEADER_ONLY -I"$SRC" -I"$SRC/nall" "$SRC/tools/sourcery/sourcery.cpp" -o "$WORK/sourcery"
export PATH="$WORK:$PATH"
cmake -S "$SRC" -B "$BUILD" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ANDROID_ABI" -DANDROID_PLATFORM=android-24 -DARES_CROSSCOMPILING=ON \
  -DARES_ENABLE_CHD=OFF -DARES_BUILD_LOCAL=OFF -DARES_ENABLE_MINIMUM_CPU=OFF -DENABLE_IPO=OFF \
  -DAMRETRO_LOW_MEMORY_68000="${AMRETRO_LOW_MEMORY_68000:-OFF}" \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_FLAGS_RELEASE='-O1 -g0 -DNDEBUG' -DCMAKE_C_FLAGS_RELEASE='-O1 -g0 -DNDEBUG' \
  -DCMAKE_SHARED_LINKER_FLAGS='-Wl,-z,max-page-size=16384'
cmake --build "$BUILD" --target ares_md_libretro -j "${AMRETRO_BUILD_JOBS:-1}"
CORE=$(find "$BUILD" -name libares_md_libretro.so -type f -print -quit)
[ -n "$CORE" ] || { echo 'Core not found after build.' >&2; exit 1; }
mkdir -p "$(dirname "$OUT")"
"$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded -o "$OUT" "$CORE"
echo "Firmware-free Mega Drive core ($ANDROID_ABI): $OUT"
