#!/system/bin/sh
# /dev/ttyHS1 <-> 127.0.0.1:5588 for McuOwner on car-owner builds (riposte.mcu.link=tcp:...).
#
#   MCU ──ttyHS1──▶ nc stdin ──▶ socket ──▶ TcpLink ──▶ McuOwner
#   MCU ◀──ttyHS1── nc stdout ◀── socket ◀── TcpLink ◀── McuOwner
#
# toybox nc serves one connection at a time and exits when it closes, so the loop re-listens;
# the launcher reconnects on its own. The line settings are the vendor's (115200 8N1 raw).
[ "$(getprop ro.riposte.os.car_owner)" = 1 ] || exit 0
[ -c /dev/ttyHS1 ] || exit 0
stty -F /dev/ttyHS1 115200 raw -echo -echoe -echok
while true; do
    toybox nc -l -s 127.0.0.1 -p 5588 < /dev/ttyHS1 > /dev/ttyHS1
    sleep 1
done
