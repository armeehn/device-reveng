#!/usr/bin/env bash
# The supervised OTA bench test (share carlauncher/os-ota.md, section 7), one verb at a time,
# against the unit's adb (Wi-Fi by default: the USB pigtail flips bits on large transfers).
#
#   bench-ota.sh facts             version, slots, snapshot state, update_engine status
#   bench-ota.sh push PAYLOAD_DIR  payload.bin onto the unit, sha256 checked against FILE_HASH,
#                                  staged in /data/ota_package and labelled for update_engine
#   bench-ota.sh apply PAYLOAD_DIR start update_engine on the staged file and follow it until
#                                  UPDATED_NEED_REBOOT (exit 0) or it falls back to IDLE (exit 1)
#   bench-ota.sh cancel            stop an apply; the running slot is never touched
#   bench-ota.sh arm               write riposte-bootcheck's marker (the slot the reboot enters),
#                                  so the first boot must prove itself or roll back
#
# The reboot is not a verb: it is the step a person watches (`adb reboot`, zero on the pigtail).
# PAYLOAD_DIR is a publish-os.sh output folder (payload.bin + payload_properties.txt).
# Env: UNIT (adb serial, default 10.0.10.14:5555), POLL_S (default 10), ADB (default adb).
set -euo pipefail
UNIT=${UNIT:-10.0.10.14:5555}
POLL_S=${POLL_S:-10}
ADB=${ADB:-adb}
readonly PKG_DIR=/data/ota_package
readonly STAGED=$PKG_DIR/payload.bin
readonly TMP=/data/local/tmp/riposte-ota-payload.bin
readonly MARK=/data/riposte/ota-pending
readonly NEED_REBOOT=6
readonly IDLE=0

die() { echo "bench-ota: $*" >&2; exit 1; }
su_sh() { "$ADB" -s "$UNIT" shell "su -c '$1'"; }
prop() { grep "^$1=" "$2/payload_properties.txt" | cut -d= -f2-; }

# The last onStatusUpdate code update_engine reports, e.g. 3 (DOWNLOADING), 6 (NEED_REBOOT).
status() {
  su_sh "timeout 3 update_engine_client --follow 2>&1" | sed -n 's/.*onStatusUpdate([^ ]* (\([0-9]*\)), \([0-9.]*\)).*/\1 \2/p' | tail -1
}

cmd_facts() {
  su_sh 'echo version=$(getprop ro.riposte.os.version) slot=$(getprop ro.boot.slot_suffix) cur=$(bootctl get-current-slot);
    for s in 0 1; do echo slot$s bootable=$(bootctl is-slot-bootable $s) good=$(bootctl is-slot-marked-successful $s); done;
    snapshotctl dump 2>/dev/null | head -1; ls -l /data/ota_package; cat /data/riposte/ota-pending 2>/dev/null'
  echo "update_engine: $(status)"
}

cmd_push() {
  local dir=${1:?push PAYLOAD_DIR} want have
  [ -f "$dir/payload.bin" ] && [ -f "$dir/payload_properties.txt" ] || die "$dir is not a payload folder"
  want=$(prop FILE_HASH "$dir" | base64 -d | od -An -tx1 | tr -d ' \n')
  "$ADB" -s "$UNIT" push "$dir/payload.bin" "$TMP" | tail -1
  have=$("$ADB" -s "$UNIT" shell sha256sum "$TMP" | cut -d' ' -f1)
  [ "$have" = "$want" ] || die "sha256 on the unit $have, payload says $want: push again"
  # -F: restorecon skips a tree whose relabel digest is unchanged, and the file would keep
  # shell_data_file, which update_engine may not read.
  su_sh "mv $TMP $STAGED && restorecon -RF $PKG_DIR >/dev/null 2>&1; ls -Z $STAGED"
  echo "bench-ota: staged, sha256 $have"
}

cmd_apply() {
  local dir=${1:?apply PAYLOAD_DIR} size headers code frac
  size=$(prop FILE_SIZE "$dir")
  headers=$(grep -v '^$' "$dir/payload_properties.txt")
  su_sh "restorecon -RF $PKG_DIR >/dev/null 2>&1; update_engine_client --update --payload=file://$STAGED --offset=0 --size=$size --headers=\"$headers\"" \
    || die "update_engine_client refused the apply"
  while sleep "$POLL_S"; do
    read -r code frac <<<"$(status)" || true
    echo "bench-ota: status ${code:-?} ${frac:-}"
    [ "${code:-}" = "$NEED_REBOOT" ] && { echo "bench-ota: UPDATED_NEED_REBOOT"; return 0; }
    if [ "${code:-}" = "$IDLE" ]; then
      su_sh 'logcat -d -b all | grep -E "update_engine.*(ERROR|ErrorCode)|avc.*update_engine" | tail -6'
      die "update_engine went back to IDLE: the apply failed"
    fi
  done
}

cmd_cancel() {
  su_sh 'update_engine_client --cancel 2>&1 | tail -1; snapshotctl dump 2>/dev/null | head -1' || true
}

cmd_arm() {
  su_sh "mkdir -p ${MARK%/*} && echo \$((1 - \$(bootctl get-current-slot))) > $MARK && cat $MARK"
}

case "${1:-}" in
  facts) cmd_facts ;;
  push) cmd_push "${2:-}" ;;
  apply) cmd_apply "${2:-}" ;;
  cancel) cmd_cancel ;;
  arm) cmd_arm ;;
  *) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
