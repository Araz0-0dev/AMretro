#!/usr/bin/env bash
# Rebuild mGBA's libretro core from the corresponding MPL-2.0 source archive.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
source "$ROOT/android/scripts/abi-env.sh"
command -v cmake >/dev/null && command -v ninja >/dev/null || { echo 'CMake and Ninja are required.' >&2; exit 1; }
OUT=${1:-"$ROOT/android/jniLibs/$ANDROID_ABI/libmgba_libretro.so"}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
if [ -n "${AMRETRO_MGBA_SRC:-}" ]; then
  SRC="$AMRETRO_MGBA_SRC"
else
  tar -xzf "$ROOT/android/third_party/mgba-source-9a36d6576.tar.gz" -C "$WORK"
  SRC="$WORK/libmgba_libretro-v1.0"
fi
cmake -S "$SRC" -B "$WORK/build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$ANDROID_ABI" -DANDROID_PLATFORM=android-24 \
  -DBUILD_LIBRETRO=ON -DSKIP_LIBRARY=ON -DDISABLE_DEPS=ON -DDISABLE_FRONTENDS=ON \
  -DBUILD_GL=OFF -DBUILD_GLES2=OFF -DBUILD_GLES3=OFF -DBUILD_LTO=OFF \
  -DENABLE_DEBUGGERS=OFF -DENABLE_GDB_STUB=OFF -DENABLE_SCRIPTING=OFF \
  -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_FLAGS_RELEASE='-O2 -DNDEBUG' \
  -DCMAKE_SHARED_LINKER_FLAGS='-Wl,-z,max-page-size=16384'
cmake --build "$WORK/build" --target mgba_libretro -j "${AMRETRO_BUILD_JOBS:-1}"
CORE=$(find "$WORK/build" -name mgba_libretro.so -type f -print -quit)
[ -n "$CORE" ] || { echo 'mGBA core not found after build.' >&2; exit 1; }
mkdir -p "$(dirname "$OUT")"
"$ANDROID_LLVM/llvm-strip" --strip-unneeded -o "$OUT" "$CORE"
echo "mGBA core ($ANDROID_ABI): $OUT"
