#!/usr/bin/env bash
# lin-probe.sh — bring-up + passive sniff for the RAV4 (XA50) climate LIN bus via a
# LIN-transceiver → USB-UART, BEFORE wiring it into the launcher (LinReader).
#
# It: (1) finds the USB-UART node, (2) sets it to 19200 8N1 raw, (3) captures raw bytes,
# (4) frames them on the LIN sync byte 0x55 and pretty-prints decoded 0xB1 (status) / 0x39
# (button) frames. LISTEN-ONLY — it never writes a byte to the LIN bus (only stty/chmod the node).
#
# The A/C amplifier is the LIN master and polls continuously with the ignition ON, so frames should
# appear the moment the port opens. If the car is off, the bus is likely asleep → no frames.
#
# ── Where to run ────────────────────────────────────────────────────────────────────────────────
#   • Laptop (adapter plugged into the laptop):   ./lin-probe.sh
#   • Head unit, two options:
#       a) capture on-device, decode on laptop (most portable — device toybox lacks awk/python):
#            adb shell "su -c 'stty -F /dev/ttyUSB0 19200 cs8 -cstopb -parenb raw -echo;
#                               timeout 20 cat /dev/ttyUSB0'" > lin_raw.bin
#            ./lin-probe.sh --decode-file lin_raw.bin
#       b) run this script through adb if the device has bash+python3 (uncommon).
#
# ── Options (env or flags) ──────────────────────────────────────────────────────────────────────
#   NODE=/dev/ttyUSB0      serial node (auto-detected if unset/absent). FTDI→ttyUSB0, CDC→ttyACM*.
#                          NOTE: the CANable (CAN) already owns ttyACM0, so LIN is most likely ttyUSB0.
#   SECONDS_MAX=20         how long to sniff
#   --decode-file FILE     skip capture; just frame+decode an existing raw byte dump
set -u

NODE="${NODE:-}"
SECONDS_MAX="${SECONDS_MAX:-20}"
DECODE_FILE=""
case "${1:-}" in
  --decode-file) DECODE_FILE="${2:?need a file}";;
esac

say() { printf '%s\n' "$*" >&2; }

TMP="${CLAUDE_JOB_DIR:-/tmp}/tmp"; mkdir -p "$TMP" 2>/dev/null || TMP="${CLAUDE_JOB_DIR:-/tmp}"
RAW="${DECODE_FILE:-$TMP/lin_raw.$$}"

# ── The framer/decoder (python3): sync on 0x55, slice known frame lengths, decode 0xB1 / 0x39 ──────
# Materialised to a temp file (NOT `python3 - <<EOF`) so the byte stream on stdin isn't swallowed by
# the here-doc — with `python3 -` the here-doc IS stdin, leaving nothing for sys.stdin.read().
PYDEC="$TMP/lin_decode.$$.py"
cat > "$PYDEC" <<'PY'
import sys
# ---- LIN climate decode (mirrors LinClimateDecoder.kt) ----------------------------------------
PID_STATUS, PID_BUTTONS = 0xB1, 0x39
LEN = {PID_STATUS: 8, PID_BUTTONS: 8}   # data-byte counts (excludes PID + checksum)
MODE = {0x1:"face",0x2:"face/feet",0x3:"feet",0x4:"feet/defrost",0x9:"defrost"}
BTN = {  # (byte_index, value) -> name ; base frame = 40 00 00 00 10 90 00 00
 (0,0x42):"OFF",(0,0x48):"AUTO",
 (1,0x80):"A/C",(1,0x40):"ECO",(1,0x3D):"FAN-",(1,0x3C):"FAN+",
 (2,0x1C):"MODE",(2,0x80):"S-MODE",
 (3,0x20):"SYNC",(3,0x80):"FRONT-DEFROST",(3,0x40):"REAR-DEFROST",
 (4,0x0F):"DRV_TEMP-",(4,0x11):"DRV_TEMP+",
 (5,0x8F):"PAS_TEMP-",(5,0x91):"PAS_TEMP+",
 (6,0xC0):"RECIRC"}

def raw_id(pid): return pid & 0x3F
def fold(seed, data):
    s = seed
    for b in data:
        s += b
        if s > 0xFF: s -= 0xFF
    return s
def enh(pid, data): return (~fold(pid, data)) & 0xFF
def cls(data):      return (~fold(0, data)) & 0xFF
def cksum_kind(pid, data, c):
    if c == enh(pid, data): return "ENH"
    if c == cls(data):      return "CLS"
    return "BAD"

def decode(pid, data, c):
    kind = cksum_kind(pid, data, c)
    hexd = " ".join("%02X"%x for x in data)
    if pid == PID_STATUS:
        fan  = data[1] & 0x07
        mode = MODE.get(data[2] & 0x0F, "?(0x%X)"%(data[2]&0x0F))
        dt, pt = data[4], data[5]
        # UNCONFIRMED temp scale: raw*0.5 °C (0x2C -> 22.0). Show BOTH raw and guess.
        ac  = "AC" if (data[7] & 0x01) else "  "
        ill = "ILLUM" if (data[7] & 0x80) else "     "
        eco = "ECO" if (data[0] & 0x01) else "   "
        return ("STATUS 0xB1(id0x%02X) fan=%d mode=%-11s drvT=0x%02X(~%.1fC) pasT=0x%02X(~%.1fC) %s %s %s  [%s cks]  {%s}"
                % (raw_id(pid), fan, mode, dt, dt*0.5, pt, pt*0.5, ac, eco, ill, kind, hexd))
    if pid == PID_BUTTONS:
        pressed = [n for (idx,val),n in BTN.items() if idx < len(data) and data[idx]==val]
        return ("BUTTON 0x39(id0x%02X) %-14s [%s cks]  {%s}"
                % (raw_id(pid), ",".join(pressed) if pressed else "(base/none)", kind, hexd))
    return None

# ---- byte source: hex tokens on stdin (from `od -An -v -tx1`) ----------------------------------
buf = bytearray()
def feed(bs):
    buf.extend(bs)
    i = 0
    while i < len(buf):
        if buf[i] != 0x55:            # skip until sync (BREAK often shows as 0x00 before it)
            i += 1; continue
        if i+1 >= len(buf): break     # need PID
        pid = buf[i+1]
        n = LEN.get(pid)
        if n is None:                 # unknown/unframeable id (or false 0x55) -> resync past it
            i += 1; continue
        end = i + 2 + n + 1           # 0x55 + PID + data + checksum
        if end > len(buf): break      # partial frame; wait for more bytes
        data = bytes(buf[i+2:i+2+n]); c = buf[i+2+n]
        line = decode(pid, data, c)
        if line: print(line, flush=True)
        i = end
    del buf[:i]

for tok in sys.stdin.read().split():
    try: feed(bytes([int(tok, 16)]))
    except ValueError: pass
PY
# Pipe a stream of `od -An -v -tx1` hex tokens on stdin into the materialised framer.
decode_stream() { python3 "$PYDEC"; }
trap 'rm -f "$PYDEC" 2>/dev/null; [ -z "$DECODE_FILE" ] && rm -f "$RAW" 2>/dev/null' EXIT INT TERM

# ── DECODE-ONLY mode ──────────────────────────────────────────────────────────────────────────
if [ -n "$DECODE_FILE" ]; then
  say "== decoding $DECODE_FILE (frame on 0x55; 0xB1 status / 0x39 buttons) =="
  od -An -v -tx1 "$DECODE_FILE" | decode_stream
  exit 0
fi

# ── 0. Find the USB-UART ──────────────────────────────────────────────────────────────────────
say "== USB-UART discovery =="
say "-- /dev/ttyUSB* /dev/ttyACM* --"
ls -l /dev/ttyUSB* /dev/ttyACM* 2>/dev/null || say "  (none found)"
say "-- dmesg (ftdi / cdc / usbserial) --"
( dmesg 2>/dev/null | grep -iE 'ftdi|cdc_acm|cdc-acm|usbserial|ch341|cp210' | tail -6 ) \
  || say "  (dmesg unavailable / nothing matched — may need sudo/root)"
say ""
say "FTDI adapters → /dev/ttyUSB0 ; CDC-ACM → /dev/ttyACM*."
say "The CANable (CAN) already uses ttyACM0, so the LIN adapter is most likely ttyUSB0."
say ""

# ── 1. Resolve node ──────────────────────────────────────────────────────────────────────────
if [ -z "$NODE" ] || [ ! -e "$NODE" ]; then
  NODE=$(ls /dev/ttyUSB* 2>/dev/null | head -n1)
fi
if [ -z "${NODE:-}" ] || [ ! -e "$NODE" ]; then
  # CDC fallback: any ttyACM that isn't the CANable's ttyACM0.
  NODE=$(ls /dev/ttyACM* 2>/dev/null | grep -v '/dev/ttyACM0$' | head -n1)
fi
if [ -z "${NODE:-}" ] || [ ! -e "$NODE" ]; then
  say "FATAL: no USB-UART node found. Plug the LIN adapter in and retry."
  exit 2
fi
say "Using node: $NODE"

# ── 2. Configure 19200 8N1 raw ───────────────────────────────────────────────────────────────
chmod 666 "$NODE" 2>/dev/null || say "note: chmod failed (already accessible, or need root/sudo)"
# 8N1 = cs8 -cstopb -parenb. raw -echo stops the tty layer cooking bytes.
STTY_ARGS="19200 cs8 -cstopb -parenb -icrnl -inpck -istrip raw -echo"
stty -F "$NODE" $STTY_ARGS 2>/dev/null || stty -f "$NODE" $STTY_ARGS 2>/dev/null \
  || say "note: stty failed (FTDI needs the 19200 divisor; CDC baud is virtual)"

# ── 3. Capture + live decode ─────────────────────────────────────────────────────────────────
say "== sniffing $NODE for ${SECONDS_MAX}s (0xB1 status / 0x39 buttons) =="
say "   (ignition must be ON — the LIN bus sleeps with the car off)"
if command -v python3 >/dev/null 2>&1; then
  # Stream bytes → od hex → python framer. stdbuf keeps od/cat unbuffered for a live feel.
  ( timeout "$SECONDS_MAX" cat "$NODE" 2>/dev/null | stdbuf -o0 od -An -v -tx1 ) | decode_stream
else
  say "python3 not found; capturing raw to $RAW instead — decode later with:"
  say "   ./lin-probe.sh --decode-file $RAW"
  timeout "$SECONDS_MAX" cat "$NODE" > "$RAW" 2>/dev/null
  say "captured $(wc -c < "$RAW" 2>/dev/null) bytes to $RAW"
fi

say ""
say "== TOGGLE-AND-DIFF: nail the UNCONFIRMED fields =="
say "  While sniffing, change ONE thing on the climate panel and watch which byte moves:"
say "   • Fan +/- ....... 0xB1 byte1 (fan 0..7); 0x39 byte1 = 0x3C/0x3D press"
say "   • Mode .......... 0xB1 byte2 low nibble (1 face..9 defrost); 0x39 byte2 = 0x1C"
say "   • Driver temp ... 0xB1 byte4 (raw). Step it 1°C at a time to LEARN THE SCALE:"
say "                     note byte4 at 16°C and at 30°C → deduce °C-per-count + offset."
say "                     (current guess: raw*0.5 °C, i.e. 0x2C=44 → 22.0°C — UNCONFIRMED.)"
say "   • Passenger temp  0xB1 byte5 (raw); 0x39 byte5 = 0x8F/0x91 press"
say "   • A/C ........... 0xB1 byte7 bit0; 0x39 byte1 = 0x80"
say "  Also grab a REAL checksum byte from a status frame and confirm ENH vs CLS in the output."
