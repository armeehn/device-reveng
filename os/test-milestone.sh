#!/usr/bin/env bash
# os_milestone (lib.sh) names the Riposte OS milestone from the profile and the Android the
# system carries: 0.1 the stock system, 0.2 an Android 14 GSI, 0.3 Android 16 and later. The
# crDroid 12.12 (Android 16) candidate of 2026-09-30 came out labelled 0.2 because the
# milestone followed the profile alone. Runs anywhere with bash.
set -euo pipefail
cd "$(dirname "$0")"
# shellcheck source=lib.sh
. ./lib.sh

T=$(mktemp -d)
trap "rm -rf $T" EXIT

prop() { printf 'ro.build.version.release=%s\nro.build.version.sdk=%s\n' "$1" "$2" > "$T/build.prop"; }
expect() {
  local got
  got=$(os_milestone "$1" "$T/build.prop")
  [ "$got" = "$2" ] || { echo "FAIL: profile $1, $(tr '\n' ' ' < $T/build.prop): got $got, want $2"; exit 1; }
}

prop 13 33; expect tier2 0.1
prop 14 34; expect gsi 0.2
prop 16 36; expect gsi 0.3
prop 17 37; expect gsi 0.3
: > "$T/build.prop"; expect gsi 0.2     # no sdk line: the GSI base it has always been
echo "MILESTONE PASS"
