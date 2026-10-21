#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
: "${JUNIT_JAR:?Set JUNIT_JAR to JUnit 4.13.2}"
: "${HAMCREST_JAR:?Set HAMCREST_JAR to Hamcrest Core 1.3}"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
sources="$root/app/src/main/java/io/github/avikulin/thud/service/dircon"
tests="$root/app/src/test/java/io/github/avikulin/thud/service/dircon"
kotlinc "$sources/DirConPacket.kt" "$sources/DirConInput.kt" \
    "$sources/DirConConnectionSlots.kt" "$sources/DirConSocketWriter.kt" \
    "$tests/DirConInputTest.kt" "$tests/DirConResourcesTest.kt" \
    -cp "$JUNIT_JAR" -include-runtime -d "$out/tests.jar"
java -cp "$out/tests.jar:$JUNIT_JAR:$HAMCREST_JAR" org.junit.runner.JUnitCore \
    io.github.avikulin.thud.service.dircon.DirConInputTest \
    io.github.avikulin.thud.service.dircon.DirConResourcesTest
if [[ -n "${ANDROID_JAR:-}" && -n "${COROUTINES_JAR:-}" ]]; then
    kotlinc "$sources/"*.kt \
        "$root/app/src/main/java/io/github/avikulin/thud/service/ftms/FtmsControlAuthorization.kt" \
        -cp "$ANDROID_JAR:$COROUTINES_JAR" -d "$out/production.jar"
    printf '%s\n' 'Production DirCon sources compiled against Android and coroutines jars.'
fi
