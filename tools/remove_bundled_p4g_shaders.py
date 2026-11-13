"""Remove only the old P4G shader pack from this checkout, not from a phone.

Run from any working directory: python path/to/repo/tools/remove_bundled_p4g_shaders.py
"""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
manifest = root / 'games/p4g/AndroidManifest.xml'
if not manifest.is_file() or 'io.github.p4grecomp' not in manifest.read_text(encoding='utf-8'):
    raise SystemExit('Not a P4G checkout; nothing removed.')
target = root / 'games/p4g/assets/spirv'
if not target.exists():
    print('Old bundled pack is already absent.')
    raise SystemExit(0)
if target.is_symlink() or not target.resolve().is_relative_to(root):
    raise SystemExit('Unexpected shader directory; nothing removed.')
files = list(target.iterdir())
for f in files:
    if f.is_symlink() or not f.is_file() or not re.fullmatch(r'(?:[0-9a-f]{16}\.spv|manifest\.tsv|results\.tsv)', f.name):
        raise SystemExit('Unexpected file in old pack; nothing removed: ' + f.name)
for f in files:
    if f.resolve().parent != target.resolve():
        raise SystemExit('Unexpected path; stopped.')
    f.unlink()
target.rmdir()
print(f'Removed {len(files)} old bundled shader files from {root}. Saves and phone data were not accessed.')
