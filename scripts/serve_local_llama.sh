#!/bin/sh
set -eu
: "${PARAREAD_GGUF:?Set PARAREAD_GGUF to a local Qwen3 GGUF file}"
exec llama-server -m "$PARAREAD_GGUF" --alias qwen3:1.7b \
    --host 127.0.0.1 --port "${PARAREAD_LLAMA_PORT:-18080}" \
    -c 4096 -b 512 -ub 128 -t 4 -tb 4 -np 1 -ngl 0 --reasoning off --no-webui
