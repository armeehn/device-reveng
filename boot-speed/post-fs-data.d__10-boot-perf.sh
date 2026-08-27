#!/system/bin/sh
# Deploy to: /data/adb/post-fs-data.d/10-boot-perf.sh   (chmod 0755)
# Runs EARLY (Magisk post-fs-data, before zygote). Pins the SoC to max clocks
# for the duration of cold boot so the heavy one-time framework work
# (PackageManager scan, system_server, app starts) runs at full speed instead
# of being ramped down by the interactive/schedutil governor while cold.
#
# It saves the original governor per policy, then a companion service.d script
# restores it a few seconds after boot completes. Fully reversible: delete both
# files. If this script is missing/fails, the unit boots exactly as stock.

LOG=/data/local/tmp/bootperf.log
SAVE=/data/local/tmp/bootperf.govsave
echo "[$(date)] post-fs-data boot-perf start" >> "$LOG"

: > "$SAVE"

# --- CPU: force performance on every cpufreq policy, remember the old value ---
for P in /sys/devices/system/cpu/cpufreq/policy*; do
    [ -d "$P" ] || continue
    G="$P/scaling_governor"
    [ -w "$G" ] || continue
    OLD=$(cat "$G" 2>/dev/null)
    echo "$P $OLD" >> "$SAVE"
    echo performance > "$G" 2>/dev/null
done
# legacy per-cpu path fallback (older kernels)
for G in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
    [ -w "$G" ] || continue
    echo performance > "$G" 2>/dev/null
done

# --- Storage: larger readahead speeds the sequential app/dex reads at boot ---
# (cold UFS is the slow part of the 14->27s framework window)
for RA in /sys/block/sd*/queue/read_ahead_kb /sys/block/dm-*/queue/read_ahead_kb; do
    [ -w "$RA" ] || continue
    echo 2048 > "$RA" 2>/dev/null
done

echo "[$(date)] post-fs-data boot-perf applied" >> "$LOG"
