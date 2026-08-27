#!/system/bin/sh
# Deploy to: /data/adb/service.d/90-restore-gov.sh   (chmod 0755)
# Runs LATE (Magisk late_start service). Waits for boot to complete, holds a few
# extra seconds so the launcher/app-preload settles at full clocks, then restores
# the original governor saved by post-fs-data.d/10-boot-perf.sh. Also drops
# readahead back to a normal value so we don't waste RAM after boot.

LOG=/data/local/tmp/bootperf.log
SAVE=/data/local/tmp/bootperf.govsave

# wait for boot_completed (cap ~90s so we never hang forever)
i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$i" -lt 90 ]; do
    sleep 1; i=$((i+1))
done
sleep 6   # let post-boot UI/app settle at max clocks

# restore per-policy governor
if [ -f "$SAVE" ]; then
    while read -r P OLD; do
        [ -n "$P" ] || continue
        G="$P/scaling_governor"
        [ -w "$G" ] && [ -n "$OLD" ] && echo "$OLD" > "$G" 2>/dev/null
    done < "$SAVE"
fi

# normalize readahead
for RA in /sys/block/sd*/queue/read_ahead_kb /sys/block/dm-*/queue/read_ahead_kb; do
    [ -w "$RA" ] || continue
    echo 512 > "$RA" 2>/dev/null
done

echo "[$(date)] service.d governor restored" >> "$LOG"
