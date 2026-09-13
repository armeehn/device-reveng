#!/usr/bin/env bash
# Turn an EDL dump (backup.sh output: one raw file per partition, all LUNs) into the base
# directory build.sh expects, without the car being online.
#
#   edl-dump/{super.bin, boot_b.bin, dtbo_b.bin, vbmeta_b.bin, vbmeta_system_b.bin}
#        │ lpunpack.py (LGPL-3.0, fetched by URL with a pinned checksum, not redistributed)
#        ▼
#   base/{system,product,vendor,boot,dtbo,vbmeta,vbmeta_system}.img + .sha256 + BASE-INFO
#
# Usage: from-edl.sh --edl DIR --out DIR [--slot a|b] [--tools DIR]
#   --slot defaults to b (the slot the unit has run since rooting; check BASE-INFO of an adb
#   dump or `getprop ro.boot.slot_suffix` if unsure). Needs python3 and network once.

set -euo pipefail
readonly LPUNPACK_URL=https://raw.githubusercontent.com/unix3dgforce/lpunpack/master/lpunpack.py
readonly LPUNPACK_SHA256=600d1cab2fc7de5127fb287f4e2791718db54efb1102d0f365db0d3ec1ccf62e
readonly LOGICAL="system product vendor"
readonly PHYSICAL="boot dtbo vbmeta vbmeta_system"

EDL="" OUT="" SLOT=b TOOLS=""
while [ $# -gt 0 ]; do
  case "$1" in
    --edl) EDL=$2; shift 2 ;;
    --out) OUT=$2; shift 2 ;;
    --slot) SLOT=$2; shift 2 ;;
    --tools) TOOLS=$2; shift 2 ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
[ -n "$EDL" ] && [ -n "$OUT" ] || { echo "need --edl DIR --out DIR" >&2; exit 2; }
TOOLS=${TOOLS:-$OUT/../tools}
mkdir -p "$OUT" "$TOOLS"
log() { printf '[from-edl] %s\n' "$*" >&2; }

# A partition file from backup.sh may be name.bin or name.img; find either.
find_part() { # name -> path or empty
  local n; for n in "$EDL/$1.bin" "$EDL/$1.img" "$EDL"/lun*/"$1.bin"; do [ -f "$n" ] && { echo "$n"; return; }; done
}

# ---- lpunpack, pinned ---------------------------------------------------------
LP="$TOOLS/lpunpack.py"
if [ ! -f "$LP" ] || ! echo "$LPUNPACK_SHA256  $LP" | sha256sum -c --quiet 2>/dev/null; then
  log "fetching lpunpack.py"
  curl -sSL -o "$LP.tmp" "$LPUNPACK_URL"
  echo "$LPUNPACK_SHA256  $LP.tmp" | sha256sum -c --quiet || { echo "lpunpack.py checksum mismatch; refusing" >&2; rm -f "$LP.tmp"; exit 1; }
  mv "$LP.tmp" "$LP"
fi

# ---- logical partitions out of super -----------------------------------------
SUPER=$(find_part super); [ -n "$SUPER" ] || { echo "super.bin not in $EDL" >&2; exit 1; }
log "super: $SUPER ($(du -h "$SUPER" | cut -f1))"
python3 "$LP" --info "$SUPER" > "$OUT/SUPER-INFO.txt" 2>&1 || true
for p in $LOGICAL; do
  python3 "$LP" -p "${p}_$SLOT" "$SUPER" "$OUT" >/dev/null
  [ -f "$OUT/${p}_$SLOT.img" ] || { echo "${p}_$SLOT not found in super (see SUPER-INFO.txt)" >&2; exit 1; }
  mv "$OUT/${p}_$SLOT.img" "$OUT/$p.img"
  log "$p.img $(du -h "$OUT/$p.img" | cut -f1)"
done

# ---- physical partitions, verbatim -------------------------------------------
for p in $PHYSICAL; do
  src=$(find_part "${p}_$SLOT")
  [ -n "$src" ] || { log "WARNING: ${p}_$SLOT missing in the dump"; continue; }
  cp --reflink=auto "$src" "$OUT/$p.img"
done

(cd "$OUT" && for f in *.img; do sha256sum "$f" > "$f.sha256"; done)
{ echo "slot=_$SLOT"; echo "source=edl:$(basename "$EDL")"; echo "converted=$(date -u +%F)"; } > "$OUT/BASE-INFO"
log "base ready: $OUT"
