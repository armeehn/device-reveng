#!/usr/bin/env bash
# One-shot restore of a slot backup over EDL. Runs on the laptop that holds the backup and
# bkerler `edl`, with the unit already in 9008 (loader upload works on a fresh enumeration only).
#
#   printgpt ──► w boot/dtbo/vbmeta/vbmeta_system ──► rs super metadata ──► lpdump
#            ──► edl-extents.py ──► ws each extent ──► reset
#
# Usage: edl-restore.sh --backup DIR [--edl-dir DIR] [--slot b] [--work DIR]
#                       [--full-super] [--no-reset] [--yes]
#   --backup      backup-slot.sh output: boot dtbo vbmeta vbmeta_system system system_ext
#                 product vendor .img + SHA256SUMS
#   --edl-dir     holds src/ (bkerler edl), venv/, prog_firehose_qcm6125.bin
#   --full-super  read all of super first (a second copy of the broken state); default reads
#                 only the metadata region, which is what lpdump needs
#   --yes         write without the prompt
set -euo pipefail

SLOT=b
EDL_DIR=$HOME/rav4-headunit/edl
WORK=""
FULL_SUPER=0
RESET=1
YES=0
BACKUP=""
PHYSICAL="boot dtbo vbmeta vbmeta_system"
LOGICAL="system system_ext product vendor"
SUPER_META_BYTES=$((16 * 1024 * 1024))   # liblp geometry + both metadata slots sit in the first MiBs
UFS_SECTOR=4096

while [ $# -gt 0 ]; do
    case "$1" in
        --backup) BACKUP=$2; shift 2 ;;
        --edl-dir) EDL_DIR=$2; shift 2 ;;
        --slot) SLOT=$2; shift 2 ;;
        --work) WORK=$2; shift 2 ;;
        --full-super) FULL_SUPER=1; shift ;;
        --no-reset) RESET=0; shift ;;
        --yes) YES=1; shift ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
done
[ -n "$BACKUP" ] || { echo "--backup DIR is required" >&2; exit 2; }
[ -d "$BACKUP" ] || { echo "no such backup: $BACKUP" >&2; exit 2; }

HERE=$(cd "$(dirname "$0")" && pwd)
WORK=${WORK:-$EDL_DIR/restore-$(date +%Y%m%d-%H%M%S)}
mkdir -p "$WORK"
LOG=$WORK/restore.log
LOADER=$EDL_DIR/prog_firehose_qcm6125.bin
# EDL_BIN/EDL_CMD let the test stub the tool. `reset` takes no --memory (edl.py's docopt refuses
# the whole call and prints usage), so it goes through EDL_BIN with the loader only.
EDL_BIN=${EDL_BIN:-"$EDL_DIR/venv/bin/python $EDL_DIR/src/edl.py"}
EDL_CMD=${EDL_CMD:-"$EDL_BIN --memory=ufs --loader=$LOADER"}

log() { echo "$(date +%T) $*" | tee -a "$LOG"; }
edl() { log "edl $*"; $EDL_CMD "$@" 2>&1 | { grep -v RuntimeWarning || true; } | tee -a "$LOG"; }

# 0. The backup must be complete and intact before anything is written.
for p in $PHYSICAL $LOGICAL; do
    [ -s "$BACKUP/$p.img" ] || { log "missing $BACKUP/$p.img"; exit 1; }
done
if [ -f "$BACKUP/SHA256SUMS" ]; then
    log "verifying $BACKUP/SHA256SUMS"
    (cd "$BACKUP" && sha256sum -c --quiet SHA256SUMS) || { log "backup checksum FAILED"; exit 1; }
fi

# 1. GPT: the loader goes up here if the unit is still in Sahara; then super's byte offset.
edl printgpt > "$WORK/gpt.txt" || true
grep -q "^super:" "$WORK/gpt.txt" || { log "no GPT read; unit not in EDL or loader refused (press RST, retry)"; exit 1; }
SUPER_OFFSET=$(( $(sed -n 's/^super:.*Offset \(0x[0-9a-f]*\),.*/\1/p' "$WORK/gpt.txt") ))
for p in $PHYSICAL; do
    grep -q "^${p}_${SLOT}:" "$WORK/gpt.txt" || { log "GPT has no ${p}_${SLOT}"; exit 1; }
done
log "super at byte $SUPER_OFFSET (sector $((SUPER_OFFSET / UFS_SECTOR)))"

if [ $YES -eq 0 ]; then
    read -r -p "Write $BACKUP onto slot $SLOT of the unit? [y/N] " a
    [ "$a" = y ] || exit 1
fi

# 2. Physical partitions: straight writes by GPT name.
for p in $PHYSICAL; do
    edl w "${p}_${SLOT}" "$BACKUP/$p.img"
done

# 3. Logical partitions: read super's metadata, plan writes into its extents, run the plan.
if [ $FULL_SUPER -eq 1 ]; then
    edl r super "$WORK/super-current.img"
else
    edl rs $((SUPER_OFFSET / UFS_SECTOR)) $((SUPER_META_BYTES / UFS_SECTOR)) "$WORK/super-current.img"
fi
lpdump "$WORK/super-current.img" > "$WORK/lpdump.txt"
python3 "$HERE/edl-extents.py" --lpdump "$WORK/lpdump.txt" --super-offset "$SUPER_OFFSET" \
        --sectorsize $UFS_SECTOR --slot "$SLOT" --images "$BACKUP" --out "$WORK/plan.sh" 2>&1 | tee -a "$LOG"
log "plan: $(grep -c ' ws ' "$WORK/plan.sh") extent writes"
(cd "$WORK" && EDL="$EDL_CMD" bash plan.sh 2>&1 | { grep -v RuntimeWarning || true; } | tee -a "$LOG")

# 4. Reboot into the restored slot. A refused reset leaves the unit sitting in 9008 looking
# done, so it is fatal here.
if [ $RESET -eq 1 ]; then
    log "edl reset"
    $EDL_BIN reset --loader="$LOADER" 2>&1 | { grep -v RuntimeWarning || true; } | tee -a "$LOG"
    [ "${PIPESTATUS[0]}" = 0 ] || { log "reset REFUSED: unit still in EDL, run: $EDL_BIN reset --loader=$LOADER"; exit 1; }
fi
log "RESTORE DONE (log: $LOG)"
