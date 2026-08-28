#!/usr/bin/env bash
# =====================================================================================
# v2.9 — cold-start budget for the head-unit launcher.
#
# Usage: cold-start.sh <dir containing the debug APK>
# Runs inside reactivecircus/android-emulator-runner, with an emulator already booted.
#
# Procedure, and why each step is there:
#   1. Install the debug APK (release is arm64-only, so it will not run on the emulator).
#   2. `pm compile -m speed-profile` — applies the baseline profile the same way a real
#      second launch would. Measuring the very first, still-interpreted launch would
#      report the one number the profile is there to avoid.
#   3. `am start -W` COLD_START runs, force-stopped between each, and take the MEDIAN
#      TotalTime. A shared CI runner produces the occasional 2x outlier; a median of five
#      absorbs one without hiding a real regression.
#
# The budget is a REGRESSION TRIPWIRE measured on an emulator, not a head-unit figure.
# It is set loose on purpose: this job has never had a green run to calibrate against, so
# a tight budget would only teach people to ignore a red check. Tighten it to roughly
# 1.3x the observed median once a few runs exist.
# =====================================================================================
set -euo pipefail

APK_DIR="${1:-apk}"

# The debug build carries applicationIdSuffix ".debug", but the suffix applies to the
# application ID only — the namespace, and so the activity's class name, is unchanged. The
# component is therefore <debug application id>/<undecorated class>, which look mismatched
# side by side and are meant to.
PACKAGE="com.reveng.carlauncher.debug"
ACTIVITY="com.reveng.carlauncher.MainActivity"
RUNS=5
BUDGET_MS=2500

APK="$(find "$APK_DIR" -name '*.apk' | head -n1)"
if [ -z "$APK" ]; then
  echo "no APK found under $APK_DIR" >&2
  exit 1
fi

adb install -r -t "$APK"

# Grant up front: a runtime permission dialog on first launch would be measured as startup.
adb shell pm grant "$PACKAGE" android.permission.ACCESS_FINE_LOCATION || true

# Warm run, discarded: it is what writes and applies the ART profile.
#
# A launch failure is fatal here rather than tolerated. `am start` reports a bad component on
# stdout and still exits 0, so the original `|| true` let a malformed component through this
# step and surfaced it five runs later as "produced no TotalTime:" — which points at the
# measurement loop instead of at the component that was actually wrong.
warm="$(adb shell am start -W -n "$PACKAGE/$ACTIVITY" 2>&1)"
if echo "$warm" | grep -qi 'error'; then
  echo "could not launch $PACKAGE/$ACTIVITY:" >&2
  echo "$warm" >&2
  exit 1
fi
sleep 5
adb shell pm compile -m speed-profile "$PACKAGE"

times=()
for i in $(seq 1 "$RUNS"); do
  adb shell am force-stop "$PACKAGE"
  sleep 2
  out="$(adb shell am start -W -n "$PACKAGE/$ACTIVITY")"
  total="$(echo "$out" | awk -F': *' '/^TotalTime:/ {print $2}')"
  if [ -z "$total" ]; then
    echo "run $i produced no TotalTime:" >&2
    echo "$out" >&2
    exit 1
  fi
  echo "run $i: ${total} ms"
  times+=("$total")
done

median="$(printf '%s\n' "${times[@]}" | sort -n | awk '{a[NR]=$1} END {print a[int((NR+1)/2)]}')"

echo "----"
echo "median cold start: ${median} ms (budget ${BUDGET_MS} ms, emulator — not a device figure)"

if [ "$median" -gt "$BUDGET_MS" ]; then
  echo "COLD START OVER BUDGET" >&2
  exit 1
fi
