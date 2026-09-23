#!/system/bin/sh
# The one USB controller's role from one property, so the launcher sets
# persist.riposte.usb.role=host|peripheral and nothing else. init runs this on every change
# and at boot_completed (car-owner builds), the #279 camera-mode pattern:
#
#   setprop persist.riposte.usb.role host
#     └─ init: on property:persist.riposte.usb.role=* ──► this script (root)
#          └─ printf host > /sys/devices/platform/soc/4e00000.ssusb/mode
#
#   host        the car's sockets: CANable, USB media, wired CarPlay
#   peripheral  adb over the 4PIN pigtail; the car's sockets are dead
#
# Stock wrote the same two words to the same node (eventcenter AccEvent/Utils.java:47,
# :192-197 openAdb(z ? "peripheral" : "host"); settings AdbHelps.java:5-13). An unset property
# means the image decides: peripheral on a bench image (ro.riposte.os.bench=1, updated over the
# pigtail from zero), host in the car. The vendor init writes host at boot_completed too
# (riposte.rc), so the boot call waits it out with `boot` as $1 before applying.
PROP=persist.riposte.usb.role
BENCH_PROP=ro.riposte.os.bench
USB_CONFIG_PROP=sys.usb.config
SYSFS=${RIPOSTE_SYSFS:-}   # test hook: a prefix in front of the node path
NODE=/sys/devices/platform/soc/4e00000.ssusb/mode
VENDOR_HOST_WRITE_S=${RIPOSTE_USB_BOOT_WAIT_S:-5}   # riposte-usbadb.sh waits the same
TAG=riposte-usb

say() {
  log -t "$TAG" "$1"
  echo "$TAG: $1"
}

role=$(getprop "$PROP")
if [ -z "$role" ]; then
  if [ "$(getprop "$BENCH_PROP")" = 1 ]; then role=peripheral; else role=host; fi
  say "$PROP unset: image default $role"
fi
case "$role" in
  host|peripheral) ;;
  *) say "ignored $PROP='$role': want host|peripheral"; exit 1 ;;
esac

[ "${1:-}" = boot ] && sleep "$VENDOR_HOST_WRITE_S"

node=$SYSFS$NODE
if [ ! -e "$node" ]; then
  say "no $NODE on this board, nothing written"
  exit 1
fi

now=$(cat "$node" 2>/dev/null)
if [ "$now" = "$role" ]; then
  say "port already $role"
  exit 0
fi

# The adb gadget must be configured before the port faces a computer (riposte-usbadb.sh:7).
[ "$role" = peripheral ] && setprop "$USB_CONFIG_PROP" adb

if ! printf %s "$role" > "$node"; then
  say "write $role to $NODE failed (was '$now')"
  exit 1
fi
say "port ${now:-?} -> $role"
