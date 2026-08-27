#!/usr/bin/env bash
# Analyze the on-device reverse-camera capture (/sdcard/rav4_capture/logcat.txt).
# Computes trigger->first-frame latency per reverse event, and the XS9922B signal used.
#
# Usage:
#   bash analyze-capture.sh [logfile]     # analyze a local logcat file
#   bash analyze-capture.sh --pull [ip:port]   # adb-pull the capture first, then analyze
#
# Capture format: logcat -v time, with injected markers "I/RAV4CAP: REVERSE_STATE=<0|1>".
set -uo pipefail

LOG=""
if [ "${1:-}" = "--pull" ]; then
  DEV="${2:-$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')}"
  [ -z "$DEV" ] && { echo "no adb device to pull from"; exit 1; }
  mkdir -p ./camera-diag-out
  adb -s "$DEV" pull /sdcard/rav4_capture/logcat.txt ./camera-diag-out/logcat.txt || exit 1
  LOG=./camera-diag-out/logcat.txt
else
  LOG="${1:-./camera-diag-out/logcat.txt}"
fi
[ -f "$LOG" ] || { echo "log not found: $LOG"; exit 1; }
echo ">> analyzing $LOG ($(wc -l < "$LOG") lines)"

python3 - "$LOG" <<'PY'
import sys, re, datetime
log = open(sys.argv[1], encoding='utf-8', errors='ignore').read().splitlines()
TS = re.compile(r'^(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})\.(\d{3})')
def t(line):
    m = TS.match(line)
    if not m: return None
    mo,d,h,mi,s,ms = map(int, m.groups())
    # year is arbitrary; only deltas matter within one capture
    return datetime.datetime(2026, mo, d, h, mi, s, ms*1000)

# "camera is up" signals — from decompiled BackcarEvent/CameraManager/Camera360 pipeline
FRAME = re.compile(r'openCamera|startPreview|onCheckXS9922BSignal|startBackcar|BackcarEvent|'
                   r'first.?frame|Camera360|setPreviewDisplay|preview.*(start|display)|'
                   r'surface.*camera|BACKCAR_START', re.I)
SIGNAL = re.compile(r'sensorcfg\.signal|getXS9922BSignalState|StrValue\s*=|openCamera:\s*\d+\s*\|\s*\d+|VCH\d+_RES\d+', re.I)

events = []   # (time, kind, line)
for ln in log:
    ts = t(ln)
    if not ts: continue
    if 'RAV4CAP' in ln and 'REVERSE_STATE=1' in ln: events.append((ts,'REV_ON',ln))
    elif 'RAV4CAP' in ln and 'REVERSE_STATE=0' in ln: events.append((ts,'REV_OFF',ln))
    elif FRAME.search(ln): events.append((ts,'FRAME',ln))
    if SIGNAL.search(ln): events.append((ts,'SIG',ln))
events.sort(key=lambda e:e[0])

rev_ons = [e for e in events if e[1]=='REV_ON']
print(f"\n=== reverse engagements found: {len(rev_ons)} ===")
if not rev_ons:
    print("No REVERSE_STATE=1 markers — the unit never registered reverse during the capture")
    print("(car likely not in READY mode, or reverse not engaged in the window).")
    # still surface any signal lines seen
    sigs=[e for e in events if e[1]=='SIG'][:8]
    if sigs:
        print("\nSignal/format lines observed (context):")
        for _,_,l in sigs: print("  ", l.strip()[:160])
    sys.exit(0)

lats=[]
for i,(ts,_,line) in enumerate(rev_ons):
    # first FRAME event after this reverse-on, before the next reverse-on
    nxt = rev_ons[i+1][0] if i+1 < len(rev_ons) else None
    frame = next((e for e in events if e[1]=='FRAME' and e[0] > ts and (nxt is None or e[0] < nxt)), None)
    sig   = next((e for e in events if e[1]=='SIG'   and e[0] >= ts and (nxt is None or e[0] < nxt)), None)
    if frame:
        dl = (frame[0]-ts).total_seconds()
        lats.append(dl)
        print(f"\n[{i+1}] reverse @ {ts.strftime('%H:%M:%S.%f')[:-3]}")
        print(f"     -> first camera event +{dl*1000:.0f} ms : {frame[2].strip()[:120]}")
        if sig: print(f"     signal/format: {sig[2].strip()[:140]}")
    else:
        print(f"\n[{i+1}] reverse @ {ts.strftime('%H:%M:%S.%f')[:-3]} -> NO camera event captured")

if lats:
    lats.sort()
    print(f"\n=== latency summary ({len(lats)} samples) ===")
    print(f"  min {min(lats)*1000:.0f} ms | median {lats[len(lats)//2]*1000:.0f} ms | max {max(lats)*1000:.0f} ms")
    print("  (>1s implicates the XS9922B 1Hz signal-detect poll + ~3-sample debounce)")
PY

cat <<'EOF'

=== A/B TEST PROTOCOL (validate the fix) ===
1. BEFORE: run this analyzer on a capture with several reverse engagements -> record median ms.
2. Apply fix (rooted):  pin the rear signal, e.g.
     su -c 'setprop persist.camera.sensorcfg.signal "<field0>,<REARCODE>,<f2>,<f3>"'
     su -c 'setprop persist.camera.sensorcfg.resolution "TYP1_CID0_VCH1_RES<res>"'
   (REARCODE from the "signal/format" line above; 3=AHD720p25, 4=AHD1080p25, etc. Back up first:
     su -c 'getprop persist.camera.sensorcfg.signal; getprop persist.camera.sensorcfg.resolution')
3. Re-capture the same way, re-run this analyzer -> compare median. Expect the multi-second
   detect delay to drop toward the raw UART+open latency.
EOF
