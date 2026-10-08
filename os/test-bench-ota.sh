#!/usr/bin/env bash
# os/ota/bench-ota.sh against a fake adb: a push whose bytes differ on the unit is refused, the
# apply hands update_engine the forced relabel, the size and all four headers, and the follow
# loop ends on UPDATED_NEED_REBOOT (exit 0) or on a fall back to IDLE (exit 1). Runs anywhere
# with bash. What it cannot prove: the unit itself, which is the bench test this script drives.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
fail() { echo "FAIL: $1"; exit 1; }

mkdir -p "$T/pay"
head -c 4096 /dev/urandom > "$T/pay/payload.bin"
hash=$(sha256sum "$T/pay/payload.bin" | cut -d' ' -f1 | sed 's/../\\x&/g')
printf 'FILE_HASH=%s\nFILE_SIZE=4096\nMETADATA_HASH=bWV0YQ==\nMETADATA_SIZE=12\n' \
  "$(printf "$hash" | base64)" > "$T/pay/payload_properties.txt"

# Fake adb: push copies to unit-file (or corrupts it when CORRUPT=1); shell logs the command and
# answers sha256sum and update_engine_client --follow, the latter from the statuses list in order.
cat > "$T/adb" <<STUB
#!/usr/bin/env bash
shift 2
case "\$1" in
  push) cp "\$2" "$T/unit-file"; [ "\${CORRUPT:-0}" = 1 ] && printf x >> "$T/unit-file"; echo "1 file pushed" ;;
  shell) shift; echo "\$*" | tr "\\n" " " >> "$T/calls"; echo >> "$T/calls"
    case "\$*" in
      sha256sum*) echo "\$(sha256sum "$T/unit-file" | cut -d' ' -f1)  /data/local/tmp/x" ;;
      *--follow*) n=\$(( \$(cat "$T/n" 2>/dev/null || echo 0) + 1 )); echo \$n > "$T/n"
                  echo "[INFO:x] \$(sed -n "\${n}p" "$T/statuses")" ;;
    esac ;;
esac
STUB
chmod +x "$T/adb"
export ADB=$T/adb UNIT=fake POLL_S=0
run() { bash "$HERE/ota/bench-ota.sh" "$@" > "$T/out" 2>&1; }

echo "== a push that arrives different is refused"
CORRUPT=1 run push "$T/pay" && fail "accepted a corrupt push"
grep -q "push again" "$T/out" || fail "no reason given: $(cat "$T/out")"
grep -q "mv " "$T/calls" 2>/dev/null && fail "staged a corrupt payload"

echo "== a good push is staged with a forced relabel"
: > "$T/calls"
run push "$T/pay" || fail "push: $(cat "$T/out")"
grep -q "restorecon -RF /data/ota_package" "$T/calls" || fail "no forced relabel: $(cat "$T/calls")"

echo "== apply follows to UPDATED_NEED_REBOOT"
: > "$T/calls"; rm -f "$T/n"
printf '%s\n' 'onStatusUpdate(UPDATE_STATUS_DOWNLOADING (3), 0.25)' 'onStatusUpdate(UPDATE_STATUS_FINALIZING (5), 0.9)' \
  'onStatusUpdate(UPDATE_STATUS_UPDATED_NEED_REBOOT (6), 1)' > "$T/statuses"
run apply "$T/pay" || fail "apply: $(cat "$T/out")"
grep -q "UPDATED_NEED_REBOOT" "$T/out" || fail "end not reported"
update=$(grep -- "--update" "$T/calls")
for want in "restorecon -RF" "--payload=file:///data/ota_package/payload.bin" "--size=4096" \
            FILE_HASH= FILE_SIZE=4096 METADATA_HASH= METADATA_SIZE=12; do
  grep -qF -- "$want" <<<"$update" || fail "apply lacks $want: $update"
done

echo "== apply that falls back to IDLE fails"
rm -f "$T/n"
printf '%s\n' 'onStatusUpdate(UPDATE_STATUS_DOWNLOADING (3), 0.1)' 'onStatusUpdate(UPDATE_STATUS_IDLE (0), 0)' > "$T/statuses"
run apply "$T/pay" && fail "a failed apply exited 0"
grep -q "went back to IDLE" "$T/out" || fail "failure not said: $(cat "$T/out")"

echo "PASS"
