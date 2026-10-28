#!/usr/bin/env bash
# Build a Debian/Ubuntu .deb from an already-built x86_64 Linux executable.
# For broad compatibility the supplied executable must be built on Ubuntu 22.04.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
root="$(cd "$here/.." && pwd)"
bin="${1:?provide the absolute path to the Ubuntu 22.04-built executable}"
out="${2:-$root/releases/AMretro_0.3.0_amd64.deb}"
work="$(mktemp -d)"
chmod 0755 "$work"
trap 'rm -rf "$work"' EXIT
install -D -m 0755 "$bin" "$work/usr/bin/amretro"
strip "$work/usr/bin/amretro"
install -D -m 0644 "$here/src-tauri/icons/icon.png" "$work/usr/share/icons/hicolor/512x512/apps/amretro.png"
install -D -m 0644 "$here/ui/assets/fonts/OFL-Vazirmatn.txt" "$work/usr/share/doc/amretro/OFL-Vazirmatn.txt"
mkdir -p "$work/usr/share/applications" "$work/DEBIAN"
cat > "$work/usr/share/applications/amretro.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=AMretro
GenericName=Retro Game Launcher
GenericName[fa]=لانچر بازی‌های کلاسیک
Comment=Discover and play your classic game library
Comment[fa]=کتابخانه بازی‌های کلاسیک خود را کشف و اجرا کنید
Exec=amretro
Icon=amretro
Terminal=false
Categories=Game;
Keywords=retro;games;launcher;emulator;
StartupNotify=true
DESKTOP
# Calculated with dpkg-shlibdeps on Ubuntu 22.04 for the bundled executable.
depends='libc6 (>= 2.34), libcairo2 (>= 1.10.0), libdbus-1-3 (>= 1.9.14), libgcc-s1 (>= 4.2), libgdk-pixbuf-2.0-0 (>= 2.36.9), libglib2.0-0 (>= 2.65.1), libgtk-3-0 (>= 3.21.5), libjavascriptcoregtk-4.1-0, libsoup-3.0-0 (>= 3.0.3), libssl3 (>= 3.0.0~~alpha1), libudev1 (>= 183), libwebkit2gtk-4.1-0 (>= 2.41.90), zlib1g (>= 1:1.2.3.4)'
installed="$(du -sk "$work/usr" | cut -f1)"
cat > "$work/DEBIAN/control" <<CONTROL
Package: amretro
Version: 0.3.0
Section: games
Priority: optional
Architecture: amd64
Maintainer: AMretro
Installed-Size: $installed
Depends: $depends
Description: AMretro classic game launcher
 A Persian desktop launcher for a self-managed retro game library.
 RetroArch, game cores, game files and BIOS files are installed separately.
CONTROL
mkdir -p "$(dirname "$out")"
dpkg-deb --build --root-owner-group "$work" "$out"
printf 'Created %s\n' "$out"
