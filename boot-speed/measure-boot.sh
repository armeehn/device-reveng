#!/usr/bin/env bash
# Measure the cold-boot timeline. Run before AND after each change so you have
# real numbers, not guesses. Requires adb (rooted not needed for this).
#   ./measure-boot.sh            # snapshot current boot timings
set -euo pipefail
OUT="boot-timeline-$(date +%Y%m%d-%H%M%S).txt"

{
  echo "# kernel->boot_completed marker times (seconds since kernel start)"
  adb shell 'getprop | grep ro.boottime' \
    | sed -E 's/\[ro.boottime.([^]]+)\]: \[([0-9]+)\]/\2 \1/' \
    | awk '{printf "%8.2f  %s\n", $1/1e9, $2}' | sort -n
  echo
  echo "# dmesg total-to-userspace (if permitted)"
  adb shell 'su -c "dmesg | grep -iE \"Freeing unused|run /init|processing action|Command line\" | tail -20"' 2>/dev/null || true
  echo
  echo "# time-to-boot_completed from framework"
  adb shell 'dumpsys SurfaceFlinger 2>/dev/null | grep -i boot' 2>/dev/null || true
} | tee "$OUT"

echo
echo "Saved -> $OUT"
echo "Key numbers to watch: bootcomplete_zxw, zlink5, and the gap before them."
