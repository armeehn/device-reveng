#!/system/bin/sh
# One-shot per /data: make CarLauncher the HOME role holder so the first boot
# opens on it instead of the OEM chooser. The marker lives in /data so a
# factory reset re-runs it and a re-flash of /system does not.
MARK=/data/local/tmp/riposte-firstboot.done
LAUNCHER=com.ripostelabs.carlauncher

[ -f "$MARK" ] && exit 0

cmd role add-role-holder --user 0 android.app.role.HOME "$LAUNCHER"
# A head unit never blanks its own screen; AOSP's default would after 30 s to 2 min.
settings put system screen_off_timeout 2147483647
# ... and has no lock screen: AOSP booted 0.2 into a keyguard with "No SIM" over the launcher.
locksettings set-disabled true
settings put secure lockscreen.disabled 1
# What Setup Doctor would otherwise ask the owner to grant over adb: the runtime permissions
# and notification listeners a reinstall drops. Root here, so the first boot reads 7 of 7.
pm grant "$LAUNCHER" android.permission.ACCESS_FINE_LOCATION
# Location itself: a GSI boots with the master switch off, and every provider (the GPS clock
# source, the suite's GPS and Weather apps) reads nothing until it is on. The vendor image
# shipped it on.
cmd location set-location-enabled true
pm grant "$LAUNCHER" android.permission.BLUETOOTH_CONNECT
appops set "$LAUNCHER" WRITE_SETTINGS allow
for listener in media.MediaListenerService nav.NavListenerService notif.ShelfListenerService; do
  cmd notification allow_listener "$LAUNCHER/$LAUNCHER.$listener"
done
# Gesture navigation: swipe up is HOME, an edge swipe is BACK, only a pill on screen. The
# fascia keys cannot leave a foreign app on the owner path, so this is the way out of Settings.
cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural
# Root for the launcher without Magisk (owner approved 2026-09-19): the GSI's own phh-su, the
# Koush Superuser daemon, allows root, system, radio and shell outright and looks every other
# uid up in the Superuser app's database. That app is not installed, so an 'allow' row written
# here is the whole grant. Night mode, the status bar toggle, updates and key injection all
# shell out through it.
SU_DB_DIR=/data/data/me.phh.superuser/databases
LAUNCHER_UID=$(pm list packages -U "$LAUNCHER" | sed -n 's/.*uid://p')
mkdir -p "$SU_DB_DIR"
sqlite3 "$SU_DB_DIR/su.sqlite" "create table if not exists uid_policy (logging integer, desired_name text, username text, policy text, until integer, command text, uid integer, desired_uid integer, package_name text, name text, notification integer); delete from uid_policy where uid=$LAUNCHER_UID; insert into uid_policy (logging, desired_name, username, policy, until, command, uid, desired_uid, package_name, name, notification) values (0, 'root', 'launcher', 'allow', 0, '', $LAUNCHER_UID, 0, '$LAUNCHER', 'CarLauncher', 0);"
touch "$MARK"
