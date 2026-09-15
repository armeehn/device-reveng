#!/usr/bin/env bash
# Flash a Riposte OS build IN PLACE on the running slot (Virtual A/B: the other slot has no
# logical partitions, learned 2026-09-15). Back the slot up first. Dry-run by default.
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
# Usage: flash.sh --images DIR [--adb S] [--fastboot S] [--yes]
#   --adb is the Wi-Fi adb serial (ip:port) used to read the slot and reboot; --fastboot the
#   USB serial fastbootd presents on the 4PIN port. Either may be omitted when only one device
#   is attached.

set -euo pipefail
IMAGES="" ADB_SERIAL="" FB_SERIAL="" YES=0
while [ $# -gt 0 ]; do
  case "$1" in
    --images) IMAGES=$2; shift 2 ;;
    --adb) ADB_SERIAL=$2; shift 2 ;;
    --fastboot) FB_SERIAL=$2; shift 2 ;;
    --yes) YES=1; shift ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
[ -n "$IMAGES" ] || { echo "need --images DIR" >&2; exit 2; }
for f in system product boot vbmeta; do [ -f "$IMAGES/$f.img" ] || { echo "$IMAGES/$f.img missing" >&2; exit 2; }; done
(cd "$IMAGES" && sha256sum -c --quiet SHA256SUMS) || { echo "SHA256SUMS do not verify" >&2; exit 2; }

ADB=(adb); FB=(fastboot)
[ -n "$ADB_SERIAL" ] && ADB+=(-s "$ADB_SERIAL")
[ -n "$FB_SERIAL" ] && FB+=(-s "$FB_SERIAL")
command -v fastboot >/dev/null || { echo "fastboot not installed" >&2; exit 2; }
run() { printf '+ %s\n' "$*"; [ $YES = 1 ] && "$@"; return 0; }

ACTIVE=$(timeout 30 "${ADB[@]}" shell getprop ro.boot.slot_suffix | tr -d '\r_')
case "$ACTIVE" in a|b) TARGET=$ACTIVE ;; *) echo "cannot read active slot ($ACTIVE)" >&2; exit 1 ;; esac
echo "active slot: $ACTIVE   target (IN PLACE): $TARGET   version: $(grep ^version= "$IMAGES/MANIFEST")"
[ $YES = 1 ] || echo "(dry run; add --yes to execute)"

run "${ADB[@]}" reboot fastboot                       # fastbootd, needed for logical partitions
# The unit comes back on the 4PIN port as a fastboot USB device; wait for it rather than race it.
[ $YES = 1 ] && { echo "waiting for fastbootd on USB (replug the cable if it never appears)"; "${FB[@]}" getvar is-userspace 2>&1 | tail -1 || true; }
# Logical partitions first (fastbootd resizes them); every image the set carries goes, so the
# slot never mixes a system from one build with a vendor from another.
# Stale Virtual A/B snapshots fill super; the vendor's images are a little smaller than ours and
# fastbootd resizes on flash. -S 64M: this cable stalls on larger sparse chunks.
run "${FB[@]}" snapshot-update cancel
run "${FB[@]}" delete-logical-partition "product_$TARGET-cow"
for part in system system_ext product vendor; do
  [ -f "$IMAGES/$part.img" ] && run "${FB[@]}" -S 64M flash "${part}_$TARGET" "$IMAGES/$part.img"
done
run "${FB[@]}" flash "boot_$TARGET" "$IMAGES/boot.img"         # Magisk-patched if the base came off the unit; stock if from an OTA
[ -f "$IMAGES/dtbo.img" ] && run "${FB[@]}" flash "dtbo_$TARGET" "$IMAGES/dtbo.img"
# fastboot's own --disable-verification refused this vbmeta ("AVB_MAGIC at offset 0"); patch the
# flags ourselves and flash plain.
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
for v in vbmeta vbmeta_system; do
  [ -f "$IMAGES/$v.img" ] || continue
  python3 "$(dirname "$0")/vbmeta-disable.py" "$IMAGES/$v.img" "$TMP/$v.img" >/dev/null
  run "${FB[@]}" flash "${v}_$TARGET" "$TMP/$v.img"
done
run "${FB[@]}" reboot
echo "rollback: the same script with --images <backup dir>"
