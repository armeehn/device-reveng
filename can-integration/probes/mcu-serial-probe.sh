#!/system/bin/sh
# mcu-serial-probe.sh — can we talk to the MCU without the vendor apps?
#
# THE QUESTION THIS ANSWERS, AND WHY IT MATTERS
# CUSTOM_ANDROID.md rules out a custom system because the car functions "effectively break":
# the Choiceway apps fail without their platform-signed settings provider, which we can never
# sign for. But those apps are only necessary because they OWN the MCU serial port. The same
# document concedes, in passing, that "the port is reachable".
#
# If a plain root shell can read framed MCU traffic off that port, then the vendor apps are
# replaceable rather than essential, and the whole custom-system verdict is worth reopening.
# If it cannot, we have learned that for the cost of a shell command instead of a bricked car.
#
# It also settles a documented contradiction: the decompiled apps reference /dev/ttyS1, the
# feasibility doc says /dev/ttyHS1. Both appear. This looks at every candidate and reports what
# is actually there.
#
# READ-ONLY. Opens nothing for writing, sends no frame, changes no permission. It is a question,
# not a change. Writing to this port with the vendor stack also running would put two speakers on
# one link, and the far end drives the car.
#
#   adb push mcu-serial-probe.sh /data/local/tmp/
#   adb shell "su -c 'sh /data/local/tmp/mcu-serial-probe.sh'"
set -u
SECS="${SECS:-8}"
OUT="${OUT:-/data/local/tmp/mcu-probe}"

say() { echo "[mcu-probe] $*"; }

say "candidate serial nodes"
for n in /dev/ttyS0 /dev/ttyS1 /dev/ttyS2 /dev/ttyS3 /dev/ttyS4 /dev/ttyHS0 /dev/ttyHS1 /dev/ttyHS2; do
  if [ -e "$n" ]; then
    # Mode and owner decide whether a non-root app could ever do this, which is the difference
    # between "root can" and "our app can".
    say "  present: $n  $(ls -l "$n" 2>/dev/null | awk '{print $1, $3, $4}')"
  fi
done

say ""
say "who currently holds them (a busy port means the vendor stack owns it)"
for n in /dev/ttyS1 /dev/ttyHS1; do
  [ -e "$n" ] || continue
  holder=$(lsof "$n" 2>/dev/null | tail -n +2 | awk '{print $1}' | sort -u | tr '\n' ' ')
  say "  $n held by: ${holder:-nobody visible to lsof}"
done

say ""
say "reading ${SECS}s from each candidate, looking for 5A A5 or A5 5A A5 framing"
mkdir -p "$OUT" 2>/dev/null
found=0
for n in /dev/ttyS1 /dev/ttyHS1 /dev/ttyS3; do
  [ -e "$n" ] || continue
  f="$OUT/$(basename "$n").bin"
  # A plain read. If the vendor app holds it exclusively this returns nothing, which is itself
  # the answer: the port is not shareable and a custom system would have to own it outright.
  timeout "$SECS" cat "$n" > "$f" 2>/dev/null
  sz=$(wc -c < "$f" 2>/dev/null || echo 0)
  say "  $n -> $sz bytes"
  if [ "$sz" -gt 0 ]; then
    found=1
    say "    first bytes: $(od -An -tx1 -N 32 "$f" 2>/dev/null | tr -s ' ')"
    # Count both framings. Outbound (head unit -> MCU) is 5A A5; inbound is A5 5A A5.
    ob=$(od -An -tx1 "$f" 2>/dev/null | tr -s ' ' '\n' | tr -d ' ' | tr '\n' ' ' | grep -o '5a a5' | wc -l)
    ib=$(od -An -tx1 "$f" 2>/dev/null | tr -s ' ' '\n' | tr -d ' ' | tr '\n' ' ' | grep -o 'a5 5a a5' | wc -l)
    say "    outbound 5A A5 headers: $ob   inbound A5 5A A5 headers: $ib"
  fi
done

say ""
if [ "$found" = 1 ]; then
  say "VERDICT: the port is readable from a root shell with the vendor stack running."
  say "  The Choiceway apps are not the only possible speaker, so the custom-system"
  say "  verdict in CUSTOM_ANDROID.md is worth reopening. Keep the capture: $OUT"
else
  say "VERDICT: nothing readable. Either the vendor app holds it exclusively, or the"
  say "  traffic is on a node not listed above. This does NOT prove a custom system is"
  say "  impossible - only that sharing the port on the stock system is not the route."
fi
