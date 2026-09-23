#!/system/bin/sh
# Always-on diagnostics recorder for car-owner builds: every INTERVAL_S one line per probe,
# appended to a per-boot file under DIR, rotated by count. Lines are `ts=<epoch> probe=<name>
# k=v k=v ...` with spaces in values replaced by `_`, so awk/grep and os/diag/check.py both
# read them. logcat itself is persisted by riposte-logring.sh under /data/riposte/log.
#
#   riposte-diag.sh ──30 s──▶ /data/misc/riposte/diag/diag-<boot>.log   ◀── rav4 car diag pull
#                                                                          ◀── os/diag/check.py
# Each probe answers one question of the car install: does the MCU bridge listen and does
# the launcher own the MCU; is the 4PIN port a host and what sits on it (CANable); does audio
# play and where; what the tuner, the camera, CarPlay and Bluetooth report.
[ "$(getprop ro.riposte.os.car_owner)" = 1 ] || exit 0
DIR=/data/misc/riposte/diag
INTERVAL_S=30
KEEP_FILES=40
USB_MODE=/sys/devices/platform/soc/4e00000.ssusb/mode
MCU_PORT=5588
LOG_TAGS="McuOwner:I CarService:I TunerHub:I ReverseCamera:I SysVar:I BtCarKit:I Projection:I btopt:D"

mkdir -p "$DIR" && chmod 700 "$DIR"
boot=$(cat /proc/sys/kernel/random/boot_id | cut -c1-8)
OUT=$DIR/diag-$(date +%Y%m%d-%H%M%S)-$boot.log
ls -t "$DIR"/diag-*.log 2>/dev/null | tail -n +"$KEEP_FILES" | xargs -r rm -f

flat() { tr -s ' \t\n' '___' | sed 's/^_*//; s/_*$//'; }
line() { echo "ts=$(date +%s) up=$(cut -d. -f1 /proc/uptime) $*" >> "$OUT"; }
last_log() { logcat -d -t 1 -s "$1" 2>/dev/null | tail -n 1 | cut -c1-200 | flat; }

# Sockets are /proc/net/tcp rows: local address in hex, state 0A = LISTEN, 01 = ESTABLISHED.
mcu_sock() {
  hexport=$(printf '%04X' $MCU_PORT)
  listen=$(grep -c ":$hexport 00000000:0000 0A" /proc/net/tcp)
  est=$(grep -c ":$hexport [0-9A-F]*:[0-9A-F]* 01" /proc/net/tcp)
  echo "listen=$listen established=$est"
}

probe_mcu() {
  line "probe=mcu tty=$([ -c /dev/ttyHS1 ] && echo 1 || echo 0) bridge=$(getprop init.svc.riposte_mcubridge) $(mcu_sock)" \
       "zxwio=$(getprop init.svc.riposte_zxwio) link=$(getprop riposte.mcu.link) owner_log=$(last_log McuOwner:I)"
}

probe_usb() {
  devs=""
  for d in /sys/bus/usb/devices/*; do
    [ -f "$d/idVendor" ] || continue
    devs="$devs,$(cat "$d/idVendor"):$(cat "$d/idProduct")=$(cat "$d/product" 2>/dev/null | flat)"
  done
  line "probe=usb mode=$(cat $USB_MODE 2>/dev/null) state=$(getprop sys.usb.state) config=$(getprop sys.usb.config)" \
       "devices=${devs#,} tty=$(ls /dev/ttyACM* /dev/ttyUSB* 2>/dev/null | flat)"
}

probe_audio() {
  players=$(dumpsys audio 2>/dev/null | grep -c "state:started")
  mode=$(dumpsys audio 2>/dev/null | grep -a -o -m1 "mMode=[A-Z_]*")
  line "probe=audio players=$players ${mode:-mMode=} zlink=$(pidof z-link | flat) ap_up=$(getprop riposte.ap.up)" \
       "projection_log=$(last_log Projection:I)"
}

probe_car() {
  line "probe=car service_log=$(last_log CarService:I) tuner_log=$(last_log TunerHub:I)" \
       "camera_log=$(last_log ReverseCamera:I) video=$(ls /dev/video* 2>/dev/null | flat) sysvar_log=$(last_log SysVar:I)"
}

probe_bt() {
  line "probe=bt enabled=$(settings get global bluetooth_on 2>/dev/null) carkit_log=$(last_log BtCarKit:I)" \
       "daemon_log=$(last_log btopt:D)"
}

probe_launcher() {
  pid=$(pidof com.ripostelabs.carlauncher)
  line "probe=launcher pid=${pid:-0} version=$(dumpsys package com.ripostelabs.carlauncher 2>/dev/null | grep -a -m1 versionCode= | flat)" \
       "boot_completed=$(getprop sys.boot_completed) os=$(getprop ro.riposte.os.version) bench=$(getprop ro.riposte.os.bench)"
}

# The kernel ring once per boot: USB enumeration, tty drivers, the MCU port's driver.
dmesg 2>/dev/null | tail -n 200 > "$DIR/dmesg-$boot.txt"
while true; do
  probe_launcher
  probe_mcu
  probe_usb
  probe_audio
  probe_car
  probe_bt
  sleep "$INTERVAL_S"
done
