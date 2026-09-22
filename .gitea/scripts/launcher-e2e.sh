#!/usr/bin/env bash
# =====================================================================================
# Run the launcher's Maestro journey flows on the CI emulator and diff their screenshots.
#
# Usage: launcher-e2e.sh <dir containing the farm APK> <output dir>
# Runs inside reactivecircus/android-emulator-runner, with an emulator already booted and
# .gitea/scripts/assert-geometry.sh already green.
#
#   farm APK ──install──▶ emulator ──set-home-activity──▶ HOME
#                            │
#              launcher-first-run.yaml taps Skip (clears the onboarding flag)
#                            │
#            e2e/maestro/run.sh ──▶ JUnit + PNGs ──▶ shots.py check ──▶ red on drift
#
# WHY THE FARM VARIANT. The flows name `com.ripostelabs.carlauncher`. The debug build
# carries applicationIdSuffix ".debug", so every flow would fail to find the app. `farm`
# is the debug build under the release application id, x86_64 only (app/build.gradle.kts)
# — exactly what the emulator farm installs. Building it here means CI drives the same
# package identity the flows were written and blessed against, and no flow is parameterised
# for CI.
#
# WHY NO `pm grant`. Maestro grants every permission the manifest asks for on each
# `launchApp` (maestro.log, Maestro.setPermissionInternal), so a shell grant loop before
# the run only repeats what Maestro does anyway.
#
# WHY THE /opt SYMLINKS. run.sh is the farm's script, run verbatim: it hard-codes the farm
# container's JDK and platform-tools paths. Pointing those names at this runner's toolchain
# is cheaper and safer than forking the script — CI then runs the same file the farm does,
# which is the only way the two stay comparable.
# =====================================================================================
set -euo pipefail

APK_DIR="${1:-apk}"
OUT_DIR="${2:-$PWD/maestro-out}"

PACKAGE="com.ripostelabs.carlauncher"
ACTIVITY="com.ripostelabs.carlauncher.MainActivity"
MAESTRO_HOME="$HOME/.maestro"
FLOW_SRC="launcher/e2e/maestro"
FIRST_RUN_FLOW=".gitea/scripts/launcher-first-run.yaml"
BASELINE="$FLOW_SRC/baseline"
# 05-apps searches the grid for "Calculator". The farm instance carries the 28 suite APKs;
# a bare aosp_atd image has no calculator at all and the grid answers "No matching apps"
# (run 5191 hierarchy dump). The journey is sound, the app simply is not here.
CI_SKIP_FLOW="05-apps.yaml"
# The launcher writes its first-run flag on its own coroutine scope; give it a moment to
# reach disk before the next launch reads it back (AccessibilityAuditTest waits 1 s too).
FLAG_SETTLE_S=3

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"
# Absolute from here on: run.sh cds into the output directory before it reads the flow path.
OUT_DIR="$(cd "$OUT_DIR" && pwd)"

# ---------------------------------------------------------------------------- device
SERIAL="$(adb devices | awk '/\tdevice$/ {print $1; exit}')"
if [ -z "$SERIAL" ]; then
  echo "::error::no booted emulator in \`adb devices\`"
  adb devices
  exit 1
fi
echo "device: $SERIAL"

# The marker the workflow reads to tell an emulator that never booted (retry it) from a
# journey that genuinely failed (do not retry it — a flaky gate is worse than none).
touch "$OUT_DIR/.device-reached"

# ---------------------------------------------------------------------------- install
APK="$(find "$APK_DIR" -name '*.apk' | head -n1)"
if [ -z "$APK" ]; then
  echo "::error::no APK under $APK_DIR"
  exit 1
fi
adb -s "$SERIAL" install -r -t "$APK"

# HOME, so the KEYCODE_HOME run.sh sends first goes to the launcher rather than to a
# chooser dialog between it and the AOSP launcher.
home="$(adb -s "$SERIAL" shell cmd package set-home-activity "$PACKAGE/$ACTIVITY" | tr -d '\r')"
echo "set-home-activity: $home"
if ! printf '%s' "$home" | grep -q 'Success'; then
  echo "::error::could not make $PACKAGE the HOME activity"
  exit 1
fi

# ------------------------------------------------------------------------ the toolchain
# run.sh expects the farm container's layout; give this runner the same names.
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ]; then
  sdk="$(dirname "$(dirname "$(command -v adb)")")"
fi
[ -e /opt/jdk17 ] || sudo ln -s "${JAVA_HOME:?JAVA_HOME must be set by setup-java}" /opt/jdk17
[ -e /opt/android-sdk ] || sudo ln -s "$sdk" /opt/android-sdk
export PATH="$MAESTRO_HOME/bin:$PATH"
maestro --version || true

# --------------------------------------------------------------------------- first run
# Onboarding sits over Home on a fresh /data. Skip clears the flag for every flow after it.
maestro --device "$SERIAL" test "$FIRST_RUN_FLOW"
sleep "$FLAG_SETTLE_S"

# ------------------------------------------------------------------- capture self-check
# Every PNG of run 5191 was 4,147,200 zero bytes: the emulator composed and answered taps,
# but the framebuffer read back black, which would make a pixel baseline a gate that proves
# nothing. Probe the capture path before the flows so the log says which it was.
probe="$OUT_DIR/.capture-probe.png"
adb -s "$SERIAL" exec-out screencap -p >"$probe" 2>/dev/null || true
python3 - "$probe" <<'PY' || true
import os
import sys

path = sys.argv[1]
if not os.path.exists(path) or os.path.getsize(path) == 0:
    print("capture probe: adb screencap produced nothing")
    raise SystemExit(0)

sys.path.insert(0, "launcher/e2e/maestro")
import shots

image = shots.read_png(path)
nonzero = sum(1 for byte in image.data if byte)
print("capture probe: %dx%d, %d of %d bytes non-zero" % (
    image.width, image.height, nonzero, len(image.data)))
PY

# ------------------------------------------------------------------------------- flows
# Staged: run.sh hands the whole directory to `maestro test`, and only the journey flows
# this image can satisfy belong in CI. The nine under e2e/maestro/suite need the 28 suite
# APKs, which are not built here.
flows="$(dirname "$OUT_DIR")/flows"
rm -rf "$flows"
mkdir -p "$flows"
cp "$FLOW_SRC"/*.yaml "$flows/"
rm -f "$flows/$CI_SKIP_FLOW"
echo "running $(ls "$flows" | wc -l) journey flows"

status=0
bash "$FLOW_SRC/run.sh" "$SERIAL" "$flows" "$OUT_DIR" || status=$?

# ---------------------------------------------------------------------------- baseline
# Maestro writes its artefacts into a timestamped directory under --test-output-dir, and
# that directory is the run shots.py reads (README: `shots.py check maestro-out/<stamp>`).
run_dir="$(find "$OUT_DIR" -mindepth 1 -maxdepth 1 -type d | sort | tail -n1)"
if [ -z "$run_dir" ]; then
  echo "::error::Maestro left no run directory under $OUT_DIR"
  exit 1
fi

echo "----"
echo "screenshot baseline: $(basename "$run_dir") against $BASELINE"
python3 "$FLOW_SRC/shots.py" check "$run_dir" "$BASELINE" || status=$?

exit "$status"
