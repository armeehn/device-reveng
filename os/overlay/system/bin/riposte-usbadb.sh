#!/system/bin/sh
# Bring adb up on the 4PIN USB during the boot animation (car-owner builds only). The vendor
# init writes `host` to the same node when the animation starts, so wait it out, then ask
# for the adb gadget and switch the port to peripheral. boot_completed hands it back to host.
[ "$(getprop ro.riposte.os.car_owner)" = 1 ] || exit 0
sleep 5
setprop sys.usb.config adb
sleep 1
echo peripheral > /sys/devices/platform/soc/4e00000.ssusb/mode
