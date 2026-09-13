#!/usr/bin/env bash
# Flash a Riposte OS build to the INACTIVE slot and switch to it. Dry-run by
# default: prints every command, runs nothing until --yes.
#
#   active slot _b (stock, daily driver)     inactive slot _a  ◄── the whole matched set:
#                                                system, system_ext, product, vendor, boot,
#                                                dtbo, vbmeta, vbmeta_system; set_active a
#   rollback: fastboot set_active b   (or hold the unit in fastboot and re-run this with --slot b)
#
# Runs on the laptop at the car, over the 4PIN USB port (USB-A to USB-A data
# cable; see STATUS.md). Needs adb + fastboot. /data is kept: the build is the
# stock system with additions, same fingerprint, same keys.
#
# Usage: flash.sh --images DIR [--serial S] [--yes]

set -euo pipefail
IMAGES="" SERIAL="" YES=0
while [ $# -gt 0 ]; do
  case "$1" in
    --images) IMAGES=$2; shift 2 ;;
    --serial) SERIAL=$2; shift 2 ;;
    --yes) YES=1; shift ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
[ -n "$IMAGES" ] || { echo "need --images DIR" >&2; exit 2; }
for f in system product boot vbmeta; do [ -f "$IMAGES/$f.img" ] || { echo "$IMAGES/$f.img missing" >&2; exit 2; }; done
(cd "$IMAGES" && sha256sum -c --quiet SHA256SUMS) || { echo "SHA256SUMS do not verify" >&2; exit 2; }

ADB=(adb); FB=(fastboot)
[ -n "$SERIAL" ] && { ADB+=(-s "$SERIAL"); FB+=(-s "$SERIAL"); }
run() { printf '+ %s\n' "$*"; [ $YES = 1 ] && "$@"; return 0; }

ACTIVE=$(timeout 30 "${ADB[@]}" shell getprop ro.boot.slot_suffix | tr -d '\r_')
case "$ACTIVE" in a) TARGET=b ;; b) TARGET=a ;; *) echo "cannot read active slot ($ACTIVE)" >&2; exit 1 ;; esac
echo "active slot: $ACTIVE   target (inactive): $TARGET   version: $(grep ^version= "$IMAGES/MANIFEST")"
[ $YES = 1 ] || echo "(dry run; add --yes to execute)"

run "${ADB[@]}" reboot fastboot                       # fastbootd, needed for logical partitions
run "${FB[@]}" getvar is-userspace
# Logical partitions first (fastbootd resizes them); every image the set carries goes, so the
# slot never mixes a system from one build with a vendor from another.
for part in system system_ext product vendor; do
  [ -f "$IMAGES/$part.img" ] && run "${FB[@]}" flash "${part}_$TARGET" "$IMAGES/$part.img"
done
run "${FB[@]}" flash "boot_$TARGET" "$IMAGES/boot.img"         # Magisk-patched if the base came off the unit; stock if from an OTA
[ -f "$IMAGES/dtbo.img" ] && run "${FB[@]}" flash "dtbo_$TARGET" "$IMAGES/dtbo.img"
run "${FB[@]}" --disable-verity --disable-verification flash "vbmeta_$TARGET" "$IMAGES/vbmeta.img"
[ -f "$IMAGES/vbmeta_system.img" ] && run "${FB[@]}" --disable-verity --disable-verification flash "vbmeta_system_$TARGET" "$IMAGES/vbmeta_system.img"
run "${FB[@]}" set_active "$TARGET"
run "${FB[@]}" reboot
echo "rollback if it does not come up: fastboot set_active $ACTIVE"
