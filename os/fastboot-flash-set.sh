#!/usr/bin/env bash
# Flash an image set in place on slot b from fastbootd (`adb reboot fastboot` first; the unit
# then shows as 18d1:4ee0 on the 4PIN USB). Runs on the laptop.
#
# Usage: fastboot-flash-set.sh IMAGE_DIR [wipe]
#   wipe erases userdata and metadata (see edl-write-set.sh for why 0.1 <-> 0.2 needs it).
set -uo pipefail
I=$1; WIPE=${2:-}
OS_DIR=${OS_DIR:-$HOME/rav4-headunit/os}
readonly SLOT=b
readonly SPARSE_MAX=64M     # this cable stalls on bigger sparse chunks
readonly SHRINK_BYTES=4096
timeout 60 fastboot getvar is-userspace 2>&1 | head -1 | grep -q "is-userspace: yes" || { echo "not in fastbootd"; exit 1; }
if [ "$WIPE" = wipe ]; then
  fastboot erase userdata 2>&1 | tail -1
  fastboot erase metadata 2>&1 | tail -1
fi
fastboot snapshot-update cancel >/dev/null 2>&1
fastboot delete-logical-partition product_$SLOT-cow >/dev/null 2>&1
# fastbootd grows one partition at a time inside super: shrink every logical partition to a
# token size first, or a big new system cannot grow past the old product (2026-09-18).
for p in system system_ext product vendor; do
  fastboot resize-logical-partition ${p}_$SLOT $SHRINK_BYTES 2>&1 | grep -qE "FAILED" && { echo "shrink $p FAILED"; exit 1; }
done
for p in system system_ext product vendor; do
  out=$(fastboot -S $SPARSE_MAX flash ${p}_$SLOT "$I/$p.img" 2>&1)
  if grep -qE "FAILED|error" <<<"$out"; then echo "$p FAILED:"; grep -E "FAILED|error" <<<"$out"; exit 1; fi
  echo "$p ok"
done
flash() { fastboot flash "$1" "$2" 2>&1 | grep -qE "FAILED" && { echo "$1 FAILED"; exit 1; }; echo "$1 ok"; }
flash boot_$SLOT "$I/boot.img"
flash dtbo_$SLOT "$I/dtbo.img"
python3 "$OS_DIR/vbmeta-disable.py" "$I/vbmeta.img" /tmp/vbmeta-fb.img >/dev/null
flash vbmeta_$SLOT /tmp/vbmeta-fb.img
flash vbmeta_system_$SLOT "$I/vbmeta_system.img"
fastboot reboot 2>&1 | tail -1
echo "FLASH-SET DONE $(date +%T)"
