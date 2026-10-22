#!/usr/bin/env bash
# firmware.sh against two fake stock partitions: the files land where the kernel asks for them,
# under lower-case names, with sums; a dump without the WLAN firmware fails instead of
# shipping a rootfs whose Wi-Fi can never start. Needs mtools.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
fail() { echo "FAIL: $*"; exit 1; }

readonly IMG_KIB=2048
export MTOOLS_SKIP_CHECK=1

# A vfat image holding image/<name> for each name given; content is the name itself.
mkimg() { # img name...
  local img=$1; shift
  truncate -s "${IMG_KIB}K" "$img"
  mformat -i "$img" ::
  mmd -i "$img" ::/image
  for n in "$@"; do
    echo "$n" > "$T/$n"
    mcopy -i "$img" "$T/$n" "::/image/$n"
    rm "$T/$n"
  done
}

mkimg "$T/modem.img" modem.mdt modem.b00 modem.b01 wlanmdsp.mbn BDWLAN.BIN modemr.jsn mba.mbn qdsp6m.qdb
mkimg "$T/bluetooth.img" crbtfw21.tlv crnv21.bin apbtfw11.tlv apnv11.bin tpbtfw00.tlv
mkdir -p "$T/lfw/ath10k/WCN3990/hw1.0"
echo fw5 > "$T/lfw/ath10k/WCN3990/hw1.0/firmware-5.bin"

GT6_MODEM_IMG="$T/modem.img" GT6_BT_IMG="$T/bluetooth.img" RIPOSTE_OS_ENV=/dev/null \
  "$HERE/firmware.sh" "$T/out" "$T/lfw" > "$T/log"
FW="$T/out/lib/firmware"

for f in qcom/sm6125/choiceway/gt6eau/{modem.mdt,modem.b00,modem.b01,wlanmdsp.mbn,modemr.jsn} \
         ath10k/WCN3990/hw1.0/{board.bin,firmware-5.bin} qca/{crbtfw21.tlv,crnv21.bin,apbtfw11.tlv,apnv11.bin}; do
  [ -f "$FW/$f" ] || fail "$f not staged"
done
[ "$(cat "$FW/ath10k/WCN3990/hw1.0/board.bin")" = BDWLAN.BIN ] || fail "board.bin is not the stock bdwlan"
# Only what the kernel loads: no MBA (PAS boots the modem without it), no other chip's BT.
[ ! -e "$FW/qcom/sm6125/choiceway/gt6eau/mba.mbn" ] || fail "mba.mbn staged"
[ ! -e "$FW/qca/tpbtfw00.tlv" ] || fail "foreign BT firmware staged"
(cd "$FW" && sha256sum -c --quiet SHA256SUMS-gt6eau) || fail "sums do not match"
[ "$(wc -l < "$FW/SHA256SUMS-gt6eau")" -eq 11 ] || fail "sums list $(wc -l < "$FW/SHA256SUMS-gt6eau") files, want 11"

# A modem dump without wlanmdsp.mbn must stop the build.
mkimg "$T/modem-nowlan.img" modem.mdt modem.b00 bdwlan.bin
if GT6_MODEM_IMG="$T/modem-nowlan.img" GT6_BT_IMG="$T/bluetooth.img" RIPOSTE_OS_ENV=/dev/null \
  "$HERE/firmware.sh" "$T/out2" > "$T/log2" 2>&1; then
  fail "a modem dump without wlanmdsp.mbn passed"
fi
grep -q wlanmdsp "$T/log2" || fail "the failure does not name wlanmdsp.mbn"

echo "firmware.sh: OK"
