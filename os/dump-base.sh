#!/usr/bin/env bash
# Dump the stock logical partitions off the unit, resumably, over adb + Magisk su.
#
#   /dev/block/mapper/system_b ──dd 64 MiB chunks | gzip──► adb exec-out ──► out/system/0001.bin …
#
# The car sits on a relay and shows up for minutes at a time, so each chunk is
# hashed on the device and verified here; a chunk that matches is never fetched
# again. Re-run until `assemble` reports every partition complete. Every adb
# call is bounded: an unplugged unit leaves adb half-open, not refused.
#
# Usage: dump-base.sh [--serial S] [--out DIR] [--parts "system product boot ..."] [assemble]

set -euo pipefail
readonly CHUNK_MIB=64
readonly CHUNK=$((CHUNK_MIB * 1048576))
# product first: 0.2 needs only product + boot + vbmeta (the GSI brings system), and the car
# is online for minutes at a time.
readonly DEFAULT_PARTS="product boot dtbo vbmeta vbmeta_system system"
readonly ADB_T=30          # seconds for a control call
readonly CHUNK_T=900       # seconds for one chunk: 64 MiB at ~100 KiB/s

SERIAL=${RAV4_SERIAL:-100.127.132.101:5555}
OUT=${RAV4_BASE_OUT:-/z1-pool/share/carlauncher/os/base}
PARTS=$DEFAULT_PARTS
MODE=dump
while [ $# -gt 0 ]; do
  case "$1" in
    --serial) SERIAL=$2; shift 2 ;;
    --out) OUT=$2; shift 2 ;;
    --parts) PARTS=$2; shift 2 ;;
    assemble) MODE=assemble; shift ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done

log() { printf '%s dump-base: %s\n' "$(date '+%F %T')" "$*" >&2; }
a()  { timeout "$ADB_T" adb -s "$SERIAL" "$@"; }
su_() { a shell su -c "$*" | tr -d '\r'; }

# Block device for a partition on the ACTIVE slot: logical ones live under
# mapper, the rest under by-name, both with the slot suffix.
blockdev_for() { # part slot
  case "$1" in
    system|product|vendor|system_ext|odm) echo "/dev/block/mapper/$1$2" ;;
    *) echo "/dev/block/by-name/$1$2" ;;
  esac
}

assemble() {
  local part n size want got
  for part in $PARTS; do
    [ -f "$OUT/$part/SIZE" ] || { log "$part: never started"; continue; }
    size=$(cat "$OUT/$part/SIZE"); want=$(( (size + CHUNK - 1) / CHUNK ))
    got=$(find "$OUT/$part" -name '*.ok' | wc -l)
    if [ "$got" -ne "$want" ]; then log "$part: $got/$want chunks, incomplete"; continue; fi
    [ -f "$OUT/$part.img" ] && { log "$part: assembled already"; continue; }
    for n in $(seq -f %04g 0 $((want - 1))); do cat "$OUT/$part/$n.bin"; done > "$OUT/$part.img.tmp"
    [ "$(stat -c %s "$OUT/$part.img.tmp")" -eq "$size" ] || { log "$part: size mismatch after assembly"; rm -f "$OUT/$part.img.tmp"; continue; }
    mv "$OUT/$part.img.tmp" "$OUT/$part.img"
    (cd "$OUT" && sha256sum "$part.img" > "$part.img.sha256")
    log "$part: assembled $size bytes"
  done
}

[ "$MODE" = assemble ] && { assemble; exit 0; }

mkdir -p "$OUT"
a get-state >/dev/null 2>&1 || { timeout "$ADB_T" adb connect "$SERIAL" >/dev/null 2>&1 || true; }
a get-state >/dev/null 2>&1 || { log "no device at $SERIAL"; exit 1; }
SLOT=$(su_ getprop ro.boot.slot_suffix)
[ -n "$SLOT" ] || { log "no root or no slot"; exit 1; }
if [ ! -f "$OUT/BASE-INFO" ]; then
  { echo "slot=$SLOT"; echo "fingerprint=$(su_ getprop ro.build.fingerprint)";
    echo "product_fingerprint=$(su_ getprop ro.product.build.fingerprint)";
    echo "incremental=$(su_ getprop ro.build.version.incremental)"; echo "dumped=$(date -u +%F)"; } > "$OUT/BASE-INFO"
fi
grep -q "^slot=$SLOT$" "$OUT/BASE-INFO" || { log "slot changed since the dump began ($SLOT); refusing to mix"; exit 1; }

for part in $PARTS; do
  dev=$(blockdev_for "$part" "$SLOT")
  mkdir -p "$OUT/$part"
  if [ ! -f "$OUT/$part/SIZE" ]; then
    size=$(su_ blockdev --getsize64 "$dev")
    [[ "$size" =~ ^[0-9]+$ ]] || { log "$part: cannot size $dev ($size)"; continue; }
    echo "$size" > "$OUT/$part/SIZE"
  fi
  size=$(cat "$OUT/$part/SIZE"); want=$(( (size + CHUNK - 1) / CHUNK ))
  for i in $(seq 0 $((want - 1))); do
    n=$(printf %04d "$i")
    [ -f "$OUT/$part/$n.ok" ] && continue
    expect=$(su_ "dd if=$dev bs=$CHUNK skip=$i count=1 2>/dev/null | sha256sum" | awk '{print $1}')
    [ ${#expect} -eq 64 ] || { log "$part: chunk $n hash failed on device; stopping"; exit 1; }
    timeout "$CHUNK_T" adb -s "$SERIAL" exec-out "su -c 'dd if=$dev bs=$CHUNK skip=$i count=1 2>/dev/null | gzip -1'" \
      | gzip -dc > "$OUT/$part/$n.bin" || { log "$part: chunk $n transfer failed; stopping"; exit 1; }
    got=$(sha256sum "$OUT/$part/$n.bin" | awk '{print $1}')
    [ "$got" = "$expect" ] || { log "$part: chunk $n hash mismatch; will retry"; rm -f "$OUT/$part/$n.bin"; exit 1; }
    touch "$OUT/$part/$n.ok"
    log "$part: chunk $((i + 1))/$want ok"
  done
done
assemble
