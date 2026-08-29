#!/system/bin/sh
# canable-probe.sh — validate a CANable 2.0 (slcan firmware) BEFORE app integration.
#
# Confirms the adapter enumerates as USB CDC-ACM, opens it LISTEN-ONLY at 500 kbps (Toyota bus),
# and dumps a batch of decoded frames so you can see which Toyota CAN IDs actually appear at your
# chosen tap point. It NEVER transmits a CAN frame (no slcan 't'/'T'); it only sends the slcan
# control commands C / S6 / L(or O).
#
# Runs both places (uses /system/bin/sh syntax, POSIX-only, no bashisms):
#   • Laptop:  plug the CANable into the laptop, then:   sh canable-probe.sh
#   • Head unit: push + run over adb:
#                 adb push canable-probe.sh /data/local/tmp/
#                 adb shell "su -c 'sh /data/local/tmp/canable-probe.sh'"
#     (root is used only to chmod the node + read it; SELinux is Permissive on this unit.)
#
# Options (env vars):
#   NODE=/dev/ttyACM0   serial node (auto-detected if unset/absent)
#   BITRATE=S6          slcan bitrate code (S6=500k, S5=250k, S4=125k)
#   COUNT=50            how many decoded frames to print before exiting
#   OPENCMD=L           L=listen-only (preferred). Set OPENCMD=O only if your firmware NAKs L.
#   SECONDS_MAX=15      hard time cap
set -u

NODE="${NODE:-}"
BITRATE="${BITRATE:-S6}"
COUNT="${COUNT:-50}"
OPENCMD="${OPENCMD:-L}"
SECONDS_MAX="${SECONDS_MAX:-15}"
CR=$(printf '\r')

say() { printf '%s\n' "$*" >&2; }

# ── 0. Verify USB-host + CDC-ACM support FIRST (the real gate) ──────────────────────────────────
say "== USB host / CDC-ACM check =="
say "-- /dev/ttyACM* --"
ls -l /dev/ttyACM* 2>/dev/null || say "  (none — adapter not enumerated as CDC-ACM; see notes below)"
say "-- dmesg cdc-acm (last few) --"
( dmesg 2>/dev/null | grep -i -e cdc_acm -e cdc-acm -e 'ttyACM' | tail -5 ) || say "  (dmesg unavailable / no cdc_acm lines)"
if command -v lsusb >/dev/null 2>&1; then
  say "-- lsusb --"; lsusb 2>/dev/null | grep -i -e canable -e 'fw.*slcan' -e 1d50 -e 16d0 || lsusb 2>/dev/null
else
  say "-- lsusb not present; try: cat /sys/kernel/debug/usb/devices | grep -i acm --"
fi
say ""
say "If NO /dev/ttyACM* appears when the CANable is plugged in:"
say "  • the head unit's USB port may be host-incapable or in ADB/host-switch mode;"
say "  • the kernel may lack CONFIG_USB_ACM (cdc_acm) — check: zcat /proc/config.gz | grep -i USB_ACM;"
say "  • try a powered USB hub / the other port. Do NOT proceed until a node shows up."
say ""

# ── 1. Resolve the node ─────────────────────────────────────────────────────────────────────────
if [ -z "$NODE" ] || [ ! -e "$NODE" ]; then
  NODE=$(ls /dev/ttyACM* 2>/dev/null | head -n1)
fi
if [ -z "${NODE:-}" ] || [ ! -e "$NODE" ]; then
  say "FATAL: no CDC-ACM node found. Aborting."
  exit 2
fi
say "Using node: $NODE"

# ── 2. Make it readable + set raw mode ──────────────────────────────────────────────────────────
chmod 666 "$NODE" 2>/dev/null || say "note: chmod failed (already accessible, or need root)"
# CDC-ACM baud is virtual for slcan, but raw mode stops the tty layer from cooking bytes/echo.
if command -v stty >/dev/null 2>&1; then
  # -F on GNU, -f on toybox/BSD; try both.
  stty -F "$NODE" raw -echo 115200 2>/dev/null || stty -f "$NODE" raw -echo 115200 2>/dev/null \
    || say "note: stty raw failed (some CDC-ACM stacks don't need it)"
fi

# ── 3. Start a background reader on the node, THEN send the init sequence ────────────────────────
# Reader must be attached before we open the channel or we miss the first frames.
TMP="${CLAUDE_JOB_DIR:-/data/local/tmp}"; [ -d "$TMP" ] || TMP=/tmp
RAW="$TMP/canable_raw.$$"
: > "$RAW"
# stdbuf if available keeps cat from buffering; fall back to plain cat.
if command -v stdbuf >/dev/null 2>&1; then
  stdbuf -o0 cat "$NODE" >> "$RAW" 2>/dev/null &
else
  cat "$NODE" >> "$RAW" 2>/dev/null &
fi
CATPID=$!
trap 'kill "$CATPID" 2>/dev/null; printf "C%s" "$CR" > "$NODE" 2>/dev/null; rm -f "$RAW"' EXIT INT TERM

# slcan init: close (in case open) → set bitrate → open. LISTEN-ONLY unless OPENCMD=O.
say "== slcan init: C / $BITRATE / $OPENCMD (listen-only=${OPENCMD}) =="
printf 'C%s'      "$CR" > "$NODE" 2>/dev/null; sleep 1
printf '%s%s' "$BITRATE" "$CR" > "$NODE" 2>/dev/null; sleep 1
printf '%s%s' "$OPENCMD" "$CR" > "$NODE" 2>/dev/null; sleep 1

# ── 4. Decode + print frames ────────────────────────────────────────────────────────────────────
# slcan data lines: t<III><L><DD..>  (11-bit)  or  T<IIIIIIII><L><DD..>  (29-bit).
say "== decoded frames (id : dlc : data) — target $COUNT, cap ${SECONDS_MAX}s =="
say "   watch for Toyota IDs: 0B4 speed, 0AA wheels, 127 gear, 025 steer, 245 gas, 0A6 brake,"
say "   1D2/1D3 cruise, 620 doors, 614 blinkers"
start=$(date +%s 2>/dev/null || echo 0)
seen=0
last=0
while :; do
  now=$(date +%s 2>/dev/null || echo 0)
  [ "$now" -ge $((start + SECONDS_MAX)) ] && { say "(time cap hit)"; break; }
  [ "$seen" -ge "$COUNT" ] && break
  # Read any new complete lines appended since last offset.
  total=$(wc -l < "$RAW" 2>/dev/null | tr -d ' ')
  [ -z "$total" ] && total=0
  if [ "$total" -gt "$last" ]; then
    sed -n "$((last + 1)),${total}p" "$RAW" 2>/dev/null | tr -d '\r' | while IFS= read -r line; do
      case "$line" in
        t???*)
          id=$(printf '%s' "$line" | cut -c2-4)
          dlc=$(printf '%s' "$line" | cut -c5)
          data=$(printf '%s' "$line" | cut -c6-)
          printf '  0x%s : %s : %s\n' "$id" "$dlc" "$data"
          ;;
        T????????*)
          id=$(printf '%s' "$line" | cut -c2-9)
          dlc=$(printf '%s' "$line" | cut -c10)
          data=$(printf '%s' "$line" | cut -c11-)
          printf '  0x%s(ext) : %s : %s\n' "$id" "$dlc" "$data"
          ;;
        *) : ;;  # 'V'/'v' version, bell (0x07 = L unsupported), blank — ignore
      esac
    done
    seen=$total
    last=$total
  fi
  sleep 1
done

say "== done: $seen raw lines captured =="
if [ "$seen" -eq 0 ]; then
  say "No frames. Checklist:"
  say "  • Is the tap on a LIVE bus? (ignition ON — many buses sleep with the car off.)"
  say "  • Right bitrate? Toyota powertrain = 500k (S6). Try BITRATE=S5 (250k) if silent."
  say "  • If OPENCMD=L returned a bell (0x07), the firmware may not support listen-only —"
  say "    rerun with OPENCMD=O (still no transmit is issued by this script)."
  say "  • CANH/CANL not swapped, 120Ω termination present on the segment."
fi
# EXIT trap sends 'C' to close the channel cleanly and cleans up.
