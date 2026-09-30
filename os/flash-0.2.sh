#!/bin/bash
# Flash the Riposte OS 0.2 image set on the head unit from zero, over the USB pigtail.
#
#   curl -sfO https://launcher.hq.ripostelabs.xyz/car-update/os/flash-0.2.sh && bash flash-0.2.sh
#
# Start it at home on the LAN: step 1 pulls ~3.5 GB from x (resumable), then it waits for Enter.
# Take zero to the car, plug the unit in, press Enter. Away from home the network steps time out
# fast and use what an earlier run staged on zero. Then: reboot to fastbootd, flash slot b (no
# wipe, your data stays), verify every
# partition by hash, repair flipped blocks if the pigtail lied. Safe to re-run at any point:
# each step checks before it acts. Log: ~/rav4-headunit/os/flash-<stamp>.log
set -uo pipefail

BASE=https://launcher.hq.ripostelabs.xyz/car-update/os
SRC="sasha@x.hq.ripostelabs.xyz:/z1-pool/share/carlauncher/os/0.2-bench/"
OS_DIR="$HOME/rav4-headunit/os"
DIR="$OS_DIR/0.2-bench"
# The unit's adb serial changed once already; with one device attached, use that one.
UNIT=${UNIT:-$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device"{print $1}' | head -1)}
UNIT=${UNIT:-da40e9ac}
FASTBOOTD_USB="18d1:4ee0"
ADB_USB="18d1:4ee7"
LOG="$OS_DIR/flash-$(date +%Y%m%d-%H%M%S).log"

mkdir -p "$DIR"
exec > >(tee "$LOG") 2>&1
say() { printf '\n== %s\n' "$*"; }
die() { printf '\nSTOP: %s\n' "$*"; exit 1; }
NET_S=10   # a network step gives up after this, so the car never waits on home Wi-Fi
wait_usb() { for i in $(seq 1 "$2"); do lsusb | grep -qE "$1" && return 0; sleep 5; done; return 1; }

# 0. The bench scripts, fresh from the repo's main.
say "scripts"
for f in fastboot-flash-set.sh bench-verify.sh bench-blockfix.sh vbmeta-disable.py; do
    if curl -sf --connect-timeout 5 --max-time "$NET_S" -o "$OS_DIR/$f.part" "$BASE/$f"; then
        mv "$OS_DIR/$f.part" "$OS_DIR/$f"
        continue
    fi
    [ -f "$OS_DIR/$f" ] || die "cannot fetch $f and no staged copy (run it at home first)"
    echo "offline: staged $f"
done

# 1. The image set, resumable; the sums prove it arrived whole.
say "images from x"
rsync -a --partial --info=progress2 -e "ssh -o BatchMode=yes -o ConnectTimeout=5" "$SRC" "$DIR/" ||
    echo "offline: using the staged image set"
[ -f "$DIR/SHA256SUMS" ] || die "no image set staged on zero (run it at home first)"
(cd "$DIR" && sha256sum -c --quiet SHA256SUMS) || die "checksums do not match after the copy"
want=$(sed -n 's/^version=//p' "$DIR/MANIFEST")
echo "set $want complete"

# 2. The unit. Not on USB yet: wait for the owner to plug it in, however long the drive takes.
say "unit"
if ! lsusb | grep -qE "$ADB_USB|$FASTBOOTD_USB"; then
    echo "image staged. At the car: engine on, unit booted, pigtail plugged into zero."
    read -rp "Press Enter when the unit is plugged in... " _
    wait_usb "$ADB_USB|$FASTBOOTD_USB" 6 || { usbreset "$ADB_USB" >/dev/null 2>&1; wait_usb "$ADB_USB|$FASTBOOTD_USB" 6; }
fi
if lsusb | grep -q "$ADB_USB"; then
    have=$(adb -s "$UNIT" shell getprop ro.riposte.os.version 2>/dev/null | tr -d '\r')
    echo "unit runs ${have:-unknown}"
    if [ "$have" = "$want" ]; then
        echo "already flashed"
        exit 0
    fi
    adb -s "$UNIT" reboot fastboot
elif lsusb | grep -q "$FASTBOOTD_USB"; then
    echo "unit is in fastbootd already"
else
    die "no head unit on USB (plug the pigtail in, unit booted)"
fi
wait_usb "$FASTBOOTD_USB" 24 || die "fastbootd never enumerated"

# 3. Flash. A stalled write needs a port reset first (BENCH.md).
say "flash slot b"
usbreset "$FASTBOOTD_USB" >/dev/null 2>&1
OS_DIR="$OS_DIR" bash "$OS_DIR/fastboot-flash-set.sh" "$DIR" 2>&1 | grep -v '^Sending\|^Writing' | tail -14
grep -q "FLASH-SET DONE" "$LOG" || die "flash failed; the unit is still in fastbootd: rerun this script"

# 4. Prove the bytes; patch the blocks the pigtail flipped rather than reflash.
say "verify"
wait_usb "$ADB_USB" 60 || die "the unit did not come back on adb after the flash"
sleep 15
if ! UNIT="$UNIT" bash "$OS_DIR/bench-verify.sh" "$DIR"; then
    say "blockfix"
    UNIT="$UNIT" bash "$OS_DIR/bench-blockfix.sh" "$DIR"
    UNIT="$UNIT" bash "$OS_DIR/bench-verify.sh" "$DIR" || die "still mismatching after blockfix; run it again"
fi

say "result"
echo "flashed and verified $want; the unit is booting it. Log: $LOG"
