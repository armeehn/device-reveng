#!/system/bin/sh
# The logcat ring on flash, for a car nobody sits in with a laptop. logd's own buffers roll
# over in minutes, so a reverse attempt, a wheel key or a CarPlay session tried between two
# plug-ins used to leave nothing (CrashLog.kt says the same of crashes). This copy survives
# until rav4-usb-update pulls it, or `adb pull /data/riposte/log` does.
#
#   logd ──logcat -b all -f──▶ /data/riposte/log/logcat.txt          live, up to ROTATE_KB
#                              /data/riposte/log/logcat.txt.1 .. .N  rotated, .N the oldest
#                              /data/riposte/log/boots.txt           riposte-logring-mark.sh
#
# Sizes, from `logcat --help`: "-r, --rotate-kbytes=<n>  Rotate log every <n> kbytes
# (requires -f)" and "-n, --rotate-count=<n>  Sets max number of rotated logs to <n>,
# default 4". 8 MiB x (1 live + 12 rotated) = 104 MiB at most; a day of the launcher at
# INFO is well under one file. No `oneshot` in riposte.rc, so init restarts this
# when logcat exits (a logd restart costs the gap, never the ring).
#
# Readers without su: adb runs as uid shell and adbd may read shell_data_file (adbd.te:
# `allow adbd shell_data_file:file r_file_perms`, the /data/local/tmp rule), and nothing
# else under /data. Hence root:shell, the setgid bit so rotated files inherit the group, 0640
# files through the umask, and the label. The launcher reads through its root shell. Init
# services run as u:r:su:s0 here (riposte.rc, the #279 pattern), a permissive domain on the
# GSI, so the mkdir, chown and chcon below need no policy of their own.
[ "$(getprop ro.riposte.os.car_owner)" = 1 ] || exit 0
BASE=/data/riposte
DIR=$BASE/log
FILE=$DIR/logcat.txt
ROTATE_KB=8192
ROTATE_COUNT=12
READER_GROUP=shell
LABEL=u:object_r:shell_data_file:s0

umask 027
mkdir -p "$DIR"
chmod 0755 "$BASE"
chown root:$READER_GROUP "$DIR"
chmod 2770 "$DIR"
chcon "$LABEL" "$DIR"

exec logcat -b all -v threadtime -f "$FILE" -r "$ROTATE_KB" -n "$ROTATE_COUNT"
