#!/usr/bin/env bash
# FULL PARTITION BACKUP of the GT6-CAR head unit via Qualcomm EDL (Firehose).
# PRECONDITION: unit is already in EDL mode and the 4PIN USB cable is connected.
#   To enter EDL:  adb connect <ip:port>   then   adb reboot edl
#   Then plug the 4PIN USB-A<->USB-A cable into the laptop.
# This is READ-ONLY (dumps partitions to disk). It writes NOTHING to the device.
set -uo pipefail

# Workspace holding the EDL tools, Firehose loader, TWRP and Magisk images.
# None of that is redistributed here — see README "Getting the vendor files".
# Override with: RAV4_HOME=/path ./backup.sh
ROOT="${RAV4_HOME:-$HOME/rav4-headunit}"
PY="$ROOT/edl/venv/bin/python"
EDL="$ROOT/edl/src/edl.py"
LOADER="$ROOT/run/prog_firehose_Qcm6125_ddr.elf"
STAMP=$(date +%Y%m%d-%H%M%S 2>/dev/null || echo manual)
OUT="$ROOT/backup-$STAMP"

# Preflight: each of these is vendor/third-party material the repo does not ship.
# Naming the missing file beats a stack trace from edl.py three steps later.
for req in "$PY:python-edl venv" "$EDL:edl.py (bkerler/edl)" "$LOADER:Firehose loader .elf"; do
  path="${req%%:*}"; what="${req#*:}"
  [ -e "$path" ] && continue
  echo "!! missing $what"
  echo "   expected at: $path"
  echo "   See README section 'Getting the vendor files'. Set RAV4_HOME to relocate."
  exit 1
done

# EDL USB needs raw access -> run as root.
SUDO=""; [ "$(id -u)" -ne 0 ] && SUDO="sudo"

echo "=== 0. check device is in EDL (05c6:9008) ==="
if ! lsusb 2>/dev/null | grep -qi "05c6:9008"; then
  echo "!! Qualcomm 9008 (EDL) device NOT found on USB."
  echo "   Do: adb reboot edl, then connect the 4PIN cable, then re-run."
  echo "   (lsusb should show '05c6:9008 Qualcomm ... QDLoader 9008')"
  exit 1
fi
echo "   found: $(lsusb | grep -i 05c6:9008)"
mkdir -p "$OUT"

echo; echo "=== 1. NON-DESTRUCTIVE loader test: read GPT (proves loader auth works) ==="
if ! $SUDO "$PY" "$EDL" printgpt --loader="$LOADER" --memory=ufs 2>&1 | tee "$OUT/_printgpt.txt" | tail -30; then
  echo "!! printgpt failed. If it's a Sahara/auth error, this loader is rejected by the SoC."
  echo "   STOP — do not proceed. We'll need the exact vendor loader. Nothing was written."
  exit 2
fi
echo "   >> GPT read OK — loader is accepted. Safe to dump."

echo; echo "=== 2. FULL DUMP of all partitions (all LUNs) -> $OUT ==="
# rl = read all partitions; --genxml writes rawprogram/patch xml for later restore.
$SUDO "$PY" "$EDL" rl "$OUT" --memory=ufs --loader="$LOADER" --genxml 2>&1 | tee "$OUT/_dump.log" | tail -40

echo; echo "=== 3. summary ==="
$SUDO chown -R "$(id -un)":"$(id -gn)" "$OUT" 2>/dev/null || true
ls -la "$OUT" | head -60
echo ">> Backup dir: $OUT"
echo ">> Verify boot_a/boot_b, abl, vbmeta, dtbo, modem/nvram etc. are present and non-zero."
echo ">> When satisfied, exit EDL:  $SUDO $PY $EDL reset   (or just power-cycle the unit)."
