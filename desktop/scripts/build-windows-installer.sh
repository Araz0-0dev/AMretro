#!/usr/bin/env bash
# Bundle an already-built x64 Tauri executable and its matching WebView2 loader.
# Usage: ./build-windows-installer.sh /absolute/path/to/AMretro.exe /absolute/path/to/WebView2Loader.dll [output.exe]
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
root="$(cd "$here/.." && pwd)"
exe="${1:?provide the absolute path to the x64 AMretro executable}"
loader="${2:?provide the absolute path to WebView2Loader.dll}"
out="${3:-$root/releases/AMretro-Setup-0.3.0-x64.exe}"
bootstrap="$here/installer/vendor/MicrosoftEdgeWebview2Setup.exe"
expected="13f6d9cdc8c3e7186e4b180db7f48e199ee97d631480e8391fb3106f338fb21a"
echo "$expected  $bootstrap" | sha256sum --check --status || { echo 'WebView2 bootstrapper checksum mismatch' >&2; exit 1; }
test -f "$exe" && test -f "$loader"
mkdir -p "$(dirname "$out")"
cd "$here/installer"
makensis -V2 \
  "-DAPP_BINARY=$exe" \
  "-DWEBVIEW_LOADER=$loader" \
  "-DWEBVIEW_BOOTSTRAPPER=$bootstrap" \
  "-DFONT_LICENSE=$here/ui/assets/fonts/OFL-Vazirmatn.txt" \
  "-DAPP_ICON=$here/src-tauri/icons/icon.ico" \
  "-DSIDEBAR=$here/installer/sidebar.bmp" \
  "-DOUTPUT=$out" windows-amretro.nsi
printf 'Created %s\n' "$out"
