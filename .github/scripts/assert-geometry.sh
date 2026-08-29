#!/usr/bin/env bash
# =====================================================================================
# Prove the booted emulator really is the head unit's shape: 1920x720 at 240 dpi.
#
# `headunit-avd.sh` only writes config.ini. An unknown or rejected property there does not
# fail anything — the emulator boots at the system image's own default and every job
# downstream quietly measures and renders a phone. That silent fall back is the whole reason
# this check exists, so it runs first in every job that boots an emulator and it is fatal.
#
# `wm size` prints "Physical size: WxH", plus an "Override size:" line if anything has
# resized the display. Both are checked: an override would mean the tests see a shape that
# is not the one the AVD was built for.
# =====================================================================================
set -euo pipefail

WANT_W=1920
WANT_H=720
WANT_DENSITY=240

size="$(adb shell wm size | tr -d '\r')"
density="$(adb shell wm density | tr -d '\r')"

echo "emulator geometry"
echo "  $size"
echo "  $density"
echo "  wanted: ${WANT_W}x${WANT_H} @ ${WANT_DENSITY}dpi (Choiceway GT6-EAU head unit)"

physical_size="$(printf '%s\n' "$size" | sed -n 's/^Physical size: *//p')"
physical_density="$(printf '%s\n' "$density" | sed -n 's/^Physical density: *//p')"

if [ "$physical_size" != "${WANT_W}x${WANT_H}" ]; then
  echo "::error::emulator booted at '$physical_size', not ${WANT_W}x${WANT_H} — the AVD fell back to a default profile"
  exit 1
fi

if [ "$physical_density" != "$WANT_DENSITY" ]; then
  echo "::error::emulator density is '$physical_density', not ${WANT_DENSITY} — the AVD fell back to a default profile"
  exit 1
fi

if printf '%s\n' "$size" | grep -q '^Override size:'; then
  echo "::error::a display size override is in effect; the tests would not run at the AVD's geometry"
  exit 1
fi

echo "geometry OK"
