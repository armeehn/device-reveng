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
# A CI baseline, not the farm's: a different system image draws different fonts and a
# different status bar. Blessed from a green CI run, once this image captures real pixels.
BASELINE="$FLOW_SRC/baseline-ci"
# 05-apps searches the grid for "Calculator". The farm instance carries the 28 suite APKs;
# a bare aosp_atd image has no calculator at all and the grid answers "No matching apps"
# (run 5191 hierarchy dump). The journey is sound, the app simply is not here.
# 12-home-back opens system Settings to sit behind Home; the image has no Settings either
# (run 5280). HomeBackTest in the instrumentation job guards the same Back rule here.
CI_SKIP_FLOWS="05-apps.yaml 12-home-back.yaml"
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
# THE PIXEL GATE IS NOT AVAILABLE ON THIS IMAGE. Runs 5191 and 5202 both answered every tap
# and composed every screen, and both read every frame back as 4,147,200 zero bytes — a
# fully black picture, with Vulkan on and with it off. The emulator renders host-side
# through gfxstream and the guest never sees the colour buffers, so `screencap` and the
# in-process capture the accessibility audit takes have nothing to read.
#
# Blessing those frames as a baseline would produce a check that is green forever and
# catches nothing, so the diff runs only when the capture is real. If a later image or GPU
# mode starts handing pixels back, this prints what to do and the run says so out loud.
probe="$OUT_DIR/.capture-probe.png"
capture_ok=no
adb -s "$SERIAL" exec-out screencap -p >"$probe" 2>/dev/null || true
if python3 .gitea/scripts/capture-probe.py "$probe" "$FLOW_SRC"; then
  capture_ok=yes
fi

# ------------------------------------------------------------------------------- flows
# Staged: run.sh hands the whole directory to `maestro test`, and only the journey flows
# this image can satisfy belong in CI. The nine under e2e/maestro/suite need the 28 suite
# APKs, which are not built here.
flows="$(dirname "$OUT_DIR")/flows"
rm -rf "$flows"
mkdir -p "$flows"
cp "$FLOW_SRC"/*.yaml "$flows/"
for skip in $CI_SKIP_FLOWS; do
  rm -f "$flows/$skip"
done
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
if [ "$capture_ok" = "no" ]; then
  echo "screenshot baseline: SKIPPED — every frame this image captures is black, so a"
  echo "diff of them would pass whatever the launcher drew. The journeys above are the"
  echo "gate; the pixels stay the farm's job (headunit maestro + shots.py)."
  exit "$status"
fi

# The capture works. Diff against a baseline blessed from a CI run, never the farm's:
# a different system image draws different fonts and a different status bar.
if [ ! -d "$BASELINE" ]; then
  echo "::error::this image captures real pixels but $BASELINE does not exist — bless one"
  echo "::error::from this run's artifact: shots.py accept <run-dir> $BASELINE"
  exit 1
fi

echo "screenshot baseline: $(basename "$run_dir") against $BASELINE"
python3 "$FLOW_SRC/shots.py" check "$run_dir" "$BASELINE" || status=$?

exit "$status"
