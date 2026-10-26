#!/usr/bin/env python3
"""Generate valid, uncompressed text PDFs for source-byte/cache stress (not representative real books). No padding streams."""
import argparse
import json
from pathlib import Path

def generate(path, mib):
    target = mib * 1024 * 1024
    text = 'Measurements compare sentence alignment, cache persistence, random access and cancellation. ' * 4
    # Large page is deliberate: all 80 lines fit in the MediaBox, with distinct page/line labels.
    def stream(page):
        lines = [f'BT /F1 8 Tf 30 2500 Td 0 -28 TD']
        for line in range(80):
            value = f'Page {page:05d}, line {line:02d}. ' + text
            lines.append('(' + value + ') Tj T*')
        return ('\n'.join(lines) + '\nET\n').encode('ascii')
    count = max(1, target // (len(stream(1)) + 250))
    offsets = [0]
    with path.open('wb') as out:
        out.write(b'%PDF-1.7\n%\xe2\xe3\xcf\xd3\n')
        def obj(number, data):
            assert number == len(offsets)
            offsets.append(out.tell()); out.write(f'{number} 0 obj\n'.encode() + data + b'\nendobj\n')
        obj(1, b'<< /Type /Catalog /Pages 2 0 R >>')
        kids = ' '.join(f'{4 + 2 * i} 0 R' for i in range(count))
        obj(2, f'<< /Type /Pages /Count {count} /Kids [{kids}] >>'.encode())
        obj(3, b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>')
        for i in range(count):
            obj(4 + 2*i, f'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 2100 2600] /Resources << /Font << /F1 3 0 R >> >> /Contents {5+2*i} 0 R >>'.encode())
            content = stream(i+1)
            obj(5 + 2*i, f'<< /Length {len(content)} >>\nstream\n'.encode() + content + b'endstream')
        xref = out.tell();out.write(f'xref\n0 {len(offsets)}\n0000000000 65535 f \n'.encode())
        for pos in offsets[1:]:out.write(f'{pos:010d} 00000 n \n'.encode())
        out.write(f'trailer\n<< /Size {len(offsets)} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode())
    return {'path':str(path),'requestedMiB':mib,'bytes':path.stat().st_size,'pages':count,'kind':'synthetic uncompressed text, all lines within MediaBox; stress only'}

if __name__ == '__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',default='.cache/large-pdfs');parser.add_argument('--sizes',nargs='+',type=int,default=[100,300,500]);args=parser.parse_args()
    directory=Path(args.output);directory.mkdir(parents=True,exist_ok=True)
    results=[generate(directory/f'text-{size}MiB.pdf',size) for size in args.sizes]
    (directory/'manifest.json').write_text(json.dumps(results,indent=2)+'\n');print(json.dumps(results,indent=2))
