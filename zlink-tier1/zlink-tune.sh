#!/usr/bin/env bash
# zlink-tune.sh — reversible AEC / mic tuning for CarPlay call+Siri audio.
# Captures the current values to baseline-props.txt on first run, so `restore` is exact.
#
#   ./zlink-tune.sh baseline   # just capture current values (read-only)
#   ./zlink-tune.sh apply       # apply the recommended starting deltas (reversible)
#   ./zlink-tune.sh restore     # put every prop back to the captured baseline
#   ./zlink-tune.sh show        # print current values
#
# These props are the ZLink/blinkbt AEC engine knobs (persist.* => survive reboot). They only
# affect the mic/echo path for calls & Siri — never the CarPlay video/data stream. Change one
# thing at a time and A/B a phone call if you're chasing echo.
set -uo pipefail
SERIAL="${ADB_SERIAL:-}"; ADB=(adb); [ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")
SU() { "${ADB[@]}" shell su -c "$*"; }
HERE="$(cd "$(dirname "$0")" && pwd)"; BASE="$HERE/baseline-props.txt"

# props we manage (baseline captured from recon: aecdelay=1000, apm.delay=200, micgain=1,
# speakergain=2, rnn=false, force_cp_mic_8k=false)
PROPS=(
  persist.blinkbt.carplay.aecdelay
  persist.blinkbt.aec.delay
  persist.blinkbt.aec.gain
  persist.zj.apm.delay
  persist.zj.apm.micdelaycount
  persist.zj.apm.micgain
  persist.zj.apm.micvolume
  persist.zj.apm.speakergain
  persist.zj.apm.rnn
  persist.zj.force_cp_mic_8k
)

"${ADB[@]}" get-state >/dev/null 2>&1 || { echo "!! no device"; exit 1; }
SU id | grep -q uid=0 || { echo "!! need Magisk root"; exit 1; }

capture(){ : > "$BASE"; for p in "${PROPS[@]}"; do v=$("${ADB[@]}" shell getprop "$p" | tr -d '\r'); echo "$p=$v" >> "$BASE"; done; echo "[*] baseline -> $BASE"; cat "$BASE"; }
show(){ for p in "${PROPS[@]}"; do printf "  %-34s %s\n" "$p" "$("${ADB[@]}" shell getprop "$p" | tr -d '\r')"; done; }

case "${1:-show}" in
  baseline) capture ;;
  show)     show ;;
  restore)
    [ -f "$BASE" ] || { echo "!! no baseline-props.txt — run 'baseline' before you ever applied changes"; exit 1; }
    while IFS='=' read -r p v; do [ -n "$p" ] && SU "setprop $p '$v'"; done < "$BASE"
    echo "[*] restored from baseline:"; show ;;
  apply)
    [ -f "$BASE" ] || capture   # never apply without a baseline to revert to
    echo "[*] applying recommended starting deltas (reversible via 'restore') ..."
    # Conservative, defensible starting point for echo/latency on calls+Siri:
    #  - enable RNNoise denoise (cleaner mic)               rnn  false -> true
    #  - keep AEC delays at OEM 1000/200 unless echo persists (then bisect down 1000->600->400)
    #  - bump mic gain one notch if the phone side is quiet  micgain 1 -> 2
    SU "setprop persist.zj.apm.rnn true"
    SU "setprop persist.zj.apm.micgain 2"
    echo "[*] applied. A/B a call. If echo, lower persist.blinkbt.carplay.aecdelay (try 600, then 400)."
    echo "[*] revert anytime: ./zlink-tune.sh restore"; show ;;
  *) echo "usage: $0 [baseline|apply|restore|show]"; exit 2 ;;
esac
