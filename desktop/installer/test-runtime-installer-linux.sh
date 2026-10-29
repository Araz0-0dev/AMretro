#!/usr/bin/env bash
# Linux harness for install-windows-runtime.ps1 (PowerShell 7 + p7zip).
# It runs the real installer script in -TestMode against a throw-away APPDATA and
# install folder, then checks the promised files and user-data protections.
# It does NOT prove Windows behaviour: run the GitHub Windows workflow for that.
# Usage: CACHE=/dir/with/official/archives PWSH=/path/pwsh ./test-runtime-installer-linux.sh
# CACHE must contain the four pinned archives under their installer names (see script).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
pwsh_bin="${PWSH:-pwsh}"
cache="${CACHE:?set CACHE to the directory with pinned archives}"
work="$(mktemp -d /var/tmp/amretro-runtime-test.XXXXXX)"
trap 'rm -rf "$work"' EXIT
export APPDATA="$work/appdata" TMPDIR="$work/tmp"
mkdir -p "$APPDATA" "$TMPDIR"
for f in RetroArch-1.22.2-win64.7z RetroArch-1.22.2-win64-cores.7z ppsspp-src-c1581e6.zip dolphin-src-227c4e0.zip; do
  test -f "$cache/$f" || { echo "missing $cache/$f" >&2; exit 1; }
done
# Extractor: on Linux the Windows 7za.exe cannot run, so use p7zip with the same CLI.
printf '#!/bin/sh\nexec 7z "$@"\n' > "$work/7za"; chmod +x "$work/7za"
x86_64-w64-mingw32-gcc -municode -mwindows -Os -s -Wall -Wextra -Werror \
  -o "$work/retroarch-launcher.exe" "$here/retroarch-wrapper.c"
run() {
  "$pwsh_bin" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$here/install-windows-runtime.ps1" \
    -InstallDir "$work/install" -Extractor "$work/7za" -CoreList "$here/windows-core-list.txt" \
    -AssetManifest "$here/windows-support-assets.json" -Launcher "$work/retroarch-launcher.exe" \
    -CacheDir "$cache" -TestMode
}
# 1) Fresh install.
run | tail -1 | grep -q 'AMRETRO_DEPENDENCIES_READY cores=44'
test "$(ls "$APPDATA/RetroArch/cores" | wc -l)" -eq 44
test -f "$work/install/AMretroRuntime/retroarch.exe"
cmp -s "$work/install/retroarch.exe" "$work/retroarch-launcher.exe"
test -f "$APPDATA/RetroArch/system/PPSSPP/LICENSE.TXT"
test -d "$APPDATA/RetroArch/system/dolphin-emu/Sys"
echo "fresh install: ok"
# 2) User data is preserved and a corrupt core is replaced.
mkdir -p "$APPDATA/RetroArch/system" && echo USERBIOS > "$APPDATA/RetroArch/system/scph5501.bin"
echo garbage > "$APPDATA/RetroArch/cores/gambatte_libretro.dll"
mkdir -p "$APPDATA/com.retrohub.launcher"
python3 - "$APPDATA/com.retrohub.launcher/retro-hub.sqlite3" <<'PY'
import sqlite3,sys
c=sqlite3.connect(sys.argv[1]); c.execute('CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT)')
c.execute("INSERT OR REPLACE INTO settings VALUES('retroarch_path','/nonexistent/Old/retroarch.exe')")
c.commit(); c.close()
PY
run | tail -1 | grep -q 'AMRETRO_DEPENDENCIES_READY cores=44'
grep -q USERBIOS "$APPDATA/RetroArch/system/scph5501.bin"
test "$(head -c2 "$APPDATA/RetroArch/cores/gambatte_libretro.dll")" = "MZ"
ls "$APPDATA/com.retrohub.launcher"/*.before-runtime-*.backup >/dev/null
echo "repair and user-data preservation: ok"
