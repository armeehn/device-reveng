#!/usr/bin/env bash
# A slot is marked good only once it has proved itself (riposte-bootcheck.sh says why). Checks
# the init wiring: update_verifier no longer marks at zygote-start, riposte.rc starts the check
# at boot_completed as root. Then runs the script against stubs: bootctl (slot state in files),
# pidof (one answer per call from a list), sleep, reboot and log. Runs anywhere with bash.
# What it cannot prove: that the unit's ABL honours the slot switch, a bench item.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
BIN=$HERE/overlay/system/bin
INIT=$HERE/overlay/system/etc/init
readonly SCRIPT=/system/bin/riposte-bootcheck.sh

fail() { echo "FAIL: $1"; exit 1; }

echo "== update_verifier keeps its service names but never runs the marking binary"
for svc in update_verifier_nonencrypted update_verifier; do
  grep -Eq "^service $svc " "$INIT/update_verifier.rc" || fail "init.rc exec_starts $svc; it must stay defined"
done
grep -q "/system/bin/update_verifier" "$INIT/update_verifier.rc" && fail "update_verifier.rc still runs the binary"

echo "== riposte.rc starts the check as root at boot_completed"
found=$(awk -v script="$SCRIPT" '
  /^service riposte_bootcheck / { svc = 1; if ($0 ~ script) { cmd = 1 }; next }
  /^[^ \t]/                     { svc = 0 }
  svc && $1 == "seclabel" && $2 == "u:r:su:s0" { label = 1 }
  /^on property:sys.boot_completed=1[ \t]*$/ { boot = 1; next }
  boot && $1 == "start" && $2 == "riposte_bootcheck" { started = 1 }
  END { if (cmd && label && started) print "yes" }
' "$INIT/riposte.rc")
[ "$found" = yes ] || fail "riposte.rc lacks riposte_bootcheck (su, $SCRIPT) started on sys.boot_completed=1"

T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/stub" "$T/data/riposte"
# bootctl as the unit's (0.2, boot HAL 1.1) answers, seen 2026-10-07: the is-* queries PRINT 1 or 0
# and always exit 0, whatever its help text says; marking is mark-boot-successful. Slot state as
# files: cur = running slot; marked-N, bootable-N exist when true.
cat > "$T/stub/bootctl" <<STUB
#!/usr/bin/env bash
S=$T/slots
echo "bootctl \$*" >> "$T/calls"
case "\$1" in
  get-current-slot) cat "\$S/cur" ;;
  is-slot-marked-successful) [ -e "\$S/marked-\$2" ] && echo 1 || echo 0 ;;
  is-slot-bootable) [ -e "\$S/bootable-\$2" ] && echo 1 || echo 0 ;;
  mark-boot-successful) touch "\$S/marked-\$(cat "\$S/cur")" ;;
  set-active-boot-slot) echo "\$2" > "\$S/active" ;;
  *) echo "unknown command \$1" >&2; exit 64 ;;
esac
STUB
# pidof: the next line of pids on each call, the last one once the list runs out.
cat > "$T/stub/pidof" <<STUB
#!/usr/bin/env bash
n=\$(( \$(cat "$T/pidn" 2>/dev/null || echo 0) + 1 )); echo \$n > "$T/pidn"
line=\$(sed -n "\${n}p" "$T/pids"); [ -n "\$line" ] || line=\$(tail -1 "$T/pids")
[ "\$line" = - ] && exit 1
echo "\$line"
STUB
printf '#!/usr/bin/env bash\necho "reboot $*" >> "%s/calls"\n' "$T" > "$T/stub/reboot"
printf '#!/usr/bin/env bash\n:\n' > "$T/stub/sleep"
cat > "$T/stub/log" <<STUB
#!/usr/bin/env bash
while [ "\$1" = -t ] || [ "\$1" = -p ]; do shift 2; done
echo "\$*" >> "$T/logcat"
STUB
chmod +x "$T/stub/"*
export PATH="$T/stub:$PATH" RIPOSTE_DATA="$T/data"
MARK=$T/data/riposte/ota-pending

# slots CUR [marked slots] [bootable slots]: a fresh state for one run.
slots() {
  rm -rf "$T/slots" "$T/calls" "$T/logcat" "$T/pidn" "$MARK"; mkdir -p "$T/slots"
  echo "$1" > "$T/slots/cur"
  for s in $2; do touch "$T/slots/marked-$s"; done
  for s in $3; do touch "$T/slots/bootable-$s"; done
  : > "$T/calls"; : > "$T/logcat"
}
run() { bash "$BIN/riposte-bootcheck.sh" > "$T/out" 2>&1 || fail "exited $?: $(cat "$T/out")"; }
marked() { [ -e "$T/slots/marked-$1" ]; }
rebooted() { grep -q '^reboot' "$T/calls"; }

echo "== slot already good: nothing to do, a stale marker goes"
slots 1 "1" "0 1"; echo 1 > "$MARK"; echo 4242 > "$T/pids"
run
grep -q mark-boot-successful "$T/calls" && fail "re-marked a good slot"
[ ! -e "$MARK" ] || fail "marker kept"

echo "== no OTA marker: marked at boot_completed, as update_verifier did"
slots 0 "" "0"; echo - > "$T/pids"
run
marked 0 || fail "slot 0 not marked"
rebooted && fail "rebooted a plain boot"

echo "== marker names the other slot (the bootloader fell back): marked, marker gone"
slots 1 "" "0 1"; echo 0 > "$MARK"; echo - > "$T/pids"
run
marked 1 || fail "slot 1 not marked"
[ ! -e "$MARK" ] || fail "marker kept"
rebooted && fail "rebooted after a fallback"

echo "== first boot of an OTA, launcher stays up: marked after the healthy window"
slots 0 "" "0 1"; echo 0 > "$MARK"; printf -- '-\n-\n777\n' > "$T/pids"
run
marked 0 || fail "healthy slot not marked: $(cat "$T/logcat")"
[ ! -e "$MARK" ] || fail "marker kept"
rebooted && fail "rebooted a healthy slot"
[ "$(cat "$T/pidn")" -ge 25 ] || fail "marked after $(cat "$T/pidn") polls, before the healthy window"

echo "== first boot of an OTA, launcher keeps dying: back to the old slot"
slots 0 "" "0 1"
echo 0 > "$MARK"
seq 100 300 > "$T/pids"
run
marked 0 && fail "marked a crash loop"
[ "$(cat "$T/slots/active" 2>/dev/null)" = 1 ] || fail "old slot not made active: $(cat "$T/calls")"
rebooted || fail "no reboot into the old slot"
[ ! -e "$MARK" ] || fail "marker kept: the old slot would roll back again"
grep -q "rolling back" "$T/logcat" || fail "rollback not logged"

echo "== first boot of an OTA, old slot not bootable: stay, unmarked, logged"
slots 0 "" "0"; echo 0 > "$MARK"; echo - > "$T/pids"
run
marked 0 && fail "marked a slot that never started the launcher"
rebooted && fail "rebooted into an unbootable slot"
grep -q "not bootable" "$T/logcat" || fail "stuck state not logged: $(cat "$T/logcat")"

echo "PASS"
