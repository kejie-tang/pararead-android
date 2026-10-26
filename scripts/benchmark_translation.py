#!/usr/bin/env python3
"""Repeat the real CPU JNI check and summarize translation latency (no model download)."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import statistics
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SHA256 = '3d0b790534fe4b79525fc3692950408dca41171676ed7e21db57af5c65ef6ab6'


def command(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runs', type=int, default=3, help='1-20 fresh JVM/provider runs; default 3')
    parser.add_argument('--threads', type=int, default=6, help='Requested CPU threads, 0 for automatic')
    parser.add_argument('--output', type=Path, default=Path('artifacts/translation-speed/summary.json'))
    args = parser.parse_args()
    if not 1 <= args.runs <= 20 or args.threads < 0:
        parser.error('--runs must be 1-20 and --threads must be nonnegative')
    value = os.environ.get('PARAREAD_GGUF')
    if not value:
        parser.error('Set PARAREAD_GGUF to the local default Qwen3-1.7B Q4_K_M GGUF; weights are not downloaded')
    model = Path(value).expanduser().resolve()
    digest = hashlib.sha256()
    with model.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    if digest.hexdigest() != DEFAULT_SHA256:
        parser.error('This fixed benchmark profile requires the documented default GGUF SHA-256')
    stamp = datetime.now(timezone.utc)
    directory = ROOT / 'artifacts/translation-speed' / stamp.strftime('%Y%m%dT%H%M%S%fZ')
    directory.mkdir(parents=True)
    output = args.output if args.output.is_absolute() else ROOT / args.output
    metadata = {
        'schemaVersion': 1, 'measuredAt': stamp.isoformat(), 'sourceCommit': command('git', 'rev-parse', 'HEAD'),
        'scope': 'Real host CPU JNI integration; not an Android device or WebView latency test',
        'host': {'os': platform.system(), 'osVersion': platform.release(), 'architecture': platform.machine(), 'logicalCpus': os.cpu_count()},
        'requestedThreads': args.threads, 'runCount': args.runs, 'modelSha256': DEFAULT_SHA256,
        'modelBytes': model.stat().st_size, 'model': 'Qwen3 1.7B Q4_K_M, thinking disabled',
        'notes': [
            'Each run starts a fresh JVM/provider. The first case includes model loading; OS file caches are not cleared.',
            'Warm four-sentence batches use a loaded model with no translation-cache lookup.',
            'First stream delta can contain JSON syntax; it is not the first translated word or a token counter.',
            'Completed time includes prompt preparation, generation and validation; excludes Gradle/build, GGUF hash checks and PDF rendering.',
            'Three default repetitions describe this sample only; no p95 or physical-tablet throughput is inferred.',
        ],
        'runs': [], 'summary': [], 'status': 'running',
    }
    if platform.system() == 'Darwin':
        metadata['host'].update(cpu=command('sysctl', '-n', 'machdep.cpu.brand_string'),
            memoryBytes=int(command('sysctl', '-n', 'hw.memsize')), osVersion=command('sw_vers', '-productVersion'))
    output.parent.mkdir(parents=True, exist_ok=True)

    def save():
        output.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')

    save()
    try:
        for i in range(args.runs):
            report = directory / f'run-{i + 1}.json'
            log = directory / f'run-{i + 1}.log'
            env = os.environ.copy()
            env.update(PARAREAD_GGUF=str(model), PARAREAD_CPU_THREADS=str(args.threads), PARAREAD_CHECK_REPORT=str(report))
            print(f'Real JNI run {i + 1}/{args.runs}; log: {log.relative_to(ROOT)}', flush=True)
            with log.open('w') as stream:
                result = subprocess.run(['scripts/check_local_llama.sh', 'jni'], cwd=ROOT, env=env,
                    stdout=stream, stderr=subprocess.STDOUT, timeout=900)
            if report.exists():
                data = json.loads(report.read_text())
                metadata['runs'].append(data)
                save()
            if result.returncode != 0:
                raise RuntimeError(f'JNI check failed; inspect {log.relative_to(ROOT)}')
            data = metadata['runs'][-1]
            if data['status'] != 'passed' or data['modelSha256'] != DEFAULT_SHA256:
                raise RuntimeError('Failed check or changed model; raw result retained')
            print('  all four real-model integration cases passed', flush=True)
        configs = {(run['engine'], run['effectiveThreads'], run['configuration']) for run in metadata['runs']}
        if len(configs) != 1:
            raise RuntimeError('Engine/thread configuration changed between runs; refusing to aggregate')
        for name, sentences in [('cold-single', 1), ('warm-batch-four-with-protected-spans', 4)]:
            cases = [next(case for case in run['cases'] if case['name'] == name) for run in metadata['runs']]
            if not all(case['status'] == 'passed' and len(case['translations']) == sentences for case in cases):
                raise RuntimeError('Workload incomplete; refusing to report a successful benchmark')
            entry = {'name': name, 'sentences': sentences, 'sampleCount': len(cases)}
            for field in ['firstDeltaMs', 'totalMs']:
                values = [case[field] for case in cases]
                if not all(value > 0 for value in values):
                    raise RuntimeError('Missing or invalid monotonic timing')
                entry[field] = {'samples': values, 'median': statistics.median(values), 'min': min(values), 'max': max(values)}
            metadata['summary'].append(entry)
        metadata['status'] = 'passed'
        save()
        print(f'Saved {output.relative_to(ROOT) if output.is_relative_to(ROOT) else output}', flush=True)
        for entry in metadata['summary']:
            print(f"{entry['name']}: first delta median {entry['firstDeltaMs']['median']/1000:.2f}s; complete median {entry['totalMs']['median']/1000:.2f}s (n={entry['sampleCount']})")
    except Exception as error:
        metadata.update(status='failed', error=str(error))
        save()
        raise


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        sys.exit(str(error))
