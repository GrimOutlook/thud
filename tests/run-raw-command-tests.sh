#!/usr/bin/env bash
# Requires a JDK and kotlinc on PATH. No Android SDK, network, or hardware needed.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
src="$root/app/src/main/java/io/github/avikulin/thud/service"
kotlinc "$src/RawCommandLimits.kt" "$src/RawCommandPlanner.kt" \
    "$root/tests/raw-command-validation.kt" -include-runtime -d "$out/tests.jar"
java -jar "$out/tests.jar"
