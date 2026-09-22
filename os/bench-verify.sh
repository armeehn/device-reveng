#!/usr/bin/env bash
# Prove a flash wrote what was sent: hash the first <image size> bytes of each logical
# partition on the unit and compare with the set's SHA256SUMS. fastboot has no payload
# checksum, and a marginal USB link once wrote twelve bad files into system_b without a single
# error (bench, 2026-09-19): the framework never came up, zygote32 died on SIGILL in a library
# whose bytes were wrong. Runs over adb as root; the bench build's adbd is up from init, so
# this works even when the framework is not.
#
# Usage: bench-verify.sh DIR            DIR holds the images and SHA256SUMS
# Env:   ADB (default adb), UNIT (adb serial, default da40e9ac), SLOT (default b)
set -euo pipefail

DIR=${1:?usage: bench-verify.sh DIR}
ADB=${ADB:-adb}
UNIT=${UNIT:-da40e9ac}
SLOT=${SLOT:-b}
readonly LOGICAL="system system_ext product vendor"

a() { "$ADB" -s "$UNIT" "$@"; }

a root >/dev/null 2>&1 || true
sleep 3
# Over TCP (car-flash.sh via the unit's AP) `adb root` restarts adbd and the link drops: dial again.
case "$UNIT" in *:*) adb connect "$UNIT" >/dev/null 2>&1 ;; esac
a wait-for-device
[ "$(a shell id -u | tr -d '\r')" = 0 ] || { echo "[verify] adb is not root"; exit 1; }

bad=0
for p in $LOGICAL; do
  img="$DIR/$p.img"
  [ -f "$img" ] || { echo "[verify] no $img"; bad=$((bad + 1)); continue; }
  size=$(stat -c %s "$img")
  want=$(grep -E " (\./)?$p\.img\$" "$DIR/SHA256SUMS" | awk '{print $1}')
  have=$(a shell "head -c $size /dev/block/mapper/${p}_$SLOT | sha256sum" | awk '{print $1}')
  if [ "$want" = "$have" ]; then
    echo "[verify] $p ok"
  else
    echo "[verify] $p MISMATCH: partition $have, image $want"; bad=$((bad + 1))
  fi
done

[ "$bad" = 0 ] && echo "[verify] VERIFY PASS" || { echo "[verify] $bad partition(s) differ: reflash"; exit 1; }
