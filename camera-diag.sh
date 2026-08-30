#!/usr/bin/env bash
# Reverse-camera lag diagnostic. Run AFTER we're connected via adb (root helps but not all steps need it).
# Usage: bash camera-diag.sh [ip:port]
# It will prompt you to shift into REVERSE at the right moment to capture trigger->frame latency.
set -uo pipefail
DEV="${1:-$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')}"
[ -z "$DEV" ] && { echo "no adb device"; exit 1; }
A(){ adb -s "$DEV" "$@"; }
SU(){ adb -s "$DEV" shell "su 0 sh -c '$*' 2>/dev/null || $*"; }   # try root, fall back to shell
OUT="${RAV4_HOME:-$HOME/rav4-headunit}/camera-diag-out"; mkdir -p "$OUT"

echo "=== 1. camera-related props (format pinned or Auto?) ==="
A shell getprop | grep -iE "camera|backcar|reverse|avm|ivicar|xs9922|cvbs|ahd|screen.reverse" | tee "$OUT/props.txt"

echo; echo "=== 2. camera services + HAL provider ==="
A shell 'ps -A | grep -iE "camera|provider|auxcam|avm|ivicar"' | tee "$OUT/services.txt"

echo; echo "=== 3. aux/reverse camera app + its stored config (needs root for /data) ==="
for p in com.szchoiceway.auxcamera com.ivicar.avm; do
  echo "--- $p ---"; A shell "pm path $p"
  SU "ls -R /data/data/$p/shared_prefs 2>/dev/null; cat /data/data/$p/shared_prefs/*.xml 2>/dev/null"
done | tee "$OUT/appconfig.txt"

echo; echo "=== 4. video capture device / format (v4l2 / camera HAL) ==="
SU "ls -l /dev/video* 2>/dev/null; getprop | grep -iE 'sensorcfg|sensor360|camera.aux'" | tee "$OUT/video.txt"

echo; echo "=== 5. LATENCY CAPTURE ==="
echo ">>> When ready, keep engine on, then SHIFT TO REVERSE when it says GO. <<<"
A shell logcat -c 2>/dev/null
echo "GO -> shift to REVERSE now (capturing 12s of logcat)..."
timeout 12 adb -s "$DEV" logcat -v time 2>/dev/null | grep -iE "backcar|reverse|auxcam|camera|avm|surface|preview|frame" > "$OUT/reverse_capture.txt"
echo "captured $(wc -l < "$OUT/reverse_capture.txt") lines"
echo "--- backcar.state transitions + first camera/surface events (with timestamps) ---"
grep -iE "backcar|reverse|auxcam|camera.*open|preview|surface.*camera|first.*frame" "$OUT/reverse_capture.txt" | head -30

echo; echo "=== 6. current backcar/reverse flag now ==="
A shell getprop sys.backcar.state; A shell getprop ro.screen.reverse
echo; echo ">> Saved to $OUT/. Key questions to answer from this:"
echo "   - Is camera Type pinned (AHD/CVBS + res/fps) or Auto/empty?  (props.txt: sensorcfg.resolution)"
echo "   - Time gap between 'backcar.state=1' and first camera 'preview/frame' line = trigger->display latency"
echo "   - Does camera-provider-2-4-ext / auxcamera COLD-START on reverse (appears in logcat) or already running?"
echo "   - Buffer count / format-convert warnings in the camera HAL lines"
