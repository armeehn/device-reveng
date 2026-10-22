#!/usr/bin/env bash
# CarPlay on a bench unit that runs 0.2 without the daemon in its image yet: everything the
# image will carry once #226 is in, done by hand over adb, from the owner's stock system.img.
#
#   stock system.img ──▶ /data/local/tmp/zlink/{z-link,z-mdnsd,z-usbmuxd,lib/*.so}   (survives a flash)
#                    ──▶ /system/bin/z-mdnsd                     (the daemon's fixed path; lost on a flash)
#   cmd wifi start-softap RAV4-CarPlay wpa2 <psk> -b 5 -f <STA freq>   ──▶ riposte.ap.* props
#   z-link -c zhuoxw  ◀──127.0.0.1──▶  com.ripostelabs.projection (installed from the release host's suite/)
#
# Usage: bench-carplay.sh [--restart]     runs on the build host; adb reaches the unit over Wi-Fi.
#   --restart   only restart the daemon (after an app reinstall).
# Env: UNIT (adb serial), SHARE (the share's os/ folder), ADB (the build host's adb),
#      STAGE (a folder that adb can push from), PSK (the access point password).
set -euo pipefail
# Site values (hosts, addresses, share paths) come from $RIPOSTE_OS_ENV, never from this
# file: the repo is public. os/riposte-os.env.example lists them.
RIPOSTE_OS_ENV=${RIPOSTE_OS_ENV:-/etc/riposte-os.env}
# shellcheck source=/dev/null
[ -r "$RIPOSTE_OS_ENV" ] && . "$RIPOSTE_OS_ENV"
UNIT=${UNIT:-${RIPOSTE_UNIT_ADB:?set RIPOSTE_UNIT_ADB}}
SHARE=${SHARE:-${RIPOSTE_SHARE:?set RIPOSTE_SHARE}/os}
ADB=${ADB:-${RIPOSTE_BUILD_ADB:-adb}}
STAGE=${STAGE:-${RIPOSTE_ZLINK_STAGE:?set RIPOSTE_ZLINK_STAGE}}
readonly SSID=RAV4-CarPlay
readonly PSK=${PSK:-${RIPOSTE_CARPLAY_PSK:?set RIPOSTE_CARPLAY_PSK}}
readonly DAEMON_DIR=/data/local/tmp/zlink
readonly APP=com.ripostelabs.projection

a() { $ADB -s "$UNIT" "$@"; }
log() { echo "[carplay] $*" >&2; }

# The daemon exits with every session; a loop on the unit brings it back (init does that on
# the image). One loop only: the old one is killed by its script name.
start_daemon() {
  a shell "cat > $DAEMON_DIR/keeper.sh <<'EOS'
#!/system/bin/sh
cd $DAEMON_DIR
export LD_LIBRARY_PATH=$DAEMON_DIR/lib PATH=$DAEMON_DIR:\$PATH
while true; do
  pgrep -x z-link > /dev/null || ./z-link -c zhuoxw > run.out 2>&1
  sleep 3
done
EOS
chmod 755 $DAEMON_DIR/keeper.sh; pkill -f keeper.sh; pkill -x z-link" >/dev/null
  a shell "(setsid sh $DAEMON_DIR/keeper.sh > $DAEMON_DIR/keeper.out 2>&1 < /dev/null &); sleep 5; pgrep -x z-link" >/dev/null
  log "daemon running, with a respawn loop"
}

$ADB connect "$UNIT" >/dev/null
a root >/dev/null 2>&1 || true; sleep 2; $ADB connect "$UNIT" >/dev/null

if [ "${1:-}" = --restart ]; then
  # A reinstall kills the service; the daemon needs its servers before it redials.
  a shell "am start-foreground-service -n $APP/.ZlinkService" >/dev/null 2>&1 || true
  start_daemon; exit 0
fi

# 1. the daemon set, from the owner's stock system image
MNT=$(mktemp -d)
mount -o ro,loop "$SHARE/base-ota/system.img" "$MNT"
rm -rf "$STAGE"; mkdir -p "$STAGE/lib"
cp "$MNT"/system/priv-app/zlink5/lib/arm/*.so "$STAGE/lib/"
cp "$MNT"/system/bin/z-link "$MNT"/system/bin/z-mdnsd "$MNT"/system/bin/z-usbmuxd "$STAGE/"
umount "$MNT"; rmdir "$MNT"
a push /var/tmp/zlink "$DAEMON_DIR" >/dev/null
a shell "chmod 755 $DAEMON_DIR/z-*"
log "daemon set pushed"

# 2. z-mdnsd where the daemon looks for it
a shell "mount -o remount,rw / && cp $DAEMON_DIR/z-mdnsd /system/bin/z-mdnsd && chmod 755 /system/bin/z-mdnsd && chcon u:object_r:system_file:s0 /system/bin/z-mdnsd; mount -o remount,ro / || true"
log "z-mdnsd on /system/bin"

# 3. the 5 GHz access point, pinned to the STA's channel so Wi-Fi adb survives
freq=$(a shell 'cmd wifi status' | sed -n 's/.*Frequency: \([0-9]*\)MHz.*/\1/p' | head -1 | tr -d '\r')
if [ -n "$freq" ] && [ "$freq" -ge 5000 ]; then
  a shell "cmd wifi start-softap $SSID wpa2 $PSK -b 5 -f $freq; setprop sys.wifiap.channel $(( (freq - 5000) / 5 ))" >/dev/null
else
  log "STA is not on 5 GHz; the AP will take the radio and Wi-Fi adb with it"
  a shell "cmd wifi start-softap $SSID wpa2 $PSK -b 5" >/dev/null
fi
a shell "setprop riposte.ap.ssid $SSID; setprop riposte.ap.psk $PSK; setprop riposte.ap.iface wlan1"
# The tethered AP idles out after ten minutes without a client; a loop on the unit puts it
# back, as riposte-hotspot.sh does on the image.
a shell "pkill -f ap-keeper; (setsid sh -c 'while true; do ip link show wlan1 >/dev/null 2>&1 || cmd wifi start-softap $SSID wpa2 $PSK -b 5 ${freq:+-f $freq} >/dev/null 2>&1; sleep 60; done' > /dev/null 2>&1 < /dev/null &)" 2>/dev/null || true
log "access point $SSID up, keeper loop running"

# 4. the app's runtime grants (the image's default-permissions XML does this at first boot)
for perm in BLUETOOTH_CONNECT NEARBY_WIFI_DEVICES BLUETOOTH_ADVERTISE POST_NOTIFICATIONS; do
  a shell "pm grant $APP android.permission.$perm" 2>/dev/null || true
done
a shell "am start -n $APP/.CarPlayActivity" >/dev/null 2>&1 || log "projection app not installed: the release host's suite/"
start_daemon
log "pair an iPhone with the unit; the session follows on its own (logcat -s Projection btopt)"
