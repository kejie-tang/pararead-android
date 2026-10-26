#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
if [ -z "${JAVA_HOME:-}" ]; then
    pararead_jdk=$(find "$PWD/.cache/toolchains/complete" -type d -path '*/Contents/Home' -maxdepth 5 2>/dev/null | head -n 1)
    if [ -n "$pararead_jdk" ]; then export JAVA_HOME="$pararead_jdk"; fi
fi
exec ./gradlew "$@"
