#!/usr/bin/env bash
# Read and drive the unit's screen over adb without a camera: the launcher is Compose and
# exposes its texts to accessibility, so `uiautomator dump` lists them and a label can be tapped.
#
#     bench-ui.sh texts                 every text / content-desc on screen, one per line
#     bench-ui.sh tap <label>           tap the node whose text or content-desc is <label>
#     bench-ui.sh find <label>          scroll down until <label> is on screen, then tap it
#     bench-ui.sh doctor                open Settings -> Setup doctor and print its rows
#
# Env: ADB (default: the SDK's platform-tools adb), UNIT (adb serial, default 10.0.10.14:5555).
# A Compose screen that is off-screen is not in the dump; `find` swipes up to eight times.
set -euo pipefail

ADB=${ADB:-/opt/android-sdk/platform-tools/adb}
UNIT=${UNIT:-10.0.10.14:5555}
readonly DUMP=/sdcard/riposte-ui.xml
readonly SWIPE="960 600 960 250 300"     # one screen-height scroll on the 1920x720 panel
readonly FIND_TRIES=8

a() { "$ADB" -s "$UNIT" "$@"; }

dump() {
  a shell "uiautomator dump $DUMP" >/dev/null 2>&1
  a shell "cat $DUMP" | tr -d '\r'
}

texts() {
  dump | grep -o '\(text\|content-desc\)="[^"]\+"' | sed 's/^[a-z-]*=//; s/^"//; s/"$//' | awk '!seen[$0]++'
}

# Centre of the first node whose text or content-desc equals the label, or nothing.
centre() {
  dump | python3 -c '
import re, sys
label = sys.argv[1]
for m in re.finditer(r"<node [^>]*>", sys.stdin.read()):
    n = m.group(0)
    if f"text=\"{label}\"" in n or f"content-desc=\"{label}\"" in n:
        b = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", n)
        print((int(b[1]) + int(b[3])) // 2, (int(b[2]) + int(b[4])) // 2)
        break
' "$1"
}

tap() {
  local c
  c=$(centre "$1")
  [ -n "$c" ] || { echo "no node '$1' on screen" >&2; return 1; }
  a shell "input tap $c"
  echo "tapped '$1' at $c"
}

find_tap() {
  local i
  for i in $(seq 1 $FIND_TRIES); do
    if tap "$1" 2>/dev/null; then
      return 0
    fi
    a shell "input swipe $SWIPE"
    sleep 1
  done
  echo "'$1' not found after $FIND_TRIES screens" >&2
  return 1
}

doctor() {
  a shell "input keyevent KEYCODE_HOME"; sleep 2
  tap Settings >/dev/null; sleep 2
  find_tap "Setup doctor" >/dev/null; sleep 3
  texts
  # The sections below the first screen: scroll and print what is new.
  local before after
  before=$(texts)
  for i in $(seq 1 $FIND_TRIES); do
    a shell "input swipe $SWIPE"; sleep 1
    after=$(texts)
    [ "$after" = "$before" ] && break
    echo "$after" | grep -Fxv -f <(echo "$before") || true
    before=$after
  done
}

case "${1:-}" in
  texts) texts ;;
  tap) tap "${2:?label}" ;;
  find) find_tap "${2:?label}" ;;
  doctor) doctor ;;
  *) sed -n '2,12p' "$0"; exit 1 ;;
esac
