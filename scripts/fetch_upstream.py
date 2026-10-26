#!/usr/bin/env python3
"""Fetch a pinned ParaRead snapshot; verify every file against its Git blob hash.

No git clone, package installation, lifecycle script, or upstream code execution.
The archive is extracted only to manifest-listed regular-file paths.
"""
import argparse
import hashlib
import json
import pathlib
import shutil
import sys
import tarfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT / "upstream/pararead.lock.json").read_text())


def blob_hash(data):
    return hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()


def verify(target):
    problems = []
    for entry in LOCK["files"]:
        path = target / entry["path"]
        if not path.is_file() or blob_hash(path.read_bytes()) != entry["gitBlobSha1"]:
            problems.append(entry["path"])
    if problems:
        raise RuntimeError("Missing or modified upstream files:\n" + "\n".join(problems))
    print(f'Verified {len(LOCK["files"])} files at {LOCK["commit"]}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-only", action="store_true")
    parser.add_argument("--archive", type=pathlib.Path, help="Use an already downloaded .tar.gz")
    args = parser.parse_args()
    target = ROOT / ".references/pararead"
    if args.verify_only:
        verify(target)
        return
    archive = args.archive
    if archive is None:
        archive = ROOT / ".references/pararead-pinned.tar.gz"
        archive.parent.mkdir(parents=True, exist_ok=True)
        url = f'https://codeload.github.com/{LOCK["repository"]}/tar.gz/{LOCK["commit"]}'
        temp = archive.with_suffix(".download")
        try:
            with urllib.request.urlopen(url, timeout=600) as response, temp.open("wb") as output:
                shutil.copyfileobj(response, output, length=256 * 1024)
            temp.replace(archive)
        finally:
            temp.unlink(missing_ok=True)
    entries = {entry["path"]: entry for entry in LOCK["files"]}
    with tarfile.open(archive, "r:gz") as source:
        for member in source:
            relative = "/".join(member.name.split("/")[1:])
            if relative not in entries or not member.isfile():
                continue
            data = source.extractfile(member).read()
            if blob_hash(data) != entries[relative]["gitBlobSha1"]:
                raise RuntimeError(f"Git blob hash mismatch: {relative}")
            path = target / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    verify(target)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
