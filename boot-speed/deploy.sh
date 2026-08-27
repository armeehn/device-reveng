#!/usr/bin/env bash
# Push the boot-speed scripts to the head unit (must be rooted + adb-connected).
# Run from the laptop when the car is in wifi range OR on the 4PIN USB-A cable.
#   adb connect <ip:port>   (wifi)  — or plug the USB-A<->A data cable
#   ./deploy.sh
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

echo "[*] adb devices:"; adb devices

# stage to /sdcard first (adb push can't write /data/adb directly as shell)
adb push "$HERE/post-fs-data.d__10-boot-perf.sh" /sdcard/10-boot-perf.sh
adb push "$HERE/service.d__90-restore-gov.sh"     /sdcard/90-restore-gov.sh

# move into place as root (su pops a Grant dialog ON THE UNIT SCREEN the first time)
adb shell 'su -c "
  mkdir -p /data/adb/post-fs-data.d /data/adb/service.d
  cp /sdcard/10-boot-perf.sh /data/adb/post-fs-data.d/10-boot-perf.sh
  cp /sdcard/90-restore-gov.sh /data/adb/service.d/90-restore-gov.sh
  chmod 0755 /data/adb/post-fs-data.d/10-boot-perf.sh /data/adb/service.d/90-restore-gov.sh
  rm -f /sdcard/10-boot-perf.sh /sdcard/90-restore-gov.sh
  echo INSTALLED:; ls -l /data/adb/post-fs-data.d/10-boot-perf.sh /data/adb/service.d/90-restore-gov.sh
"'

echo "[*] Done. Reboot the unit to apply:  adb reboot"
echo "    After reboot, check the log:      adb shell su -c 'cat /data/local/tmp/bootperf.log'"
echo "    Rollback: adb shell su -c 'rm /data/adb/post-fs-data.d/10-boot-perf.sh /data/adb/service.d/90-restore-gov.sh' then reboot"
