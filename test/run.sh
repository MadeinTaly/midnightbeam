#!/usr/bin/env bash
# Plain JVM tests of ScheduleMath: the window/ramp/edge checks and the dayrhythm conformance vectors
# (regenerated from vendor/dayrhythm when the submodule is there, else the committed spec-cases.txt).
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

javac -nowarn -d "$OUT" app/src/dev/midnightbeam/ScheduleMath.java test/WindowTest.java test/ScheduleMathTest.java
V=vendor/dayrhythm/test/vectors
if [ -f "$V/active-slot.json" ]; then
    "$(command -v python3 || command -v python)" test/vectors2lines.py "$V/active-slot.json" "$V/active-slot-week.json" > test/spec-cases.txt
fi
java -cp "$OUT" dev.midnightbeam.WindowTest
java -cp "$OUT" dev.midnightbeam.ScheduleMathTest test/spec-cases.txt | tee "$OUT/result"
grep -q ' 0 failed' "$OUT/result"
