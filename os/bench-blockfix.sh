#!/usr/bin/env bash
# Repair a pigtail-corrupted flash block by block, over USB adb, without another fastboot pass:
#   partition ──adb exec-out dd──▶ cmp -l against the image ──▶ differing 4 KiB blocks
#   ──▶ each block pushed, sha256-checked on the unit, dd'd into the mapping (setrw first)
#   ──▶ whole partition re-hashed against SHA256SUMS.
# Usage (laptop at the bench): bench-blockfix.sh [DIR]      PARTS="vendor" limits it; UNIT, SLOT as bench-verify
# The pigtail flips ~4 bits per GB on fastboot writes and reports nothing; a second fastboot pass
# flips others, so a full reflash never converges. Patching converges: each block is hash-checked
# on the unit before it is written.
set -uo pipefail
I=${1:-$HOME/rav4-headunit/os/0.2-bench}
UNIT=${UNIT:-da40e9ac}
SLOT=b
BS=4096
a() { adb -s "$UNIT" "$@"; }
log() { echo "[blockfix $(date +%T)] $*"; }

for p in ${PARTS:-vendor system_ext product system}; do
  img=$I/$p.img; size=$(stat -c %s "$img"); blocks=$((size / BS))
  dev=/dev/block/mapper/${p}_$SLOT
  log "$p: reading $((size / 1048576)) MiB"
  # cmp -l prints 1-based byte offsets that differ.
  offs=$(a exec-out "dd if=$dev bs=$BS count=$blocks 2>/dev/null" | cmp -l - "$img" 2>/dev/null | awk '{print $1}')
  if [ -z "$offs" ]; then log "$p: clean"; continue; fi
  bad=$(for o in $offs; do echo $(( (o - 1) / BS )); done | sort -un)
  log "$p: $(echo "$offs" | wc -l) bytes differ in $(echo "$bad" | wc -l) block(s): $(echo $bad | cut -c1-80)"

  a shell "blockdev --setrw $dev" || { log "$p: setrw failed"; exit 1; }
  for b in $bad; do
    # Confirm the block really differs on a second read, then write the good one.
    if a exec-out "dd if=$dev bs=$BS skip=$b count=1 2>/dev/null" | cmp -s - <(dd if="$img" bs=$BS skip=$b count=1 2>/dev/null); then
      log "$p block $b: clean on the second read (read-side flip), skipping"; continue
    fi
    dd if="$img" bs=$BS skip=$b count=1 2>/dev/null > /tmp/blk-$p-$b
    want=$(sha256sum /tmp/blk-$p-$b | cut -d' ' -f1)
    have=""
    for try in 1 2 3; do
      a push /tmp/blk-$p-$b /data/local/tmp/blk >/dev/null 2>&1
      have=$(a shell "sha256sum /data/local/tmp/blk" | cut -d' ' -f1)
      [ "$want" = "$have" ] && break
      log "$p block $b: push flipped a bit (try $try)"
    done
    [ "$want" = "$have" ] || { log "$p block $b: could not push a clean block"; exit 1; }
    a shell "dd if=/data/local/tmp/blk of=$dev bs=$BS seek=$b count=1 conv=notrunc,fsync 2>/dev/null" || { log "$p block $b: write failed"; exit 1; }
    log "$p block $b: patched"
  done
  a shell "sync"
  want=$(grep -E " (\./)?$p\.img\$" "$I/SHA256SUMS" | awk '{print $1}')
  have=$(a shell "head -c $size $dev | sha256sum" | awk '{print $1}')
  [ "$want" = "$have" ] && log "$p: HASH OK" || log "$p: HASH STILL DIFFERS ($have)"
done
log "BLOCKFIX DONE"
