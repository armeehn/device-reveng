#!/usr/bin/env bash
# bench-blockfix.sh against an adb stub that maps the unit's block devices onto local files.
# Runs anywhere with bash. What it cannot prove: the pigtail itself (bench item).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)

T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/stub" "$T/img" "$T/dev" "$T/tmp"

fail() { echo "FAIL: $1"; exit 1; }

# adb -s UNIT <verb> ...: shell/exec-out run locally with the unit's paths mapped under $T.
# $T/adb-dead makes every call fail like an unplugged pigtail; $T/adb-short cuts exec-out
# streams after 8 KiB like a link that drops mid-read.
cat > "$T/stub/adb" <<STUB
#!/usr/bin/env bash
shift 2
verb=\$1; shift
map() { sed -e "s#/dev/block/mapper#$T/dev#g" -e "s#/data/local/tmp#$T/tmp#g" <<<"\$1"; }
[ -e "$T/adb-dead" ] && { echo "error: device 'da40e9ac' not found" >&2; exit 1; }
case \$verb in
  exec-out) if [ -e "$T/adb-short" ]; then bash -c "\$(map "\$*")" | head -c 8192; else bash -c "\$(map "\$*")"; fi ;;
  shell) bash -c "\$(map "\$*")" ;;
  push) cp "\$1" "\$(map "\$2")" ;;
esac
STUB
printf '#!/bin/sh\nexit 0\n' > "$T/stub/blockdev"
chmod +x "$T/stub/"*
export PATH="$T/stub:$PATH" PARTS=vendor

head -c 65536 /dev/urandom > "$T/img/vendor.img"
(cd "$T/img" && sha256sum vendor.img > SHA256SUMS)

run() { bash "$HERE/bench-blockfix.sh" "$T/img" > "$T/out" 2>&1; }

echo "== a matching partition is clean"
cp "$T/img/vendor.img" "$T/dev/vendor_b"
run || fail "exited $?: $(cat "$T/out")"
grep -q "vendor: clean" "$T/out" || fail "not reported clean: $(cat "$T/out")"

echo "== a flipped byte is patched"
printf '\xff' | dd of="$T/dev/vendor_b" bs=1 seek=12345 conv=notrunc 2>/dev/null
run || fail "exited $?: $(cat "$T/out")"
grep -q "vendor block 3: patched" "$T/out" || fail "block 3 not patched: $(cat "$T/out")"
cmp -s "$T/dev/vendor_b" "$T/img/vendor.img" || fail "partition still differs"

echo "== a dead link stops, never reports clean"
printf '\xff' | dd of="$T/dev/vendor_b" bs=1 seek=12345 conv=notrunc 2>/dev/null
touch "$T/adb-dead"
run && fail "exited 0 on a dead link: $(cat "$T/out")"
grep -q "vendor: clean" "$T/out" && fail "dead link reported clean"
rm "$T/adb-dead"

echo "== a read cut short stops, never reports clean"
touch "$T/adb-short"
run && fail "exited 0 on a short read: $(cat "$T/out")"
grep -q "vendor: clean" "$T/out" && fail "short read reported clean"
rm "$T/adb-short"

echo "PASS"
