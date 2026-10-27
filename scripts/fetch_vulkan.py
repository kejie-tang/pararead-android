#!/usr/bin/env python3
"""Fetch pinned Vulkan/SPIR-V build headers and verify archive checksums."""
import hashlib
import io
import json
from pathlib import Path
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]

def main():
    for entry in json.loads((ROOT / 'upstream/vulkan.lock.json').read_text())['headers']:
        target = ROOT / '.references' / entry['directory']
        cache = ROOT / '.cache/vulkan' / (entry['directory'] + '.tar.gz')
        data = cache.read_bytes() if cache.is_file() else b''
        if hashlib.sha256(data).hexdigest() != entry['sha256']:
            url = f"https://codeload.github.com/{entry['repository']}/tar.gz/{entry['commit']}"
            data = urllib.request.urlopen(url, timeout=60).read()
        if hashlib.sha256(data).hexdigest() != entry['sha256']:
            raise ValueError('Header archive checksum mismatch: ' + entry['repository'])
        cache.parent.mkdir(parents=True, exist_ok=True)
        cache.write_bytes(data)
        with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as archive:
            for member in archive.getmembers():
                relative = Path(*Path(member.name).parts[1:])
                if '..' in relative.parts or relative.is_absolute() or not member.isfile():
                    continue
                output = target / relative
                output.parent.mkdir(parents=True, exist_ok=True)
                content = archive.extractfile(member).read()
                if not output.is_file() or output.read_bytes() != content:
                    output.write_bytes(content)
        print('Verified ' + entry['repository'] + '@' + entry['commit'])

if __name__ == '__main__':
    main()
