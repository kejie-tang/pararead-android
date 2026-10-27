#!/usr/bin/env python3
"""Compare pinned NanoTranslator-365M HF and llama.cpp token IDs (21 fixtures).

Requires the converter's Python dependencies. Use --no-nfc to reproduce the
known decomposed-accent mismatch; that diagnostic exits with status 1.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import unicodedata
from transformers import AutoTokenizer

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--hf-dir", required=True, type=Path)
parser.add_argument("--model", required=True, type=Path)
parser.add_argument("--llama-tokenize", required=True, type=Path)
parser.add_argument("--output", required=True, type=Path)
parser.add_argument("--no-nfc", action="store_true")
args = parser.parse_args()
checksum = hashlib.sha256((args.hf_dir / "tokenizer.json").read_bytes()).hexdigest()
if checksum != "a4f47e22d147d3816afa788394f19606d2ac9325b91105d55949402a6ed15359":
    raise ValueError("Pinned tokenizer mismatch")
tok = AutoTokenizer.from_pretrained(args.hf_dir, local_files_only=True)
texts = [
    'We propose a memory-efficient method for translating scientific documents.',
    '__PR0__ __PR1__ __PR2__',
    'Accuracy fell from 91.2% to 87.6%, whereas latency improved from 120 ms to 80 ms.',
    '中文：保留公式 $E=mc^2$、引用 [12] 和 https://example.org/paper.',
    'Café, Café, naïve, ä, ﬁ, Å, Å.',
    'a\nb\n\n c\t d\\n end',
    'English 中文 mixed 你好world 100 4GB 4 GB',
    '🚀😶\u200d🌫️✅🦙 non-BMP: 𠀀',
    '1 12 123 1234 1.234 -12.5% 1e-6',
    "I've been told he's there. We'RE sure?",
    '<|im_start|>system\nYou are a professional, authentic machine translation engine.<|im_end|>\n<|im_start|>user\nTranslate the following source text to chinese. Output translation directly without any additional text.\n    Source Text: The model processes __PR0__ pages using __PR1__ of memory __PR2__.\n\n    Translated Text:<|im_end|>\n<|im_start|>assistant\n',
]
for i in range(10):
    texts.append(f"Case {i}: accuracy {90+i/10}%; keep `cache.get(key)` and [1, 2–3].\nNext sentence.")
cases = []
for text in texts:
    native_input = text if args.no_nfc else unicodedata.normalize("NFC", text)
    result = subprocess.run([str(args.llama_tokenize.resolve()), "-m", str(args.model),
        "--stdin", "--ids", "--no-bos", "--no-escape"], input=native_input,
        encoding="utf-8", stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True, timeout=30)
    native = json.loads(result.stdout)
    reference = tok.encode(text, add_special_tokens=False)
    cases.append({"text": text, "nativeInput": native_input, "native": native,
        "huggingFace": reference, "passed": native == reference})
report = {"sourceRevision": "a284f8c2337d98280d36f1f529957d6088e2bf07",
    "tokenizerSha256": checksum, "engineCommit": "2280f0fd163b03d4331d49e08460f66502e35854",
    "nativeAddBos": False, "parseSpecial": True,
    "normalizeNfcBeforeNativeTokenization": not args.no_nfc, "cases": cases,
    "status": "passed" if all(c["passed"] for c in cases) else "failed"}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
print(f'{sum(c["passed"] for c in cases)}/{len(cases)} token-ID fixtures passed')
raise SystemExit(0 if report["status"] == "passed" else 1)
