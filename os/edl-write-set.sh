#!/usr/bin/env bash
# Write an image set plus a freshly built super over EDL. Unit in 9008 on a FRESH
# enumeration (the loader upload consumes the Sahara hello). Runs on the laptop.
#
#   printgpt ──► w super ──► w boot_b dtbo_b vbmeta_b(flags=1) vbmeta_system_b ──► [e userdata] ──► reset
#
# Usage: [WIPE=1] edl-write-set.sh IMAGE_DIR SUPER_IMG
#   WIPE=1 erases userdata: the vendor fstab marks it `formattable`, so the first boot formats
#   it. Required when switching between Android 13 (0.1) and 14 (0.2) images: 13 refuses a
#   /data that 14 has touched and loops in the boot animation.
set -uo pipefail
I=$1; SUPER=$2
EDL_DIR=${EDL_DIR:-$HOME/rav4-headunit/edl}
cd "$EDL_DIR/src"
E=(../venv/bin/python edl.py --memory=ufs --loader=../prog_firehose_qcm6125.bin)
LOG=$EDL_DIR/write-$(date +%Y%m%d-%H%M%S).log
run() { echo "$(date +%T) $*" | tee -a "$LOG"; "$@" 2>&1 | grep -v RuntimeWarning | tr '\r' '\n' | grep -vE 'Progress|^$' >> "$LOG"; return "${PIPESTATUS[0]}"; }
run "${E[@]}" printgpt || true
grep -q "^super:" "$LOG" || { echo "no GPT: loader refused (press RST, retry)" | tee -a "$LOG"; exit 1; }
run "${E[@]}" w super "$SUPER" || { echo "super write FAILED" | tee -a "$LOG"; exit 1; }
run "${E[@]}" w boot_b "$I/boot.img"
run "${E[@]}" w dtbo_b "$I/dtbo.img"
python3 "$EDL_DIR/vbmeta-disable.py" "$I/vbmeta.img" /tmp/vbmeta-w.img >> "$LOG"
run "${E[@]}" w vbmeta_b /tmp/vbmeta-w.img
run "${E[@]}" w vbmeta_system_b "$I/vbmeta_system.img"
[ "${WIPE:-0}" = 1 ] && run "${E[@]}" e userdata
# `reset` takes no --memory (edl.py's docopt refuses the whole call): loader only.
run ../venv/bin/python edl.py reset --loader=../prog_firehose_qcm6125.bin
echo "$(date +%T) WRITE-SET DONE ($LOG)" | tee -a "$LOG"
