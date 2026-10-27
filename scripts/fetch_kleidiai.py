#!/usr/bin/env python3
"""Fetch the pinned Arm kernels and verify the archive plus each extracted source file."""
import hashlib
import io
import json
from pathlib import Path
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
lock = json.loads((ROOT / 'upstream/kleidiai.lock.json').read_text())
cache = ROOT / '.cache/kleidiai-source.tar.gz'
cache.parent.mkdir(parents=True, exist_ok=True)
data = cache.read_bytes() if cache.exists() else b''
if hashlib.sha256(data).hexdigest() != lock['sha256']:
    with urllib.request.urlopen(lock['url'], timeout=60) as response:
        data = response.read(32 * 1024 * 1024)
    assert hashlib.sha256(data).hexdigest() == lock['sha256'], 'KleidiAI archive SHA-256 mismatch'
    cache.write_bytes(data)
destination = ROOT / '.references/kleidiai'
with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as archive:
    for member in archive.getmembers():
        assert not member.issym() and not member.islnk(), 'Unexpected archive link'
        if not member.isfile():
            continue
        relative = Path(*Path(member.name).parts[1:])
        target = destination / relative
        assert target.resolve().is_relative_to(destination.resolve()), 'Invalid archive path'
        content = archive.extractfile(member).read()
        if not target.exists() or target.read_bytes() != content:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
print(f'KleidiAI {lock["tag"]}: verified archive and sources')
