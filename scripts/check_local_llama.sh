#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
: "${PARAREAD_GGUF:?Set PARAREAD_GGUF to a local Qwen3 GGUF file}"
pararead_mode=${1:-jni}
case "$pararead_mode" in jni|api) ;; *) echo "Usage: $0 [jni|api]" >&2; exit 1 ;; esac
pararead_cmake=${PARAREAD_CMAKE:-}
if [ -z "$pararead_cmake" ]; then
    pararead_cmake=$(command -v cmake || true)
fi
if [ -z "$pararead_cmake" ]; then
    pararead_cmake="${ANDROID_HOME:-$HOME/Library/Android/sdk}/cmake/3.22.1/bin/cmake"
fi
python3 scripts/fetch_llama.py
exec scripts/gradle.sh -PhostLlama=true "-PcmakePath=$pararead_cmake" :tools:host-llama:run "--args=$pararead_mode"
