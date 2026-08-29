#!/usr/bin/env bash
# =====================================================================================
# Give the CI emulator the head unit's geometry: 1920x720 landscape at 240 dpi.
#
# Runs as android-emulator-runner's `pre-emulator-launch-script`, i.e. after the AVD has
# been created and before the emulator starts — the only window in which config.ini can
# still be edited.
#
# WHY NOT A `profile:`. That input is passed to `avdmanager create avd --device <name>`,
# which only accepts devices the SDK already ships. None of them is 1920x720 @240dpi: the
# job used `pixel_6`, a 1080x2400 @420dpi PORTRAIT phone, so nothing in CI had ever been
# laid out at the shape the launcher actually ships on. Inventing a device name would not
# have helped — avdmanager would reject it, or the action would fall back to a default.
# So the AVD is created with no --device at all and its config.ini is written here.
#
# Every value the emulator honours must be set explicitly. skin.path=_no_skin is what tells
# the emulator to use hw.lcd.* verbatim instead of a packaged skin, and hw.initialOrientation
# is what keeps 1920x720 landscape rather than a rotated 720x1920.
#
# This script only ASKS. `assert-geometry.sh` checks what the emulator actually did, because a
# rejected or ignored property here shows up as a silent fall back to the image default.
# =====================================================================================
set -euo pipefail

: "${AVD_NAME:?AVD_NAME must be set by the workflow, and must match the action's avd-name}"

# The action creates AVDs under $ANDROID_AVD_HOME when it is set, and under the legacy
# ~/.android/avd otherwise. Try both rather than assume the runner image's layout.
config=""
for dir in "${ANDROID_AVD_HOME:-}" "$HOME/.android/avd" "$HOME/.config/.android/avd"; do
  if [ -n "$dir" ] && [ -f "$dir/$AVD_NAME.avd/config.ini" ]; then
    config="$dir/$AVD_NAME.avd/config.ini"
    break
  fi
done

if [ -z "$config" ]; then
  echo "::error::no config.ini for AVD '$AVD_NAME' — searched ANDROID_AVD_HOME, ~/.android/avd, ~/.config/.android/avd" >&2
  exit 1
fi

# Replace rather than append: avdmanager may already have written some of these, and the
# emulator takes the LAST occurrence of a key, so a blind append would work by luck only.
set_prop() {
  local key="$1" value="$2"
  sed -i "/^${key}=/d" "$config"
  printf '%s=%s\n' "$key" "$value" >>"$config"
}

set_prop hw.lcd.width 1920
set_prop hw.lcd.height 720
set_prop hw.lcd.density 240
set_prop hw.initialOrientation landscape
set_prop skin.name 1920x720
set_prop skin.path _no_skin

echo "patched $config:"
grep -E '^(hw\.lcd\.|hw\.initialOrientation|skin\.)' "$config"
