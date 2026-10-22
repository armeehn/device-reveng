#!/usr/bin/env bash
# Stage the Wi-Fi, Bluetooth and modem firmware the mainline kernel loads, from the unit's own
# stock partitions, as a /lib/firmware tree for the gt6 rootfs. The blobs are Qualcomm's and
# the vendor's: they never enter this repository, so this runs at build time against a dump.
#
#   modem.img (vfat) /image/ ─┬─ modem.mdt modem.bNN wlanmdsp.mbn *.jsn ─► qcom/sm6125/choiceway/gt6eau/
#                             └─ bdwlan.bin ─────────────────────────────► ath10k/WCN3990/hw1.0/board.bin
#   bluetooth.img (vfat) /image/ ── crbtfw*.tlv crnv*.bin apbtfw*.tlv apnv*.bin ─► qca/
#   LINUX_FIRMWARE ── ath10k/WCN3990/hw1.0/firmware-5.bin (feature flags only; the code runs in
#                     the modem, from wlanmdsp.mbn)
#
# Usage: os/mainline/firmware.sh OUT_DIR [LINUX_FIRMWARE]
#   OUT_DIR         gets lib/firmware/… and lib/firmware/SHA256SUMS-gt6eau
#   LINUX_FIRMWARE  a linux-firmware checkout (or /usr/lib/firmware with firmware-atheros)
# The dumps (os/dump-base.sh --parts "modem bluetooth", then assemble) are read from
# $RIPOSTE_SHARE/os/base/ (site file, os/riposte-os.env.example), or GT6_MODEM_IMG and
# GT6_BT_IMG name them directly. Needs mtools: the images are read without mounting.
set -euo pipefail

# Where the kernel looks: remoteproc firmware-name in the board DTS, ath10k's and btqca's
# fixed paths. tqftpserv serves the modem's own requests (wlanmdsp.mbn) from the modem's dir.
readonly MODEM_DIR=qcom/sm6125/choiceway/gt6eau
readonly ATH10K_DIR=ath10k/WCN3990/hw1.0
readonly QCA_DIR=qca
readonly SUMS=SHA256SUMS-gt6eau
# Stock keeps every blob in the partition's image/ folder (/vendor/firmware_mnt/image,
# /vendor/bt_firmware/image on Android).
readonly IMAGE_DIR=image
readonly MODEM_FILES='^(modem\.mdt|modem\.b[0-9]+|wlanmdsp\.mbn|[a-z0-9_]+\.jsn)$'
readonly BDF_FILE=bdwlan.bin
readonly BT_FILES='^(crbtfw|apbtfw)[0-9]+\.tlv$|^(crnv|apnv)[0-9]+[a-z]?\.bin$'
readonly ATH10K_FW=firmware-5.bin

[ $# -ge 1 ] && [ $# -le 2 ] || { sed -n 2,17p "$0"; exit 2; }
OUT=$1 LINUX_FW=${2:-}

RIPOSTE_OS_ENV=${RIPOSTE_OS_ENV:-/etc/riposte-os.env}
# shellcheck source=/dev/null
[ -r "$RIPOSTE_OS_ENV" ] && . "$RIPOSTE_OS_ENV"
MODEM_IMG=${GT6_MODEM_IMG:-${RIPOSTE_SHARE:+$RIPOSTE_SHARE/os/base/modem.img}}
BT_IMG=${GT6_BT_IMG:-${RIPOSTE_SHARE:+$RIPOSTE_SHARE/os/base/bluetooth.img}}

die() { echo "firmware.sh: $*" >&2; exit 1; }
command -v mcopy >/dev/null || die "mtools is missing (apt install mtools)"
[ -n "$MODEM_IMG" ] && [ -f "$MODEM_IMG" ] || die "no modem image: set RIPOSTE_SHARE or GT6_MODEM_IMG (${MODEM_IMG:-unset})"
[ -n "$BT_IMG" ] && [ -f "$BT_IMG" ] || die "no bluetooth image: set RIPOSTE_SHARE or GT6_BT_IMG (${BT_IMG:-unset})"

FW="$OUT/lib/firmware"
mkdir -p "$FW/$MODEM_DIR" "$FW/$ATH10K_DIR" "$FW/$QCA_DIR"

# The names in IMG's image/ folder that match the regex, as stored (FAT keeps the case it was
# given, but a match must not depend on it).
names() { # img regex
  MTOOLS_SKIP_CHECK=1 mdir -b -i "$1" "::/$IMAGE_DIR" 2>/dev/null \
    | sed 's#.*/##' | grep -iE "$2" || true
}

# Copy one file out of a FAT image under a lower-case name: the kernel asks for lower case.
take() { # img name dest_dir [dest_name]
  local dest=${4:-$(tr 'A-Z' 'a-z' <<<"$2")}
  MTOOLS_SKIP_CHECK=1 mcopy -n -o -i "$1" "::/$IMAGE_DIR/$2" "$FW/$3/$dest"
  echo "  $3/$dest  <- $(basename "$1"):/$IMAGE_DIR/$2"
}

echo "modem: $MODEM_IMG"
found=$(names "$MODEM_IMG" "$MODEM_FILES")
grep -qix 'modem.mdt' <<<"$found" || die "no $IMAGE_DIR/modem.mdt in $MODEM_IMG"
grep -qix 'wlanmdsp.mbn' <<<"$found" || die "no $IMAGE_DIR/wlanmdsp.mbn in $MODEM_IMG"
for n in $found; do take "$MODEM_IMG" "$n" "$MODEM_DIR"; done

# The board data file: calibration for this unit's antennas and front end. Shipped as
# board.bin, ath10k's fallback when board-2.bin has no entry for the chip's board id.
bdf=$(names "$MODEM_IMG" "^${BDF_FILE//./\\.}$")
[ -n "$bdf" ] || die "no $IMAGE_DIR/$BDF_FILE in $MODEM_IMG"
take "$MODEM_IMG" "$bdf" "$ATH10K_DIR" board.bin

echo "bluetooth: $BT_IMG"
found=$(names "$BT_IMG" "$BT_FILES")
[ -n "$found" ] || die "no crbtfw/apbtfw firmware in $BT_IMG:/$IMAGE_DIR"
for n in $found; do take "$BT_IMG" "$n" "$QCA_DIR"; done

if [ -n "$LINUX_FW" ]; then
  [ -f "$LINUX_FW/$ATH10K_DIR/$ATH10K_FW" ] || die "no $ATH10K_DIR/$ATH10K_FW in $LINUX_FW"
  install -m 644 "$LINUX_FW/$ATH10K_DIR/$ATH10K_FW" "$FW/$ATH10K_DIR/"
  echo "  $ATH10K_DIR/$ATH10K_FW  <- linux-firmware"
else
  echo "  $ATH10K_DIR/$ATH10K_FW  not staged: give LINUX_FIRMWARE, or the rootfs must carry it"
fi

# One sum per staged file, for the bench log and the README's record of what shipped.
(cd "$FW" && find . -type f ! -name "$SUMS" | sed 's#^\./##' | sort | xargs sha256sum > "$SUMS")
echo "sums: $FW/$SUMS ($(wc -l < "$FW/$SUMS") files)"
