#!/usr/bin/env python3
"""Fetch only the pinned CPU build sources, verifying every Git blob SHA-1 against GitHub tree metadata."""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import urllib.request
import time

ROOT = Path(__file__).resolve().parents[1]
lock = json.loads((ROOT / 'upstream/llama.lock.json').read_text())
destination = ROOT / '.references/llama.cpp'

def valid(data, entry):
    return len(data) == entry['size'] and hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest() == entry['sha']

def fetch(entry):
    target = destination / entry['path']
    if target.exists() and valid(target.read_bytes(), entry):
        return
    for attempt in range(8):
        try:
            host = ['fastly.jsdelivr.net', 'gcore.jsdelivr.net', 'testingcf.jsdelivr.net'][attempt % 3]
            url = 'https://' + host + '/gh/ggml-org/llama.cpp@' + lock['commit'] + '/' + entry['path']
            with urllib.request.urlopen(url, timeout=30) as response:
                data = response.read(entry['size'] + 1)
            if not valid(data, entry):
                raise ValueError('Blob hash mismatch: ' + entry['path'])
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            return
        except Exception:
            if attempt == 7: raise RuntimeError('Failed pinned source: ' + entry['path'])
            time.sleep(attempt + 1)

if __name__ == '__main__':
    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
        for i, _ in enumerate(pool.map(fetch, lock['files']), 1):
            if i % 25 == 0: print(f'{i}/{len(lock["files"])} verified', flush=True)
    print(f'{len(lock["files"])} pinned llama.cpp source blobs verified')
