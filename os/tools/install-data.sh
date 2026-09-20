#!/usr/bin/env bash
# Push the `data` entries of tools.lock (too big for /system) to the unit over adb:
# /data/local/riposte/bin. They survive a no-wipe flash and vanish with a userdata wipe, so
# bench-cycle.sh --wipe is followed by this. frida-server is the only one today.
#
# Usage: install-data.sh [CACHE] [SERIAL]      (defaults: the share cache, 10.0.10.14:5555)
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
CACHE=${1:-/z1-pool/share/carlauncher/os/tools/unit}
SERIAL=${2:-10.0.10.14:5555}
ADB=${ADB:-adb}
readonly DATA_BIN=/data/local/riposte/bin

log() { printf '[tools] %s\n' "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

# After a flash the unit's Wi-Fi adb comes up a minute or two behind the framework.
readonly WAIT_TRIES=24 WAIT_STEP=5
for _ in $(seq 1 $WAIT_TRIES); do
  "$ADB" connect "$SERIAL" >/dev/null 2>&1 || true
  "$ADB" -s "$SERIAL" shell "mkdir -p $DATA_BIN" 2>/dev/null && break
  sleep $WAIT_STEP
done
"$ADB" -s "$SERIAL" shell "[ -d $DATA_BIN ]" 2>/dev/null || die "no adb at $SERIAL"

N=0
while read -r name kind sha url; do
  case "$name" in ''|'#'*) continue ;; esac
  [ "$kind" = data ] || continue

  # The cache keeps the compressed download; unpack it beside, once, to push the binary.
  src="$CACHE/$name"
  case "$url" in
    *.xz) [ -f "$src" ] || xz -dc "$CACHE/$name.xz" > "$src" ;;
  esac
  [ -f "$src" ] || die "$name not in $CACHE, run fetch.sh"

  "$ADB" -s "$SERIAL" push "$src" "$DATA_BIN/$name" >/dev/null
  "$ADB" -s "$SERIAL" shell "chmod 0755 $DATA_BIN/$name"
  log "pushed $name -> $DATA_BIN/$name"
  N=$((N + 1))
done < "$HERE/tools.lock"
log "$N data tools on $SERIAL"
