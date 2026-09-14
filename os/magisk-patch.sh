#!/usr/bin/env bash
# Magisk-patch a boot image on the desk, the way the Magisk app does it on a phone.
#
#   Magisk APK ──extract──► magiskboot (x86_64) + magiskinit/magisk/init-ld (arm64) + boot_patch.sh
#   boot.img ──boot_patch.sh──► new-boot.img   (same kernel, Magisk in the ramdisk)
#
# Why: a slot flashed from a vendor OTA has a stock boot and no root, and the unit's own
# Magisk boot carries a different kernel. Patching the OTA's boot keeps the kernel matched
# to its vendor. Usage: magisk-patch.sh --in boot.img --out boot-magisk.img [--version v30.7]
# Defaults match the unit (Magisk 30.7, preinit on userdata, verity kept).

set -euo pipefail
readonly APK_URL_FMT='https://github.com/topjohnwu/Magisk/releases/download/%s/app-debug.apk'
readonly HOST_ABI=x86_64
readonly TARGET_ABI=arm64-v8a

IN="" OUT="" VERSION=v30.7 PREINIT=userdata
while [ $# -gt 0 ]; do
  case "$1" in
    --in) IN=$2; shift 2 ;;
    --out) OUT=$2; shift 2 ;;
    --version) VERSION=$2; shift 2 ;;
    --preinit) PREINIT=$2; shift 2 ;;
    *) echo "unknown arg $1" >&2; exit 2 ;;
  esac
done
[ -f "$IN" ] && [ -n "$OUT" ] || { echo "need --in boot.img --out file" >&2; exit 2; }

WORK=$(mktemp -d "${TMPDIR:-/var/tmp}/magisk-patch.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"
# shellcheck disable=SC2059
curl -sSL -o magisk.apk "$(printf "$APK_URL_FMT" "$VERSION")"
python3 -m zipfile -e magisk.apk x >/dev/null
cp x/assets/boot_patch.sh x/assets/util_functions.sh x/assets/stub.apk .
cp "x/lib/$HOST_ABI/libmagiskboot.so" magiskboot && chmod +x magiskboot
cp "x/lib/$TARGET_ABI/libmagiskinit.so" magiskinit
cp "x/lib/$TARGET_ABI/libmagisk.so" magisk
cp "x/lib/$TARGET_ABI/libinit-ld.so" init-ld
cp "$IN" boot.img

BOOTMODE=false PREINITDEVICE=$PREINIT KEEPVERITY=true KEEPFORCEENCRYPT=true PATCHVBMETAFLAG=false \
  sh ./boot_patch.sh boot.img >patch.log 2>&1 || { cat patch.log >&2; exit 1; }
[ -f new-boot.img ] || { cat patch.log >&2; echo "no new-boot.img produced" >&2; exit 1; }
cp new-boot.img "$OUT"
echo "$OUT: $(grep -c . patch.log) log lines, Magisk $VERSION, preinit $PREINIT"
