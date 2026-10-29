#!/usr/bin/env bash
# Bundle an already-built x64 Tauri executable, its matching WebView2 loader,
# and the Windows runtime installer inputs into one per-user setup executable.
# Usage: ./build-windows-installer.sh /absolute/path/to/AMretro.exe /absolute/path/to/WebView2Loader.dll [output.exe]
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
root="$(cd "$here/.." && pwd)"
exe="${1:?provide the absolute path to the x64 AMretro executable}"
loader="${2:?provide the absolute path to WebView2Loader.dll}"
out="${3:-$root/releases/AMretro-Setup-0.3.1-x64.exe}"
bootstrap="$here/installer/vendor/MicrosoftEdgeWebview2Setup.exe"
extractor="$here/installer/vendor/7za-26.04-x64.exe"
seven_license="$here/installer/vendor/7zip-26.04-License.txt"
launcher="$here/installer/build/retroarch-launcher.exe"
expected="13f6d9cdc8c3e7186e4b180db7f48e199ee97d631480e8391fb3106f338fb21a"
expected_7za="15d4c788c148e3677e2fc1c4a01f191fb6669eba4f6acf8a465f0f1a6f1e1260"
echo "$expected  $bootstrap" | sha256sum --check --status || { echo 'WebView2 bootstrapper checksum mismatch' >&2; exit 1; }
echo "$expected_7za  $extractor" | sha256sum --check --status || { echo 'Extractor checksum mismatch' >&2; exit 1; }
test -f "$exe" && test -f "$loader"
# Build the small RetroArch launcher from source with the MinGW cross compiler.
mkdir -p "$here/installer/build"
x86_64-w64-mingw32-gcc -municode -mwindows -Os -s -Wall -Wextra -Werror \
  -o "$launcher" "$here/installer/retroarch-wrapper.c"
mkdir -p "$(dirname "$out")"
cd "$here/installer"
"${MAKENSIS:-makensis}" -V2 \
  "-DAPP_BINARY=$exe" \
  "-DWEBVIEW_LOADER=$loader" \
  "-DWEBVIEW_BOOTSTRAPPER=$bootstrap" \
  "-DFONT_LICENSE=$here/ui/assets/fonts/OFL-Vazirmatn.txt" \
  "-DAPP_ICON=$here/src-tauri/icons/icon.ico" \
  "-DSIDEBAR=$here/installer/sidebar.bmp" \
  "-DEXTRACTOR=$extractor" \
  "-DRETROARCH_WRAPPER=$launcher" \
  "-DRUNTIME_SCRIPT=$here/installer/install-windows-runtime.ps1" \
  "-DCORE_LIST=$here/installer/windows-core-list.txt" \
  "-DASSET_MANIFEST=$here/installer/windows-support-assets.json" \
  "-DTHIRD_PARTY_NOTICES=$here/installer/THIRD-PARTY-WINDOWS.txt" \
  "-DSEVENZIP_LICENSE=$seven_license" \
  "-DOUTPUT=$out" windows-amretro.nsi
printf 'Created %s\n' "$out"
