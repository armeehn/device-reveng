#!/usr/bin/env bash
# 01-observe.sh — READ-ONLY baseline capture for the ZLink Tier-1 work.
# Run this FIRST, with the car online (Wi-Fi ADB) and, ideally, the iPhone paired.
# It changes nothing. Output -> ./observe-<timestamp>/.
#
# Usage:
#   ./01-observe.sh                 # capture everything (read-only)
#   ./01-observe.sh --failopen      # ALSO run the guarded fail-open test (temporarily
#                                   # blocks the vendor hosts, prompts you to try CarPlay,
#                                   # then restores). Reversible; needs the iPhone handy.
set -uo pipefail
SERIAL="${ADB_SERIAL:-}"
ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")
SU() { "${ADB[@]}" shell su -c "$*"; }        # Magisk root
SH() { "${ADB[@]}" shell "$*"; }

OUT="observe-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$OUT"
echo "[*] device: $("${ADB[@]}" get-serialno 2>/dev/null) -> $OUT/"
"${ADB[@]}" get-state >/dev/null 2>&1 || { echo "!! no device. adb connect <ip:port> first."; exit 1; }
SU id | grep -q 'uid=0' || { echo "!! su did not return root — enable Magisk root for shell."; exit 1; }

echo "[*] props (zlink/mfi/aec/carplay/wifi) ..."
SH getprop > "$OUT/getprop-full.txt"
grep -iE 'zj|zlink|mfi|carplay|blinkbt|hostapd|softap|wifi' "$OUT/getprop-full.txt" | sort -u > "$OUT/getprop-relevant.txt"

echo "[*] zlink5 process / service state ..."
{ SH getprop init.svc.zlink5; SH getprop init.svc.hostapd;
  SU 'ps -A | grep -iE "zlink|zjinnova|hostapd"'; } > "$OUT/proc.txt" 2>&1

echo "[*] activation / license state on disk ..."
# ZLink persists activation locally ("Activation file"). Find it (read-only).
SU 'ls -la /data/data/com.zjinnova.zlink/files /data/data/com.zjinnova.zlink/shared_prefs 2>/dev/null' > "$OUT/zlink-datadir.txt" 2>&1
SU 'find /data/data/com.zjinnova.zlink -iname "*activ*" -o -iname "*licen*" -o -iname "*.key" 2>/dev/null' >> "$OUT/zlink-datadir.txt" 2>&1
SU 'cat /data/data/com.zjinnova.zlink/shared_prefs/*.xml 2>/dev/null' > "$OUT/zlink-prefs.xml" 2>&1

echo "[*] MFi chip presence (as the app sees it) ..."
{ SH getprop persist.zj.checkmfi.exist; SH getprop persist.zj.mfi.channel;
  SH getprop persist.sys.mfi.index; SU 'ls -la /dev/i2c-* 2>/dev/null'; } > "$OUT/mfi.txt" 2>&1

echo "[*] wireless AP / hostapd config (for the RF fix) ..."
SU 'for f in /vendor/etc/hostapd.conf /data/vendor/wifi/hostapd/hostapd.conf /data/misc/wifi/hostapd.conf; do echo "== $f =="; cat "$f" 2>/dev/null; done' > "$OUT/hostapd.txt" 2>&1
SU 'iw dev 2>/dev/null; echo ---; iw dev wlan0 info 2>/dev/null; iw dev wlan1 info 2>/dev/null' >> "$OUT/hostapd.txt" 2>&1

echo "[*] outbound connections from zlink5 (see what it phones) ..."
SU 'PID=$(pidof com.zjinnova.zlink); echo pid=$PID; cat /proc/$PID/net/tcp 2>/dev/null | head -40; echo ---dns---; logcat -d -t 500 | grep -iE "zjinnova|bugly|activ|http" ' > "$OUT/net.txt" 2>&1

echo "[*] feature lever probe (rw.zlink.disable.features) ..."
SH getprop rw.zlink.disable.features > "$OUT/feature-lever.txt" 2>&1

echo
echo "== SUMMARY =="
echo -n "  zlink5 service : "; SH getprop init.svc.zlink5
echo -n "  MFi exists     : "; SH getprop persist.zj.checkmfi.exist
echo -n "  disable.features: "; SH getprop rw.zlink.disable.features
echo "  activation files:"; sed 's/^/    /' "$OUT/zlink-datadir.txt" | grep -iE 'activ|licen|\.key' | head
echo "  -> review $OUT/ before installing net-block or running Frida."

if [ "${1:-}" = "--failopen" ]; then
  echo
  echo "== FAIL-OPEN TEST (reversible) =="
  echo "  Temporarily nulls vendor hosts, then you test CarPlay, then it restores."
  HOSTS_BAK="$OUT/hosts.orig"
  SU 'cat /system/etc/hosts' > "$HOSTS_BAK" 2>/dev/null
  restore_hosts(){ echo "  [*] restoring hosts..."; "${ADB[@]}" push "$HOSTS_BAK" /data/local/tmp/hosts.orig >/dev/null 2>&1
    SU 'mount -o rw,remount /system 2>/dev/null; cp /data/local/tmp/hosts.orig /system/etc/hosts; mount -o ro,remount /system 2>/dev/null'; }
  trap restore_hosts EXIT
  SU 'mount -o rw,remount /system 2>/dev/null; { cat /system/etc/hosts; echo "127.0.0.1 www.zjinnova.com"; echo "127.0.0.1 com.zjinnova.net"; echo "127.0.0.1 url.zjinnova.com"; echo "127.0.0.1 bugly.qq.com"; } > /data/local/tmp/hosts.blk && cp /data/local/tmp/hosts.blk /system/etc/hosts; mount -o ro,remount /system 2>/dev/null'
  echo "  [*] vendor hosts blocked. Now: reconnect the iPhone and try CarPlay."
  read -r -p "  Did CarPlay connect + stream OK with the server blocked? [y/N] " ans
  echo "  RESULT: fail-open = $ans   (y = net-block alone is enough; n = also use zlink-activation.js)" | tee "$OUT/failopen-result.txt"
fi
echo "[done]"
