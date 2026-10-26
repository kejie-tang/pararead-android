#!/usr/bin/env python3
"""Run the unchanged upstream check outside this project's ignored .references.

Requires a previously installed .references/pararead/node_modules.
Copies only hash-verified tracked files; neither source nor check config changes.
"""
import pathlib
import shutil
import subprocess
import tempfile

from fetch_upstream import LOCK, ROOT, verify

source = ROOT / ".references/pararead"
verify(source)
dependencies = source / "node_modules"
if not dependencies.is_dir():
    raise SystemExit("Install upstream dependencies first (see README.md).")
with tempfile.TemporaryDirectory(prefix="pararead-upstream-check-") as directory:
    target = pathlib.Path(directory)
    for entry in LOCK["files"]:
        destination = target / entry["path"]
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source / entry["path"], destination)
    (target / "node_modules").symlink_to(dependencies, target_is_directory=True)
    result = subprocess.run(["pnpm", "run", "check"], cwd=target)
    raise SystemExit(result.returncode)
