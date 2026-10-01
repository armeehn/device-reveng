#!/usr/bin/env bash
# The unit must suspend the way stock does: suspend-to-idle, never the deep "mem" state
# (riposte-sleep-mode.sh says why). Checks riposte.rc runs the script as root at boot, and
# runs the script against stubs: log and a sysfs prefix. Runs anywhere with bash and awk.
# What it cannot prove: the kernel's resume from s2idle at ACC on, a car item.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
BIN=$HERE/overlay/system/bin
RC=$HERE/overlay/system/etc/init/riposte.rc
readonly SCRIPT=/system/bin/riposte-sleep-mode.sh

fail() { echo "FAIL: $1"; exit 1; }

echo "== riposte.rc runs the script as root under a plain 'on boot'"
# Every build on this hardware needs it, so no property condition may hold it back.
found=$(awk -v script="$SCRIPT" '
  /^on /     { boot = ($0 ~ /^on boot[ \t]*$/); next }
  /^[^ \t#]/ { boot = 0 }
  boot && $1 == "exec_background" && $2 == "u:r:su:s0" && $3 == "root" && $0 ~ script { print "yes" }
' "$RC")
[ "$found" = yes ] || fail "$RC has no 'exec_background u:r:su:s0 root -- ... $SCRIPT' under 'on boot'"

T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/stub" "$T/sysfs/sys/power"
cat > "$T/stub/log" <<STUB
#!/usr/bin/env bash
while [ "\$1" = -t ] || [ "\$1" = -p ]; do shift 2; done
echo "\$*" >> "$T/logcat"
STUB
chmod +x "$T/stub/log"
export PATH="$T/stub:$PATH" RIPOSTE_SYSFS="$T/sysfs"
NODE=$T/sysfs/sys/power/mem_sleep

run() { : > "$T/logcat"; bash "$BIN/riposte-sleep-mode.sh" > "$T/out" 2>&1; }

echo "== deep is current: s2idle is written"
printf 's2idle [deep]\n' > "$NODE"
run || fail "exited $?: $(cat "$T/out")"
[ "$(cat "$NODE")" = s2idle ] || fail "node got '$(cat "$NODE")', want s2idle"
grep -q "suspend s2idle \[deep\] -> s2idle" "$T/logcat" || fail "switch not logged: $(cat "$T/logcat")"

echo "== s2idle is current: no write"
printf '[s2idle] deep\n' > "$NODE"
run || fail "exited $?"
[ "$(cat "$NODE")" = "[s2idle] deep" ] || fail "node rewritten to '$(cat "$NODE")'"
grep -q "already s2idle" "$T/logcat" || fail "no-op not logged: $(cat "$T/logcat")"

echo "== no node: nothing to choose, exit 0"
rm -f "$NODE"
run || fail "exited $? without the node"
[ ! -e "$NODE" ] || fail "node created"
grep -q "one sleep state" "$T/logcat" || fail "missing node not logged"

echo "== unwritable node: exit 1, logged"
printf 's2idle [deep]\n' > "$NODE"
chmod 444 "$NODE"
if [ "$(id -u)" != 0 ]; then
  run && fail "accepted a refused write"
  grep -q "could not write s2idle" "$T/logcat" || fail "refusal not logged: $(cat "$T/logcat")"
fi
chmod 644 "$NODE"

echo "PASS"
