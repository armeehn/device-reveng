#!/usr/bin/env bash
# vbmeta-disable.py must set HASHTREE_DISABLED only (flags=1). flags=3 (VERIFICATION_DISABLED
# too) made the unit's ABL skip its AVB path, and the vendor's screen config read
# (privdata2 [zxw_Config] -> panel_size=, ZXWNoDebug) lives inside that path: the panel came up
# 720x1280 portrait instead of 1920x720 (2026-09-18, bench). Runs anywhere with python3.
set -euo pipefail
cd "$(dirname "$0")"

T=$(mktemp -d)
trap "rm -rf $T" EXIT

# A minimal AVB0 header: magic, then zeros; flags live at byte 120.
{ printf 'AVB0'; head -c 252 /dev/zero; } > $T/in.img
python3 vbmeta-disable.py $T/in.img $T/out.img >/dev/null
flags=$(od -An -tu4 --endian=big -j120 -N4 $T/out.img | tr -d ' ')
[ "$flags" = 1 ] || { echo "FAIL: flags=$flags, want 1 (hashtree disabled only)"; exit 1; }
[ "$(head -c 4 $T/out.img)" = AVB0 ] || { echo "FAIL: magic lost"; exit 1; }
cmp -s <(head -c 120 $T/in.img) <(head -c 120 $T/out.img) || { echo "FAIL: bytes before flags changed"; exit 1; }

# Not a vbmeta image: refused, nothing written.
head -c 256 /dev/zero > $T/junk.img
if python3 vbmeta-disable.py $T/junk.img $T/junk-out.img >/dev/null 2>&1; then echo "FAIL: junk accepted"; exit 1; fi
[ ! -e $T/junk-out.img ] || { echo "FAIL: output written for junk"; exit 1; }
echo "VBMETA-FLAGS PASS"
