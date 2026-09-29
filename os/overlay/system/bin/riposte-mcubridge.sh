#!/system/bin/sh
# /dev/ttyHS1 <-> 127.0.0.1:5588 for McuOwner on car-owner builds (riposte.mcu.link=tcp:...).
#
#   MCU ──ttyHS1──▶ cat ──▶ socket ──▶ TcpLink ──▶ McuOwner
#   MCU ◀──ttyHS1── cat ◀── socket ◀── TcpLink ◀── McuOwner
#
# toybox nc serves one connection at a time and exits when it closes, so the loop re-listens;
# the launcher reconnects on its own. The line settings are the vendor's (115200 8N1 raw).
# The tty opens per connection, never before the listen: while it is open the UART holds its
# qup_uart wakeup source and the kernel cannot suspend ("active wakeup source:
# 4c80000.qcom,qup_uart" on every ACC-off sleep, car 2026-09-28). McuSleepWake closes the link
# 1.5 s after ACC off, as stock's eventcenter closed the port (EventService.java:3561, msg 291).
[ "$(getprop ro.riposte.os.car_owner)" = 1 ] || exit 0
[ -c /dev/ttyHS1 ] || exit 0
while true; do
    toybox nc -l -s 127.0.0.1 -p 5588 /system/bin/sh -c \
        'stty -F /dev/ttyHS1 115200 raw -echo -echoe -echok; cat /dev/ttyHS1 & r=$!; cat > /dev/ttyHS1; kill $r'
    sleep 1
done
