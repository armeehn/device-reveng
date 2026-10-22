#!/usr/bin/env bash
# Push the `data` entries of tools.lock (too big for /system) to the unit over adb:
# /data/local/riposte/bin. They survive a no-wipe flash and vanish with a userdata wipe, so
# bench-cycle.sh --wipe is followed by this. frida-server is the only one today.
#
# Usage: install-data.sh [CACHE] [SERIAL]      (defaults: the share cache, RIPOSTE_UNIT_ADB)
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
# Site values (hosts, addresses, share paths) come from $RIPOSTE_OS_ENV, never from this
# file: the repo is public. os/riposte-os.env.example lists them.
RIPOSTE_OS_ENV=${RIPOSTE_OS_ENV:-/etc/riposte-os.env}
# shellcheck source=/dev/null
[ -r "$RIPOSTE_OS_ENV" ] && . "$RIPOSTE_OS_ENV"
CACHE=${1:-${RIPOSTE_SHARE:?set RIPOSTE_SHARE}/os/tools/unit}
SERIAL=${2:-${RIPOSTE_UNIT_ADB:?set RIPOSTE_UNIT_ADB}}
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
