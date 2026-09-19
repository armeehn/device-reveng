#!/system/bin/sh
# Hold /dev/zxw_io open and drain it, as the vendor's eventcenter does for its touch keys.
# The reads block between key packets; the process lives as long as the boot does.
[ "$(getprop ro.riposte.os.car_owner)" = 1 ] || exit 0
[ -c /dev/zxw_io ] || exit 0
exec cat /dev/zxw_io > /dev/null
