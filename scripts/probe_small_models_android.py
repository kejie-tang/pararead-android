#!/usr/bin/env python3
"""Run opt-in native model research against an installed Android debug/test APK pair.

Manifest entries require file, bytes and sha256. Put weights in --models-dir.
This replaces only two private research files, removes them after the run, and
retains JSON output even when a model fails format checks. A failed model exits 1.
"""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("manifest", type=Path)
parser.add_argument("--adb", default="adb")
parser.add_argument("--serial")
parser.add_argument("--models-dir", type=Path, default=Path("artifacts/models"))
parser.add_argument("--output-dir", type=Path, required=True)
args = parser.parse_args()
entries = json.loads(args.manifest.read_text())
adb = [args.adb] + (["-s", args.serial] if args.serial else [])
args.output_dir.mkdir(parents=True, exist_ok=True)


def run(command, **kwargs):
    return subprocess.run(adb + command, check=True, **kwargs)


failed = False
try:
    for index, source in enumerate(entries):
        model = args.models_dir / source["file"]
        if Path(source["file"]).name != source["file"]:
            raise ValueError("Manifest file must be a basename")
        # Instrumentation verifies the complete SHA-256 inside the device.
        if model.stat().st_size != source["bytes"]:
            raise ValueError("MODEL_SIZE_MISMATCH")
        with tempfile.TemporaryDirectory(prefix="pararead-probe-") as temp:
            spec = Path(temp) / "spec.json"
            spec.write_text(json.dumps(source) + "\n")
            run(["push", str(model), "/data/local/tmp/pararead-mid-model.gguf"])
            run(["push", str(spec), "/data/local/tmp/pararead-mid-spec.json"])
        for name in ("model.gguf", "spec.json"):
            run(["shell", "run-as", "org.pararead.android", "cp",
                 "/data/local/tmp/pararead-mid-" + name, "files/research-" + name])
        run(["shell", "run-as", "org.pararead.android", "rm", "-f", "files/model-research.json"])
        result = run(["shell", "am", "instrument", "-w", "-r", "-e", "class",
            "org.pararead.android.ResearchNativeModelTest", "-e", "modelPath",
            "/data/user/0/org.pararead.android/files/research-model.gguf", "-e", "modelSpecPath",
            "/data/user/0/org.pararead.android/files/research-spec.json", "-e", "includeHeldout", "true",
            "-e", "benchmark", "true", "org.pararead.android.test/androidx.test.runner.AndroidJUnitRunner"],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=600)
        stem = f'{index:02d}-{model.stem}'
        (args.output_dir / (stem + ".txt")).write_text(result.stdout)
        report = run(["exec-out", "run-as", "org.pararead.android", "cat", "files/model-research.json"],
                     stdout=subprocess.PIPE).stdout
        (args.output_dir / (stem + ".json")).write_bytes(report)
        data = json.loads(report)
        failed |= data.get("status") != "passed"
        print(source["file"], data.get("status"), flush=True)
finally:
    run(["shell", "run-as", "org.pararead.android", "rm", "-f", "files/research-model.gguf", "files/research-spec.json"])
    run(["shell", "rm", "-f", "/data/local/tmp/pararead-mid-model.gguf", "/data/local/tmp/pararead-mid-spec.json"])
raise SystemExit(1 if failed else 0)
