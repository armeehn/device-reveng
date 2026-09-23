#!/system/bin/sh
# Root for the launcher, every boot. Owner decision 2026-09-23: the launcher drives hardware
# (decoder nodes, key injection, night mode, updates), so its root grant is a feature of
# Riposte OS, not a workaround, and it must survive a /data wipe.
#
#   init (boot_completed, car_owner=1) ──► riposte-root.sh (root, u:r:su:s0)
#     1. uid of com.ripostelabs.carlauncher (pm)
#     2. seed the sudaemon 'allow' row if it is missing
#     3. probe: su -c "su -c id -u" AS THAT UID through sudaemon ──► "0" or a denial
#     4. logcat: "riposte-root: launcher granted (uid N)" | "riposte-root: NOT granted: why"
#
# The grant row: the GSI's sudaemon (/system/bin/phh-su --daemon, Koush Superuser) allows
# root, system, radio and shell outright and looks every other uid up in
# /data/data/me.phh.superuser/databases/su.sqlite, table uid_policy, policy 'allow'. The
# Superuser app that would write that table is not installed, so this row is the whole grant.
# A /data wipe empties it; this runs every boot, so the next boot re-seeds it.
LAUNCHER=com.ripostelabs.carlauncher
SU_DB_DIR=${RIPOSTE_SU_DB_DIR:-/data/data/me.phh.superuser/databases}
SU_DB=$SU_DB_DIR/su.sqlite
SU=${RIPOSTE_SU:-/system/bin/phh-su}
TAG=riposte-root
PROBE_TIMEOUT=5
UID_POLICY_DDL="create table if not exists uid_policy (logging integer, desired_name text, username text, policy text, until integer, command text, uid integer, desired_uid integer, package_name text, name text, notification integer);"

say() {
  log -t "$TAG" "$1"
  echo "$TAG: $1"
}

fail() {
  say "NOT granted: $1"
  exit 1
}

uid=$(pm list packages -U "$LAUNCHER" | sed -n 's/.*uid://p' | head -1)
[ -n "$uid" ] || fail "$LAUNCHER is not installed"

# Seed only when the row is gone (first boot, or after a /data wipe); an existing grant is
# left untouched so a later Superuser app could still edit it.
mkdir -p "$SU_DB_DIR" || fail "cannot create $SU_DB_DIR"
sqlite3 "$SU_DB" "$UID_POLICY_DDL" || fail "sqlite3 cannot open $SU_DB"
rows=$(sqlite3 "$SU_DB" "select count(*) from uid_policy where uid=$uid and policy='allow' and desired_uid=0;")
if [ "$rows" = 0 ]; then
  sqlite3 "$SU_DB" "delete from uid_policy where uid=$uid; insert into uid_policy (logging, desired_name, username, policy, until, command, uid, desired_uid, package_name, name, notification) values (0, 'root', 'launcher', 'allow', 0, '', $uid, 0, '$LAUNCHER', 'CarLauncher', 0);" \
    || fail "cannot seed the allow row for uid $uid in $SU_DB"
  say "seeded the allow row for uid $uid"
fi

# The proof is the daemon's answer, not the row: the outer su (already root) drops to the
# launcher's uid, the inner su asks sudaemon as that uid, the same call RootShell makes.
got=$(timeout $PROBE_TIMEOUT "$SU" -c "$SU -c 'id -u'" "$uid" 2>/dev/null)
if [ "$got" != 0 ]; then
  daemon=$(pidof phh-su >/dev/null 2>&1 && echo up || echo down)
  fail "sudaemon ($daemon) answered '$got' for uid $uid, row present"
fi

say "launcher granted (uid $uid)"
