#!/usr/bin/env bash
# Flash a Riposte OS set into the head unit IN THE CAR, from the laptop on the 4PIN USB, and
# prove it. Run this as soon as the cable is in; it waits for the unit and does the rest.
#
#   set on the laptop ──sha256──▶ unit over USB adb ──▶ (AP password kept) ──▶ adb reboot fastboot
#   ──▶ fastboot-flash-set.sh ──▶ boot ──▶ adb over USB, else over the unit's own CarPlay AP
#   ──▶ bench-verify.sh (every partition hashed; the pigtail flips bits and says nothing)
#   ──▶ bench-blockfix.sh on a mismatch ──▶ first diagnostics recording pulled after a few minutes
#
# Usage: car-flash.sh [DIR]          DIR defaults to ~/rav4-headunit/os/0.2 (the car build)
#        car-flash.sh --verify-only  skip the flash: verify and pull diagnostics from a booted unit
# Env:   UNIT (adb USB serial, default da40e9ac)
#
# Why the AP: a car build hands the 4PIN port back to the host role at boot_completed (for the
# CANable), so adb over USB is only there during the boot animation. The unit's own 5 GHz AP
# (RAV4-CarPlay) stays up, its password survives the flash (/data), and adb listens on 5555.
set -uo pipefail
OS_DIR=${OS_DIR:-$HOME/rav4-headunit/os}
DIR=${1:-$OS_DIR/0.2}
UNIT=${UNIT:-da40e9ac}
readonly FASTBOOTD_USB_ID="18d1:4ee0"
readonly ADB_PORT=5555
readonly CAPTURES=$HOME/rav4-headunit/captures
readonly AP_FILE=$HOME/rav4-headunit/car-ap.txt
readonly BOOT_WAIT_S=300
readonly DIAG_SETTLE_S=180
VERIFY_ONLY=0
[ "${1:-}" = --verify-only ] && { VERIFY_ONLY=1; DIR=$OS_DIR/0.2; }

log() { echo "[car $(date +%T)] $*"; }
die() { log "ERROR: $*"; exit 1; }
a() { adb -s "$UNIT" "$@"; }
usb_present() { adb devices | grep -q "^$UNIT[[:space:]]"; }

# ---- 1. the set ---------------------------------------------------------------------------
[ -f "$DIR/SHA256SUMS" ] || die "no set at $DIR (rsync it from x first)"
(cd "$DIR" && sha256sum -c --quiet SHA256SUMS) || die "set at $DIR is damaged"
log "set $(grep ^version= "$DIR/MANIFEST" | cut -d= -f2) checked ($(grep ^bench= "$DIR/MANIFEST"))"
[ "$(grep ^bench= "$DIR/MANIFEST")" = "bench=0" ] || log "WARNING: this is a bench build; the car needs bench=0"

# ---- 2. the unit over USB, and its AP password before the flash ----------------------------
if [ "$VERIFY_ONLY" = 0 ]; then
  log "waiting for the unit on USB (adb serial $UNIT)"
  until usb_present; do sleep 3; done
  log "unit: $(a shell getprop ro.riposte.os.version | tr -d '\r') on slot $(a shell getprop ro.boot.slot_suffix | tr -d '\r')"
  ssid=$(a shell getprop riposte.ap.ssid | tr -d '\r')
  psk=$(a shell su -c 'cat /data/misc/riposte/ap.psk' 2>/dev/null | tr -d '\r')
  if [ -n "$ssid" ] && [ -n "$psk" ]; then
    printf 'ssid=%s\npsk=%s\n' "$ssid" "$psk" > "$AP_FILE"; chmod 600 "$AP_FILE"
    log "AP $ssid password kept in $AP_FILE"
  else
    log "no AP password read (the unit will still be reachable over USB during boot)"
  fi

  # ---- 3. flash ---------------------------------------------------------------------------
  log "rebooting to fastbootd"
  a reboot fastboot
  for _ in $(seq 1 30); do sleep 5; lsusb | grep -q "$FASTBOOTD_USB_ID" && break; done
  lsusb | grep -q "$FASTBOOTD_USB_ID" || die "fastbootd never enumerated"
  # A failed write leaves the unit in fastbootd with a half-written slot: never reboot it, rerun.
  bash "$OS_DIR/fastboot-flash-set.sh" "$DIR" 2>&1 | grep -v '^Sending\|^Writing' | tail -12 | tee /dev/stderr | grep -q "FLASH-SET DONE" \
    || die "flash failed: the unit is still in fastbootd, rerun: bash $OS_DIR/fastboot-flash-set.sh $DIR"
fi

# ---- 4. reach the booted unit: USB while the animation runs, else its own AP ---------------
reach() {
  local waited=0
  while [ $waited -lt $BOOT_WAIT_S ]; do
    if usb_present; then echo "$UNIT"; return 0; fi
    sleep 5; waited=$((waited + 5))
    # After ~90 s the vendor has handed the port to the host: try the AP from here on.
    if [ $waited -ge 90 ] && [ -f "$AP_FILE" ]; then
      # shellcheck source=/dev/null
      . "$AP_FILE"
      if nmcli -t -f GENERAL.STATE dev show wlan0 2>/dev/null | grep -q connected \
         && nmcli -t -f active,ssid dev wifi 2>/dev/null | grep -q "^yes:$ssid$"; then :; else
        nmcli dev wifi connect "$ssid" password "$psk" >/dev/null 2>&1 || { sleep 5; continue; }
      fi
      gw=$(ip route show dev wlan0 2>/dev/null | awk '/default/{print $3; exit}')
      [ -n "$gw" ] || continue
      adb connect "$gw:$ADB_PORT" >/dev/null 2>&1
      if adb devices | grep -q "^$gw:$ADB_PORT[[:space:]]*device"; then echo "$gw:$ADB_PORT"; return 0; fi
    fi
  done
  return 1
}
log "waiting for the unit to boot (USB first, then the $( [ -f "$AP_FILE" ] && cut -d= -f2 "$AP_FILE" | head -1 || echo CarPlay ) AP)"
SERIAL=$(reach) || die "unit not reachable after ${BOOT_WAIT_S}s: join its AP by hand, then car-flash.sh --verify-only"
log "unit reachable as $SERIAL"
until adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | grep -q 1; do sleep 5; done
log "booted: $(adb -s "$SERIAL" shell getprop ro.riposte.os.version | tr -d '\r'), usb mode $(adb -s "$SERIAL" shell su -c 'cat /sys/devices/platform/soc/4e00000.ssusb/mode' 2>/dev/null | tr -d '\r')"

# ---- 5. prove the flash, repair what the cable flipped -------------------------------------
if ! UNIT=$SERIAL bash "$OS_DIR/bench-verify.sh" "$DIR"; then
  log "repairing the differing blocks"
  UNIT=$SERIAL bash "$OS_DIR/bench-blockfix.sh" "$DIR"
  UNIT=$SERIAL bash "$OS_DIR/bench-verify.sh" "$DIR" || die "still differs after blockfix"
fi

# ---- 6. the first diagnostics recording ----------------------------------------------------
log "letting the recorder run ${DIAG_SETTLE_S}s (MCU handshake, USB devices, CarPlay), then pulling"
sleep "$DIAG_SETTLE_S"
out=$CAPTURES/diag-$(date +%Y%m%d-%H%M%S); mkdir -p "$out"
adb -s "$SERIAL" shell su -c 'tar -C /data/misc -cf /data/local/tmp/diag.tar riposte/diag logd 2>/dev/null; chmod 644 /data/local/tmp/diag.tar' >/dev/null
adb -s "$SERIAL" pull /data/local/tmp/diag.tar "$out/diag.tar" >/dev/null && tar -C "$out" -xf "$out/diag.tar" && rm -f "$out/diag.tar"
log "diagnostics in $out"
[ -f "$OS_DIR/diag-check.py" ] && python3 "$OS_DIR/diag-check.py" "$out/riposte/diag"
log "DONE. Send $out to x: scp -r $out sasha@x.hq:/z1-pool/share/carlauncher/diag/"
