#!/system/bin/sh
# The OEM projection daemon (CarPlay) on car-owner builds, from /system/riposte/zlink (lifted
# from the owner's stock image by build.sh step 3b). What the stock zlink5.sh did before the
# daemon, minus its restart loop: init restarts the service itself.
#
#   z-link ──dlopen──▶ libzjL10001.so ──127.0.0.1:1777/1666/1888/1999──▶ com.ripostelabs.projection
#          └─ iap.gs0 + ncm.gs1 gadget functions for the wired iPhone (role switch to device)
[ "$(getprop ro.riposte.os.car_owner)" = 1 ] || exit 0
ZLINK=/system/riposte/zlink
[ -x "$ZLINK/bin/z-link" ] || exit 0

# The daemon's UDP receive buffer for the AirPlay streams, and the gadget's power draw, as stock.
echo 86364000 > /proc/sys/net/core/rmem_max
echo 1 > /config/usb_gadget/g1/configs/b.1/MaxPower
mkdir /config/usb_gadget/g1/functions/iap.gs0 2>/dev/null
mkdir /config/usb_gadget/g1/functions/ncm.gs1 2>/dev/null

export LD_LIBRARY_PATH=$ZLINK/lib
export PATH="$ZLINK/bin:$PATH"
exec "$ZLINK/bin/z-link" -c zhuoxw
