#!/system/bin/sh
# One line per boot, appended to the ring's boots.txt and sent to logcat as well, so a
# pulled ring says which build, which boot and which decoder mode produced its lines. Runs at
# boot_completed (the clock is set and pm answers by then), after riposte_logring made the
# directory; without the directory there is no ring to mark.
#
#   ts=2026-09-23T14:02:11-0700 boot=3f1c9a2e os=0.2.3 build=... bench=0 launcher=712 camera_mode=3 usb_role=
[ "$(getprop ro.riposte.os.car_owner)" = 1 ] || exit 0
DIR=/data/riposte/log
OUT=$DIR/boots.txt
TAG=riposte-logring
LAUNCHER=com.ripostelabs.carlauncher
[ -d "$DIR" ] || exit 0

boot=$(cut -c1-8 /proc/sys/kernel/random/boot_id)
launcher=$(dumpsys package $LAUNCHER 2>/dev/null | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1)
line="ts=$(date +%Y-%m-%dT%H:%M:%S%z) boot=$boot os=$(getprop ro.riposte.os.version) build=$(getprop ro.build.display.id) bench=$(getprop ro.riposte.os.bench) launcher=${launcher:-none} camera_mode=$(getprop persist.riposte.camera.mode) usb_role=$(getprop persist.riposte.usb.role)"

umask 027
echo "$line" >> "$OUT"
log -t "$TAG" "boot $line"
