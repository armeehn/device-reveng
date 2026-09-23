#!/usr/bin/env bash
# riposte-root.sh and riposte-camera-mode.sh against stubs: pm, log, getprop, setprop and a
# phh-su whose daemon side reads the same su.sqlite the script seeds. Runs anywhere with
# bash, sqlite3 and timeout. What it cannot prove: sudaemon's real answer and the decoder's
# reaction, both bench items (`adb logcat -s riposte-root riposte-camera`).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
BIN=$HERE/overlay/system/bin

T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir -p "$T/stub" "$T/props"
LAUNCHER_UID=10123

fail() { echo "FAIL: $1"; exit 1; }

# ---- stubs ----------------------------------------------------------------------
# One file per property, so getprop/setprop round-trip and the test can inspect writes.
cat > "$T/stub/getprop" <<EOF
#!/usr/bin/env bash
cat "$T/props/\$1" 2>/dev/null || true
EOF
cat > "$T/stub/setprop" <<EOF
#!/usr/bin/env bash
printf %s "\$2" > "$T/props/\$1"
EOF
cat > "$T/stub/log" <<EOF
#!/usr/bin/env bash
shift 2; echo "\$*" >> "$T/logcat"
EOF
cat > "$T/stub/pm" <<EOF
#!/usr/bin/env bash
[ -f "$T/launcher-absent" ] && exit 0
echo "package:com.ripostelabs.carlauncher uid:$LAUNCHER_UID"
EOF
# phh-su: `-c CMD UID` (outer, already root) runs CMD as if it were UID; `-c CMD` (inner, as
# that uid) is the daemon call and answers from uid_policy the way sudaemon does.
cat > "$T/stub/phh-su" <<EOF
#!/usr/bin/env bash
cmd=\$2; uid=\${3:-}
if [ -n "\$uid" ]; then STUB_UID=\$uid bash -c "\$cmd"; exit; fi
rows=\$(sqlite3 "$T/db/su.sqlite" "select count(*) from uid_policy where uid=\$STUB_UID and policy='allow';" 2>/dev/null || echo 0)
[ "\$rows" -gt 0 ] || { echo "Permission denied" >&2; exit 1; }
bash -c "\$cmd"
EOF
# The probe's inner command is `id -u`; as root on the bench it prints 0.
cat > "$T/stub/id" <<'EOF'
#!/usr/bin/env bash
echo 0
EOF
chmod +x "$T/stub/"*
export PATH="$T/stub:$PATH"
export RIPOSTE_SU="$T/stub/phh-su" RIPOSTE_SU_DB_DIR="$T/db"

run_root() { : > "$T/logcat"; bash "$BIN/riposte-root.sh" > "$T/out" 2>&1; }

# ---- riposte-root.sh --------------------------------------------------------------
echo "== root: first boot, no database: seeds the row, daemon grants"
run_root || fail "root script exited $? on a clean /data: $(cat "$T/out")"
grep -q "seeded the allow row for uid $LAUNCHER_UID" "$T/logcat" || fail "no seed logged"
grep -q "launcher granted (uid $LAUNCHER_UID)" "$T/logcat" || fail "no grant logged: $(cat "$T/logcat")"
[ "$(sqlite3 "$T/db/su.sqlite" "select policy||':'||package_name||':'||desired_uid from uid_policy where uid=$LAUNCHER_UID;")" = "allow:com.ripostelabs.carlauncher:0" ] || fail "row shape"

echo "== root: second boot, row present: no re-seed, still granted"
run_root || fail "root script failed on a seeded /data"
grep -q "seeded" "$T/logcat" && fail "re-seeded an existing row"
grep -q "launcher granted" "$T/logcat" || fail "grant lost on second boot"
[ "$(sqlite3 "$T/db/su.sqlite" "select count(*) from uid_policy;")" = 1 ] || fail "duplicate rows"

echo "== root: /data wiped: the row comes back"
rm -rf "$T/db"
run_root || fail "root script failed after a wipe"
grep -q "seeded the allow row" "$T/logcat" || fail "no re-seed after a wipe"

echo "== root: daemon denies although the row is there: NOT granted with the reason"
cat > "$T/stub/phh-su-deny" <<'EOF'
#!/usr/bin/env bash
cmd=$2; uid=${3:-}
if [ -n "$uid" ]; then bash -c "$cmd"; exit; fi
echo "Permission denied" >&2; exit 1
EOF
chmod +x "$T/stub/phh-su-deny"
: > "$T/logcat"
if RIPOSTE_SU="$T/stub/phh-su-deny" bash "$BIN/riposte-root.sh" > "$T/out" 2>&1; then
  fail "exit 0 although the daemon denied"
fi
grep -q "NOT granted: sudaemon (down) answered '' for uid $LAUNCHER_UID, row present" "$T/logcat" || fail "denial reason missing: $(cat "$T/logcat")"

echo "== root: launcher not installed: NOT granted, nothing seeded"
rm -rf "$T/db"; touch "$T/launcher-absent"
run_root && fail "exit 0 without the launcher"
grep -q "NOT granted: com.ripostelabs.carlauncher is not installed" "$T/logcat" || fail "missing-launcher reason"
[ ! -e "$T/db/su.sqlite" ] || fail "seeded a row for no uid"
rm -f "$T/launcher-absent"

# ---- riposte-camera-mode.sh ----------------------------------------------------
export RIPOSTE_SYSFS="$T/sysfs"
PR2000="$T/sysfs/sys/pr2000/pr2000"
CCI="$T/sysfs/sys/devices/platform/soc/5c0c000.qcom,cci/5c0c000.qcom,cci:qcom,camera@0/pr2000"
RN6752="$T/sysfs/sys/devices/platform/vehicle/rn6752_mode"
mkdir -p "$(dirname "$PR2000")" "$(dirname "$CCI")" "$(dirname "$RN6752")"
run_cam() { : > "$T/logcat"; rm -f "$T/props/sys.pr2000.writable"; setprop persist.riposte.camera.mode "$1"; bash "$BIN/riposte-camera-mode.sh" > "$T/out" 2>&1; }

echo "== camera: mode 3 reaches every node that exists, after the unlock prop"
: > "$PR2000"; : > "$CCI"
run_cam 3 || fail "camera script exited $?"
[ "$(cat "$PR2000")" = v3 ] || fail "PR2000 node got '$(cat "$PR2000")', want v3"
[ "$(cat "$CCI")" = v3 ] || fail "CCI node got '$(cat "$CCI")', want v3"
[ ! -e "$RN6752" ] || fail "rn6752_mode created although absent"
[ "$(cat "$T/props/sys.pr2000.writable")" = 1 ] || fail "sys.pr2000.writable not set"
grep -q "mode 3 -> v3 on 2 node(s)" "$T/logcat" || fail "camera log: $(cat "$T/logcat")"

echo "== camera: the rn6752 node is written only when the board has it"
: > "$RN6752"
run_cam 8 || fail "camera script exited $?"
[ "$(cat "$RN6752")" = v8 ] || fail "rn6752_mode got '$(cat "$RN6752")'"
grep -q "on 3 node(s)" "$T/logcat" || fail "rn6752 not counted"

echo "== camera: out-of-range and junk values are refused before any write"
for bad in 9 -1 v3 "" abc; do
  run_cam "$bad" && fail "accepted '$bad'"
  [ "$(cat "$PR2000")" = v8 ] || fail "node changed on '$bad'"
  [ ! -e "$T/props/sys.pr2000.writable" ] || fail "unlock prop set on '$bad'"
done

echo "ROOT-CAMERA PASS"
