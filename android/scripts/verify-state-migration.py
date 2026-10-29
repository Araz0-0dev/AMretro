#!/usr/bin/env python3
"""Verify exact legacy/new Sega core fingerprint pairs embedded in core_host.c.

Usage: python3 android/scripts/verify-state-migration.py OLD.apk NEW.apk
This static check does not replace the runtime cross-build migration tests.
"""
import argparse
import hashlib
import re
import zipfile
from pathlib import Path

ABIS = ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
SOURCE = Path(__file__).resolve().parents[1] / 'native' / 'core_host.c'
PAIR = re.compile(r'\{UINT64_C\(0x([0-9a-f]+)\),\s*UINT64_C\(0x([0-9a-f]+)\)\},?\s*/\*\s*([\w-]+)\s*\*/')


def fnv1a(data):
    value = 0xcbf29ce484222325
    for byte in data:
        value = ((value ^ byte) * 0x100000001b3) & 0xffffffffffffffff
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('old_apk', type=Path)
    parser.add_argument('new_apk', type=Path)
    args = parser.parse_args()
    matches = PAIR.findall(SOURCE.read_text())
    pairs = {abi: (int(cur, 16), int(old, 16)) for cur, old, abi in matches}
    if len(matches) != len(ABIS) or set(pairs) != set(ABIS):
        raise ValueError('compatibility table must have exactly the four expected ABIs')
    with zipfile.ZipFile(args.old_apk) as old_apk, zipfile.ZipFile(args.new_apk) as new_apk:
        for abi in ABIS:
            path = f'lib/{abi}/libares_md_libretro.so'
            previous, current = old_apk.read(path), new_apk.read(path)
            actual = fnv1a(current), fnv1a(previous)
            if actual != pairs[abi] or previous == current:
                raise ValueError(f'{abi}: packaged core hashes do not match the exact migration pair')
            print(f'{abi}: pair verified; old SHA-256={hashlib.sha256(previous).hexdigest()} new SHA-256={hashlib.sha256(current).hexdigest()}')
    print('STATIC_STATE_MIGRATION_PAIRS_OK; runtime and device tests remain necessary')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        raise SystemExit(f'STATIC_STATE_MIGRATION_PAIRS_FAILED: {error}')
