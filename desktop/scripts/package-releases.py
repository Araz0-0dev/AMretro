#!/usr/bin/env python3
"""Bundle reproducible source and checksums for the AMretro installable releases."""
from pathlib import Path
import hashlib
import zipfile

root = Path(__file__).resolve().parents[2]
release = root / 'releases'
release.mkdir(exist_ok=True)
exclude = {'node_modules', 'target', 'build', 'dist', '.wrangler', '.git', '.cache', '__pycache__', 'gen', '.venv'}
out = release / 'AMretro-Source.zip'
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=8) as archive:
    for tree in (root / 'desktop', root / 'worker'):
        for p in sorted(tree.rglob('*')):
            if not p.is_file() or any(part in exclude for part in p.relative_to(root).parts):
                continue
            if p.name in {'.dev.vars', '.env', 'worker-secrets.json'} or p.name.endswith(('.sqlite', '.sqlite3', '.db')):
                continue
            archive.write(p, 'AMretro-Source/' + str(p.relative_to(root)))
    for name in ('README.md',):
        archive.write(root / name, 'AMretro-Source/' + name)
print('Source:', out.name)

items = [release / name for name in ('AMretro-Setup-0.3.1-x64.exe', 'AMretro_0.3.1_amd64.deb', 'AMretro-Source.zip')]
with (release / 'AMretro-SHA256SUMS.txt').open('w', encoding='utf-8') as f:
    for p in items:
        if not p.exists():
            raise SystemExit(f'Missing release: {p}')
        h = hashlib.sha256()
        with p.open('rb') as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                h.update(chunk)
        f.write(f'{h.hexdigest()}  {p.name}\n')
print('Checksums: AMretro-SHA256SUMS.txt')
