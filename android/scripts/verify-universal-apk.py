#!/usr/bin/env python3
"""Static acceptance checks for a signed universal AMretro APK.

This checks package structure, ELF architecture/segment alignment/dependencies,
APK alignment and the expected signing certificate. It does NOT replace a
runtime test on each Android ABI or test on real devices.
"""
import argparse
import re
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

EXPECTED = {
    'arm64-v8a': (2, 183),
    'armeabi-v7a': (1, 40),
    'x86': (1, 3),
    'x86_64': (2, 62),
}
CORES = {'libamretro.so', 'libmgba_libretro.so', 'libnes_libretro.so', 'libares_md_libretro.so'}
SYSTEM_LIBS = {'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so', 'libz.so'}
EXPECTED_CERT = '8d5ac447a482fa1adae3934d26f26bfa3532e9db8f464c8b8cf52890483f5fd2'


def check(condition, message):
    if not condition:
        raise ValueError(message)


def check_elf(raw, abi, filename):
    name = f'{abi}/{filename}'
    check(len(raw) >= 64 and raw[:4] == b'\x7fELF' and raw[5] == 1, f'{name}: not little-endian ELF')
    klass, machine = EXPECTED[abi]
    check(raw[4] == klass and struct.unpack_from('<H', raw, 18)[0] == machine, f'{name}: wrong ELF architecture')
    check(struct.unpack_from('<H', raw, 16)[0] == 3, f'{name}: not a shared object')
    if klass == 1:
        off = struct.unpack_from('<I', raw, 28)[0]
        entsize, count = struct.unpack_from('<HH', raw, 42)
        fmt = '<IIIIIIII'
    else:
        off = struct.unpack_from('<Q', raw, 32)[0]
        entsize, count = struct.unpack_from('<HH', raw, 54)
        fmt = '<IIQQQQQQ'
    check(entsize >= struct.calcsize(fmt) and count > 0 and off + entsize*count <= len(raw), f'{name}: invalid program headers')
    loads = 0
    for i in range(count):
        p = struct.unpack_from(fmt, raw, off + i*entsize)
        if p[0] != 1:
            continue
        loads += 1
        file_offset, vaddr, align = (p[1], p[2], p[7]) if klass == 1 else (p[2], p[3], p[7])
        check(align >= 16384 and align % 16384 == 0 and file_offset % align == vaddr % align,
              f'{name}: PT_LOAD does not meet 16-KiB alignment')
    check(loads > 0, f'{name}: missing PT_LOAD segments')


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('apk', type=Path)
    ap.add_argument('--build-tools', type=Path, required=True)
    ap.add_argument('--llvm-readelf', type=Path, required=True)
    ap.add_argument('--certificate', default=EXPECTED_CERT)
    args = ap.parse_args()
    out = subprocess.check_output([str(args.build_tools/'apksigner'), 'verify', '--verbose', '--print-certs', str(args.apk)], text=True)
    digests = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)', out)
    check(len(digests) == 1 and digests[0].lower() == args.certificate.lower(), 'APK signing certificate mismatch')
    subprocess.run([str(args.build_tools/'zipalign'), '-c', '-P', '16', '4', str(args.apk)], check=True)
    badging = subprocess.check_output([str(args.build_tools/'aapt'), 'dump', 'badging', str(args.apk)], text=True)
    check("package: name='com.amretro.mobile'" in badging, 'unexpected application ID')
    check("sdkVersion:'24'" in badging and "targetSdkVersion:'35'" in badging, 'unexpected Android SDK range')
    native_lines = [line for line in badging.splitlines() if line.startswith('native-code:')]
    check(len(native_lines) == 1 and set(re.findall(r"'([^']+)'", native_lines[0])) == set(EXPECTED), 'unexpected APK ABI declaration')
    seen = {abi:set() for abi in EXPECTED}
    with tempfile.TemporaryDirectory(prefix='amretro-apk-verify-') as td, zipfile.ZipFile(args.apk) as apk:
        for entry in apk.infolist():
            if entry.is_dir() or not entry.filename.startswith('lib/'):
                continue
            parts = entry.filename.split('/')
            check(len(parts) == 3 and parts[1] in EXPECTED and parts[2] in CORES, f'unexpected native entry: {entry.filename}')
            abi, filename = parts[1:]
            check(filename not in seen[abi] and entry.compress_type == zipfile.ZIP_STORED, f'duplicate/compressed native entry: {entry.filename}')
            seen[abi].add(filename)
            data = apk.read(entry)
            check_elf(data, abi, filename)
            path = Path(td)/f'{abi}-{filename}'
            path.write_bytes(data)
            dynamic = subprocess.check_output([str(args.llvm_readelf), '-d', str(path)], text=True)
            needed = set(re.findall(r'\(NEEDED\).*?\[([^]]+)\]', dynamic))
            check(needed <= SYSTEM_LIBS, f'{abi}/{filename}: unexpected dependencies {needed-SYSTEM_LIBS}')
            print(f'{abi:12} {filename:24} ELF OK, 16-KiB, dependencies: {", ".join(sorted(needed))}')
    for abi in EXPECTED:
        check(seen[abi] == CORES, f'{abi}: missing or duplicate native libraries: {CORES-seen[abi]}')
    print(f'SIGNER SHA-256: {digests[0].lower()}')
    print('STATIC_APK_CHECK_OK — runtime and physical-device testing still required')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print(f'STATIC_APK_CHECK_FAILED: {error}', file=sys.stderr)
        sys.exit(1)
