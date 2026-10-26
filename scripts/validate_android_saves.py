#!/usr/bin/env python3
"""Inspect native benchmark suffixes with independent pypdf/Poppler. Requires pypdf and Pillow.

Run after benchmark_android.mjs. Stops the debug app for a consistent SQLite snapshot;
temporary combined PDFs are removed after inspection, source fixtures remain untouched.
"""
import json
import logging
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import tempfile

from pypdf import PdfReader
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ADB = os.environ.get('ADB', str(Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk'))) / 'platform-tools/adb'))
PACKAGE = 'org.pararead.android'
warnings = []
class Warnings(logging.Handler):
    def emit(self, record):
        warnings.append(record.getMessage())
logging.getLogger('pypdf').addHandler(Warnings())

def adb(*args, required=True):
    result = subprocess.run([ADB, *args], capture_output=True, check=required)
    return result.stdout if result.returncode == 0 else None

if __name__ == '__main__':
    adb('shell', 'am', 'force-stop', PACKAGE)
    parent = ROOT / '.cache/tmp/pdfs'
    parent.mkdir(parents=True, exist_ok=True)
    results = []
    reports = json.loads((ROOT / 'docs/reports/android-large-pdf.json').read_text())
    with tempfile.TemporaryDirectory(dir=parent) as folder:
        temp = Path(folder)
        database = temp / 'reader.db'
        database.write_bytes(adb('exec-out', 'run-as', PACKAGE, 'cat', 'databases/pararead.db'))
        wal = adb('exec-out', 'run-as', PACKAGE, 'cat', 'databases/pararead.db-wal', required=False)
        if wal:
            Path(str(database) + '-wal').write_bytes(wal)
        connection = sqlite3.connect(database)
        connection.row_factory = sqlite3.Row
        events = [dict(row) for row in connection.execute('SELECT documentId,name,value,timestamp FROM PerformanceRecord ORDER BY id')]
        (ROOT / 'docs/reports/android-metrics.json').write_text(json.dumps(events, indent=2))
        for run in reports:
            size = run['requestedMiB']
            name = f'text-{size}MiB.pdf'
            doc = connection.execute('SELECT * FROM DocumentRecord WHERE name=?', (name,)).fetchone()
            draft = connection.execute('SELECT * FROM DraftRecord WHERE documentId=?', (doc['id'],)).fetchone()
            assert draft and draft['baseRevision'] == doc['revision']
            suffix = adb('exec-out', 'run-as', PACKAGE, 'cat', draft['path'])
            assert len(suffix) == draft['size'] and 0 < len(suffix) <= 16 * 1024 * 1024
            combined = temp / name
            with (ROOT / '.cache/large-pdfs' / name).open('rb') as source, combined.open('wb') as output:
                shutil.copyfileobj(source, output, 262144)
                output.write(suffix)
            warnings.clear()
            reader = PdfReader(combined, strict=True)
            assert len(reader.pages) == run['pages']
            page = reader.pages[run['jumpPage'] - 1]
            annotations = [value.get_object() for value in page.get('/Annots', [])]
            expected = f'ParaRead large save {size} MiB'
            annotation = next(value for value in annotations if value.get('/Contents') == expected)
            assert '/N' in annotation['/AP']
            reader.close()
            info = subprocess.run(['pdfinfo', '-f', str(run['jumpPage']), '-l', str(run['jumpPage']), str(combined)], capture_output=True, text=True, check=True)
            prefix = temp / f'render-{size}'
            subprocess.run(['pdftoppm', '-f', str(run['jumpPage']), '-l', str(run['jumpPage']), '-scale-to', '3200', '-singlefile', '-png', str(combined), str(prefix)], capture_output=True, check=True)
            with Image.open(prefix.with_suffix('.png')) as image:
                scale = image.width / 2100
                box = tuple(round(value * scale) for value in (1785, 1895, 2090, 1960))
                image.crop(box).save(ROOT / f'docs/reports/large-save-{size}-annotation.png')
            result = {'requestedMiB': size, 'status': 'passed', 'pages': run['pages'], 'page': run['jumpPage'], 'suffixBytes': len(suffix),
                      'annotationContents': expected, 'appearancePresent': True, 'pypdfStrict': True, 'pypdfWarnings': list(warnings), 'popplerRendered': True, 'popplerWarnings': info.stderr.strip()}
            results.append(result)
            print(json.dumps(result), flush=True)
        connection.close()
    (ROOT / 'docs/reports/native-incremental-validation.json').write_text(json.dumps(results, indent=2) + '\n')
