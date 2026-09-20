#!/system/bin/sh
# The wireless CarPlay access point on car-owner builds. The phone refuses 2.4 GHz and an app
# cannot pick the band, so init raises a tethered 5 GHz soft AP and keeps it up; the projection
# app answers the daemon's AP request with the riposte.ap.* properties set here.
#
#   riposte-hotspot.sh ──cmd wifi start-softap RAV4-CarPlay wpa2 <psk> -b 5 [-f <STA freq>]──▶ wlan1
#                      └─ setprop riposte.ap.ssid / riposte.ap.psk / riposte.ap.iface / sys.wifiap.channel
#
# Pinning the AP to the STA's own 5 GHz channel keeps the unit's Wi-Fi client link alive next
# to it (this chip does STA+AP on one channel, not across bands). The tethered AP idles out
# after ten minutes without a client, so the loop puts it back.
[ "$(getprop ro.riposte.os.car_owner)" = 1 ] || exit 0
SSID=RAV4-CarPlay
IFACE=wlan1
PSK_DIR=/data/misc/riposte
PSK_FILE=$PSK_DIR/ap.psk
PSK_LEN=16
CHECK_S=60

# One passphrase per /data, root-only on disk; the phone learns it over the authenticated
# iAP2 link and never types it.
if [ ! -s "$PSK_FILE" ]; then
  mkdir -p "$PSK_DIR" && chmod 700 "$PSK_DIR"
  tr -dc a-z0-9 < /dev/urandom | head -c "$PSK_LEN" > "$PSK_FILE"
  chmod 600 "$PSK_FILE"
fi
PSK=$(cat "$PSK_FILE")

# The STA's frequency, when it sits in 5 GHz: "Frequency: 5180MHz" in the Wi-Fi status.
sta_freq() {
  cmd wifi status 2>/dev/null | sed -n 's/.*Frequency: \([0-9]*\)MHz.*/\1/p' | head -1
}

raise() {
  f=$(sta_freq)
  if [ -n "$f" ] && [ "$f" -ge 5000 ]; then
    cmd wifi start-softap "$SSID" wpa2 "$PSK" -b 5 -f "$f" > /dev/null 2>&1
    setprop sys.wifiap.channel $(( (f - 5000) / 5 ))
  else
    cmd wifi start-softap "$SSID" wpa2 "$PSK" -b 5 > /dev/null 2>&1
  fi
  setprop riposte.ap.ssid "$SSID"
  setprop riposte.ap.psk "$PSK"
  setprop riposte.ap.iface "$IFACE"
}

while true; do
  ip link show "$IFACE" > /dev/null 2>&1 || raise
  sleep "$CHECK_S"
done
