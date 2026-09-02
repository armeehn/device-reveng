#!/usr/bin/env bash
# ROOT the GT6-CAR head unit via TWRP + Magisk (bootloader already unlocked).
# PRECONDITION: a FULL EDL BACKUP already exists (run backup.sh first!).
# FLOW: adb (wifi) -> reboot to bootloader -> fastboot boot TWRP(ADB) -> install Magisk over adb.
# TWRP is booted in RAM only (fastboot boot, NOT flash) -> nothing permanent until Magisk installs.
set -uo pipefail

# Workspace holding the TWRP image and Magisk zip (see README "Getting the vendor files").
# Override with: RAV4_HOME=/path ./root.sh
ROOT="${RAV4_HOME:-$HOME/rav4-headunit}"
TWRP="$ROOT/run/recovery_ADB.img"      # USB-device/ADB variant
MAGISK_ZIP="$ROOT/run/Magisk.zip"
ADB_TARGET="${1:-}"                    # optional: pass ip:port of the wifi-adb connection

SUDO=""; [ "$(id -u)" -ne 0 ] && SUDO="sudo"   # fastboot USB needs root on Linux
FB="$SUDO fastboot"

# Preflight: refuse to start a root flow with a missing image. Step 1 pushes
# Magisk and step 3 boots TWRP; discovering either is absent mid-flow leaves the
# unit sitting in the bootloader.
for req in "$TWRP:TWRP recovery_ADB.img" "$MAGISK_ZIP:Magisk.zip"; do
  path="${req%%:*}"; what="${req#*:}"
  [ -e "$path" ] && continue
  echo "!! missing $what"
  echo "   expected at: $path"
  echo "   See README section 'Getting the vendor files'. Set RAV4_HOME to relocate."
  exit 1
done

echo "=== 0. confirm we have an adb device (wifi) ==="
adb devices
DEV="${ADB_TARGET:-$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')}"
[ -z "$DEV" ] && { echo "!! no adb device. Connect wifi-adb first (adb connect ip:port)."; exit 1; }
echo "   using $DEV"

echo; echo "=== 1. push Magisk to the device (so TWRP can install it) ==="
adb -s "$DEV" push "$MAGISK_ZIP" /sdcard/Magisk.zip

echo; echo "=== 2. reboot to bootloader (fastboot) ==="
adb -s "$DEV" reboot bootloader
echo "   >> now connect the 4PIN USB cable to the laptop. Waiting for fastboot..."
$FB wait-for-devices 2>/dev/null || sleep 8
$FB devices

echo; echo "=== 3. BOOT (not flash) the ADB TWRP in RAM ==="
$FB boot "$TWRP" || { echo "!! fastboot boot failed. Check 4PIN cable / that fastboot sees the device."; exit 2; }
echo "   >> TWRP is booting. It has ADB. Waiting for TWRP adb (sideload/recovery)..."
sleep 12
adb devices

echo; echo "=== 4. install Magisk from inside TWRP over adb ==="
echo "   NOTE: TWRP touchscreen is buggy on this unit; drive it via adb here."
# Preferred: TWRP OpenRecoveryScript to install the zip, then reboot.
adb shell 'echo "install /sdcard/Magisk.zip" > /cache/recovery/openrecoveryscript' 2>/dev/null \
  || adb shell 'echo "install /sdcard/Magisk.zip" > /tmp/openrecoveryscript' 2>/dev/null \
  || echo "   (could not write ORS automatically — fall back to: adb sideload $MAGISK_ZIP, or use recovery_KB.img + USB mouse)"
echo
echo ">> If ORS was written, run:  adb shell twrp install /sdcard/Magisk.zip   (or reboot recovery to apply)"
echo ">> Alternatively:            adb sideload $MAGISK_ZIP"
echo ">> Then:                     adb reboot"
echo ">> After boot, open the Magisk app -> it should show installed. If it shows 'requires additional setup',"
echo "   do Magisk -> Install -> Direct Install."
echo
echo ">> This script intentionally stops before the final reboot so you can confirm each TWRP step."
