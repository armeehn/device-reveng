#!/usr/bin/env bash
# dump-base.sh against a fake adb whose "unit" is a directory of block files. Proves: slotted
# partitions (modem_b, bluetooth_b) resolve by name, a run stops after --max-chunks so a timer
# tick releases the car lock, an interrupted transfer or a short read never banks a chunk, a
# resumed run fetches nothing twice, and the assembled image equals the partition. Read-only:
# nothing the script sends to the unit writes. What it cannot prove: Magisk su quoting and
# the real link speed (the car runs the same commands for vbmeta, dtbo and boot since 2026-09).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/stub" "$T/unit/dev/block/by-name" "$T/out"
fail() { echo "FAIL: $*"; exit 1; }

readonly MIB=1048576
readonly MODEM_MIB=40      # three 16 MiB chunks, the last one partial
readonly BT_MIB=1
head -c $((MODEM_MIB * MIB)) /dev/urandom > "$T/unit/dev/block/by-name/modem_b"
head -c $((BT_MIB * MIB)) /dev/urandom > "$T/unit/dev/block/by-name/bluetooth_b"

# adb -s SERIAL shell|exec-out CMD... runs CMD in bash with /dev/block under $T/unit and
# su, getprop, blockdev faked. Faults: FAKE_CUT=1 cuts an exec-out stream halfway and fails,
# FAKE_NO_SLOT=1 makes ls of a slotted by-name path print nothing (a flaky su reply).
cat > "$T/stub/adb" <<STUB
#!/usr/bin/env bash
echo "\$*" >> "$T/adb.log"
[ "\$1" = connect ] && exit 0
shift 2
verb=\$1; shift
case "\$verb" in
    get-state) echo device; exit 0 ;;
    shell|exec-out) ;;
    *) exit 1 ;;
esac
cmd=\$(printf '%s ' "\$@" | sed "s#/dev/block#$T/unit/dev/block#g")
pre='su() { shift; eval "\$*"; }
getprop() { case "\$1" in ro.boot.slot_suffix) echo _b ;; *) echo fake ;; esac; }
blockdev() { stat -c %s "\$2"; }'
if [ "\${FAKE_NO_SLOT:-}" = 1 ]; then
    pre="\$pre
ls() { case \"\\\$1\" in *_b) return 1 ;; *) command ls \"\\\$@\" ;; esac; }"
fi
if [ "\$verb" = exec-out ] && [ "\${FAKE_CUT:-}" = 1 ]; then
    bash -c "\$pre
\$cmd" | gzip -dc | head -c $((4 * MIB)) | gzip -1
    exit 1
fi
bash -c "\$pre
\$cmd"
STUB
chmod +x "$T/stub/adb"
export PATH="$T/stub:$PATH" RIPOSTE_OS_ENV=/dev/null

: > "$T/adb.log"
dump() { bash "$HERE/dump-base.sh" --serial FAKE --out "$T/out" --parts "bluetooth modem" "$@" > "$T/log" 2>&1; }
oks() { find "$T/out" -name '*.ok' | wc -l; }
echo "slot=_b" > "$T/out/BASE-INFO"

echo "== one chunk per run: bluetooth first, then the run stops"
dump --max-chunks 1 || fail "exited $?: $(cat "$T/log")"
[ "$(oks)" -eq 1 ] || fail "banked $(oks) chunks, want 1: $(cat "$T/log")"
cmp -s "$T/unit/dev/block/by-name/bluetooth_b" "$T/out/bluetooth.img" || fail "bluetooth.img differs"
grep -q "by-name/bluetooth_b" "$T/adb.log" || fail "did not read the slotted bluetooth_b"
[ ! -f "$T/out/modem.img" ] || fail "modem assembled after one chunk"
grep -q "modem: 0/3 chunks, incomplete" "$T/log" || fail "assemble summary: $(cat "$T/log")"

echo "== a cut transfer banks nothing"
if FAKE_CUT=1 dump; then fail "a cut transfer reported success"; fi
[ "$(oks)" -eq 1 ] || fail "a cut transfer banked a chunk"

echo "== a short read never banks a chunk"
if FAKE_NO_SLOT=1 dump; then fail "a run that read the wrong device reported success"; fi
[ "$(oks)" -eq 1 ] || fail "a short read banked a chunk: $(cat "$T/log")"

echo "== resume finishes modem and assembles it"
dump || fail "resume exited $?: $(cat "$T/log")"
cmp -s "$T/unit/dev/block/by-name/modem_b" "$T/out/modem.img" || fail "modem.img differs"
(cd "$T/out" && sha256sum -c --quiet modem.img.sha256 bluetooth.img.sha256) || fail "sidecar sums"

echo "== a finished dump fetches nothing again"
cp "$T/adb.log" "$T/all.log"
: > "$T/adb.log"
dump || fail "re-run exited $?: $(cat "$T/log")"
! grep -q exec-out "$T/adb.log" || fail "re-run fetched: $(cat "$T/adb.log")"

echo "== nothing sent to the unit writes"
cat "$T/adb.log" >> "$T/all.log"
! grep -qE "of=|rm |mv |setprop|reboot" "$T/all.log" || fail "write sent: $(grep -E "of=|rm |mv " "$T/all.log")"
echo "DUMP-BASE PASS"
