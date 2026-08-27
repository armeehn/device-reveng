#!/usr/bin/env bash
# RAV4 AiNavi head-unit recon pack.
# Run after `adb devices` shows the unit. Collects everything into ./recon/
# and prints the chipset-identifying properties at the end.
set -uo pipefail

OUT="$(dirname "$(readlink -f "$0")")/recon"
mkdir -p "$OUT"

# Pick the device: prefer a network (ip:port) target if present, else first serial.
DEV="$(adb devices | awk 'NR>1 && $2=="device"{print $1; exit}')"
if [ -z "${DEV:-}" ]; then
  echo "No adb device in 'device' state. Run: adb devices" >&2
  echo "If it says 'unauthorized', accept the prompt on the head-unit screen." >&2
  exit 1
fi
echo ">> Using device: $DEV"
A() { adb -s "$DEV" "$@"; }

echo ">> Collecting recon pack into $OUT"

# --- core identity (most important) ---
A shell getprop                                  > "$OUT/getprop.txt"       2>&1
A shell cat /proc/cpuinfo                         > "$OUT/cpuinfo.txt"       2>&1
A shell cat /proc/version                         > "$OUT/version.txt"       2>&1
A shell cat /proc/partitions                      > "$OUT/partitions.txt"    2>&1

# --- packages / bloat inventory ---
A shell pm list packages -f                       > "$OUT/packages.txt"      2>&1
A shell pm list packages -f -s                    > "$OUT/packages_system.txt" 2>&1
A shell pm list packages -f -3                    > "$OUT/packages_3rdparty.txt" 2>&1

# --- partitions by name (often needs root; harmless if it fails) ---
A shell ls -l /dev/block/by-name/                 > "$OUT/partitions_byname.txt" 2>&1
A shell ls -l /dev/block/platform/*/by-name/ 2>/dev/null >> "$OUT/partitions_byname.txt" 2>&1

# --- serial devices (MCU link lives on one of these) ---
A shell "ls -l /dev/ | grep -iE 'tty(MT|HS|S|USB|ACM)'" > "$OUT/serial_devices.txt" 2>&1

# --- display ---
{ A shell wm size; A shell wm density; } > "$OUT/screen.txt" 2>&1

# --- extras that help planning ---
A shell getprop ro.build.fingerprint             > "$OUT/fingerprint.txt"   2>&1
A shell id                                        > "$OUT/adb_id.txt"        2>&1
A shell "su -c id 2>/dev/null || echo 'no su / not rooted'" > "$OUT/root_check.txt" 2>&1
A shell settings list global                      > "$OUT/settings_global.txt" 2>&1

echo
echo "================ CHIPSET SNAPSHOT ================"
A shell getprop | grep -iE \
  'ro\.board\.platform|ro\.hardware|ro\.product\.(board|model|name|device)|ro\.build\.version\.release|ro\.build\.fingerprint|chipname|mcu|soc' \
  | tee "$OUT/_snapshot.txt"
echo "================================================="
echo
echo "Root status: $(cat "$OUT/root_check.txt")"
echo "All files saved in: $OUT"
echo "The key file is getprop.txt — it settles Qualcomm vs Unisoc vs Rockchip."
