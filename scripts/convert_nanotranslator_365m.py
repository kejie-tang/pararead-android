#!/usr/bin/env python3
"""Convert the pinned NanoTranslator-365M tokenizer with its existing Qwen2 BPE rules.

Run with the pinned llama.cpp converter's Python requirements installed. This only
recognizes the exact tested HF files; it does not guess a pre-tokenizer for other models.
See docs/reports/mid-small-models.md for token-ID parity checks and limitations.
"""
import argparse
import hashlib
import json
from pathlib import Path
import runpy
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--llama-dir", required=True, type=Path)
parser.add_argument("--hf-dir", required=True, type=Path)
parser.add_argument("--outfile", required=True, type=Path)
args = parser.parse_args()
hf_dir = args.hf_dir.resolve()
expected = {
    "config.json": "b3a462fc56bbac2fe3f318da97d01a89cb96410704836673a8176cbaa4c30f4c",
    "tokenizer.json": "a4f47e22d147d3816afa788394f19606d2ac9325b91105d55949402a6ed15359",
    "tokenizer_config.json": "11f7f20838bdf671f9e667950d7239a698b5964053add123e5d3d121a80a6248",
    "model.safetensors": "b23a230bdafb3f95ec8640471f036bdd53118f85fc3d00c284c2cba4a41382e7",
}
for name, checksum in expected.items():
    with (hf_dir / name).open("rb") as stream:
        actual = hashlib.file_digest(stream, "sha256").hexdigest()
    if actual != checksum:
        raise ValueError(f"Pinned HF source mismatch: {name}")
config = json.loads((hf_dir / "config.json").read_text())
assert config["model_type"] == "qwen2" and config["vocab_size"] == 8000

llama_dir = args.llama_dir.resolve()
sys.path[:0] = [str(llama_dir), str(llama_dir / "gguf-py")]
from conversion import TextModel  # noqa: E402

original = TextModel.get_vocab_base_pre


def known_nano_pre(self, tokenizer):
    if self.dir_model.resolve() == hf_dir:
        # Exact Split regex and ByteLevel settings match llama.cpp's QWEN2 pre-tokenizer.
        # The HF vocabulary differs, so the upstream token-ID fingerprint is unrecognized.
        # HF also applies NFC; the research prompt normalizes protected text to NFC.
        return "qwen2"
    return original(self, tokenizer)


TextModel.get_vocab_base_pre = known_nano_pre
sys.argv = [str(llama_dir / "convert_hf_to_gguf.py"), str(hf_dir),
            "--outfile", str(args.outfile.resolve()), "--outtype", "f16"]
runpy.run_path(sys.argv[0], run_name="__main__")
