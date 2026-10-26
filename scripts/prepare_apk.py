#!/usr/bin/env python3
"""Validate distributable assets and prepare APK, notices and SHA-256 files."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
from zipfile import ZipFile, ZIP_DEFLATED

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--variant', choices=['debug', 'release'], default='debug')
parser.add_argument('--dependencies', type=Path, help='Actual variant runtime dependency report')
args = parser.parse_args()
version = re.search(r'versionName = "([A-Za-z0-9.+-]+)"', (ROOT / 'app/build.gradle.kts').read_text()).group(1)
source = ROOT / f'app/build/outputs/apk/{args.variant}/app-{args.variant}.apk'
out = ROOT / 'artifacts/distribution'
out.mkdir(parents=True, exist_ok=True)
prefix = f'ParaRead-{version}-android-arm64-{args.variant}'
apk = out / f'{prefix}.apk'
notices = out / f'{prefix}-notices.zip'
with ZipFile(source) as z:
    names = z.namelist()
    pdfs = [n for n in names if n.lower().endswith('.pdf')]
    assert pdfs == ['assets/reader/sample.pdf'], f'Unexpected PDFs: {pdfs}'
    assert not any(n.lower().endswith('.gguf') for n in names), 'Model must not be bundled'
    assert z.read(pdfs[0]) == (ROOT / 'samples/pararead-reading-guide.pdf').read_bytes()
    for name in ['LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES.md']:
        assert z.read('assets/reader/licenses/project/' + name) == (ROOT / name).read_bytes(), name
    for path in (ROOT / 'third_party').rglob('*'):
        if path.is_file():
            relative = path.relative_to(ROOT / 'third_party').as_posix()
            assert z.read('assets/reader/licenses/' + relative) == path.read_bytes(), relative
    assert z.read('assets/reader/licenses/sample/NOTICE') == (ROOT / 'samples/NOTICE').read_bytes()
    sources = json.loads((ROOT / 'third_party/pdfjs/SOURCES.json').read_text())
    for entry in sources['files']:
        original = 'assets/reader/pdfjs/' + entry['upstreamPath'].removeprefix('assets/pdf.js/')
        assert hashlib.sha256(z.read(original)).hexdigest() == entry['sha256'], original
    natives = [n for n in names if n.startswith('lib/') and n.endswith('.so')]
    assert 'lib/arm64-v8a/libpararead-llama.so' in natives, 'Missing native inference engine'
    assert all(n.startswith('lib/arm64-v8a/') for n in natives), natives
    for name in natives:
        elf = z.read(name)
        assert elf[:6] == b'\x7fELF\x02\x01' and struct.unpack_from('<H', elf, 18)[0] == 183, name
        phoff = struct.unpack_from('<Q', elf, 32)[0]
        size, count = struct.unpack_from('<HH', elf, 54)
        for i in range(count):
            offset = phoff + i * size
            if struct.unpack_from('<I', elf, offset)[0] == 1:
                assert struct.unpack_from('<Q', elf, offset + 48)[0] >= 16384, f'ELF 16KiB alignment: {name}'
shutil.copyfile(source, apk)
with ZipFile(notices, 'w', ZIP_DEFLATED) as z:
    for name in ['LICENSE', 'NOTICE', 'THIRD_PARTY_NOTICES.md']:
        z.write(ROOT / name, name)
    z.write(ROOT / 'samples/NOTICE', 'samples/NOTICE')
    for path in sorted((ROOT / 'third_party').rglob('*')):
        if path.is_file(): z.write(path, path.relative_to(ROOT).as_posix())
    if args.dependencies:
        z.write(args.dependencies, f'{args.variant}-runtime-dependencies.txt')
checksums = out / 'SHA256SUMS.txt'
checksums.write_text(''.join(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n' for p in [apk, notices]))
print(f'Validated sole original sample, no GGUF, all notices, ARM64 engine and ELF 16KiB alignment.\n{apk}\n{notices}\n{checksums}')
