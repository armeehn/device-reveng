#!/usr/bin/env bash
# Put a head unit on the estate tailnet, once. Run it from any host whose adb reaches the
# unit; riposte-uplink.sh on the unit does the login at its next start and deletes the key.
#
#   enroll.sh SERIAL LOGIN_SERVER INGEST_URL [NODE_NAME] < keyfile
#
# The key (single use, tagged tag:car) comes on stdin only, so it never shows in argv, ps or
# shell history, and it is written to a root-only file on the unit and nowhere else.
# INGEST_URL (http://<ingest tailnet address>:8797) is not secret; the launcher reads it.
set -euo pipefail

readonly DIR=/data/misc/riposte/tailscale
readonly ENDPOINT_FILE=/data/misc/riposte/uplink.endpoint

if [ $# -lt 3 ]; then
  echo "usage: $0 SERIAL LOGIN_SERVER INGEST_URL [NODE_NAME] < keyfile" >&2
  exit 2
fi
serial=$1 login=$2 ingest=$3 name=${4:-rav4}
IFS= read -r key || true
case "$key" in
  hskey-auth-*|tskey-auth-*) ;;
  *) echo "no auth key on stdin" >&2; exit 2 ;;
esac

# Root on the unit: the GSI's adbd runs as root on car-owner builds, else Magisk's su.
su_run() {
  if adb -s "$serial" shell id -u | grep -q '^0'; then
    adb -s "$serial" exec-in sh -c "$1"
  else
    adb -s "$serial" exec-in su -c "$1"
  fi
}

printf 'LOGIN_SERVER=%s\nAUTH_KEY=%s\nNODE_NAME=%s\n' "$login" "$key" "$name" \
  | su_run "umask 077; mkdir -p $DIR; chmod 700 $DIR; cat > $DIR/enroll.env"
printf '%s\n' "$ingest" | su_run "umask 022; cat > $ENDPOINT_FILE"
su_run "stop riposte_uplink; start riposte_uplink" < /dev/null || true
echo "enrolled $serial as $name; it joins at the next riposte_uplink start"
