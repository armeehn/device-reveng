#!/bin/bash
# drive-capture.sh — one log containing both the raw bus and a trusted speed reference.
#
# WHY BOTH IN ONE FILE
# Calibrating 0x17 / 0x13 needs a raw value paired with a known km/h. OBD PID 0x0D reports road
# speed directly, so it is the reference — but something has to ASK for it. candump alone records
# a bus on which nobody asked, giving raw values and nothing to calibrate them against.
#
# Polling into the same candump log is deliberate: both the request/response and the digest frames
# carry the same capture timestamps, so pairing is a matter of reading one file rather than
# aligning two clocks. Aligning two clocks is how the last calibration went wrong.
#
# NO SPEED HOLDING. Drive normally. The reference and the raw value are sampled off the same bus
# at the same instant, so transients pair correctly; ordinary traffic covers the speed range
# better than a held speed, and holding one on a public road is dangerous.
#
#   ./drive-capture.sh            # runs until Ctrl-C
#   OUT=/tmp/drive.log ./drive-capture.sh
set -uo pipefail

IF="${IF:-can0}"
OUT="${OUT:-$HOME/canlog/drive-$(date +%Y%m%d-%H%M%S).log}"
HZ="${HZ:-2}"                 # OBD queries per second
OBD_REQ="7DF#02010D0000000000"   # mode 01, PID 0D, vehicle speed in km/h

command -v candump >/dev/null || { echo "candump not found"; exit 1; }
command -v cansend >/dev/null || { echo "cansend not found"; exit 1; }
ip link show "$IF" >/dev/null 2>&1 || { echo "$IF is not up; plug the CANable in"; exit 1; }

# Querying means transmitting. The adapter must not be listen-only, and on this firmware
# listen-only fails SILENTLY -- the channel never opens and you get a log full of nothing.
mode=$(grep -s '^CAN_MODE=' /etc/default/canable | cut -d= -f2)
if [ "$mode" = "listen-only" ]; then
  echo "CAN_MODE=listen-only: the adapter cannot send OBD queries, and on this firmware"
  echo "it will also deliver no frames at all. Set CAN_MODE=active and replug."
  exit 1
fi

mkdir -p "$(dirname "$OUT")"
echo "logging $IF -> $OUT"
echo "polling OBD PID 0x0D at ${HZ} Hz for a speed reference"
echo "drive normally; Ctrl-C when done"

candump -t a -l "$IF" >/dev/null 2>&1 &
DUMP=$!
# candump -l writes its own timestamped file in the CWD; keep ours as the canonical name.
cleanup() { kill "$DUMP" 2>/dev/null; wait "$DUMP" 2>/dev/null; echo; echo "stopped. newest log:"; ls -t candump-*.log 2>/dev/null | head -1; }
trap cleanup INT TERM

sleep_s=$(awk -v h="$HZ" 'BEGIN{printf "%.3f", 1/h}')
while kill -0 "$DUMP" 2>/dev/null; do
  cansend "$IF" "$OBD_REQ" 2>/dev/null
  sleep "$sleep_s"
done
