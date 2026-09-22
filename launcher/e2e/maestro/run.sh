#!/bin/bash
# Run every flow against an instance; JUnit + PNGs into $OUT. Runs INSIDE the farm CT as emu.
#   run.sh <serial> <flow dir> <out dir>
set -uo pipefail
SERIAL=$1; FLOWS=$2; OUT=$3
export JAVA_HOME=/opt/jdk17 PATH=/opt/jdk17/bin:/opt/android-sdk/platform-tools:$HOME/.maestro/bin:$PATH
mkdir -p "$OUT" && cd "$OUT"
adb -s "$SERIAL" shell input keyevent KEYCODE_HOME >/dev/null 2>&1
# No animations: a screenshot taken during an entry fade differs from the same screen a frame
# later, and the suite shots drifted up to 41% between runs of one build for that alone
# (screenshot differ, 2026-09-22). CI's emulator runner does the same for its instrumentation.
for scale in window_animation_scale transition_animation_scale animator_duration_scale; do
  adb -s "$SERIAL" shell settings put global "$scale" 0 >/dev/null 2>&1
done
maestro --device "$SERIAL" test "$FLOWS" --format junit --output "$OUT/junit.xml" --test-output-dir "$OUT" 2>&1 | grep -v "^$" | tail -40
