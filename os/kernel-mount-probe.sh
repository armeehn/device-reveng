#!/usr/bin/env bash
# Prove the unit's own kernel mounts what repack_image produces, before a flash.
#
#   x: repack_image ext4 (16 MiB, same mke2fs options as the real images)
#        │ adb push
#   unit: su -c 'losetup + mount -o ro'  ── mounts? ──► safe to flash / DO NOT FLASH
#
# Why: on 2026-09-15 images with host-default ext4 features crashed the unit into 900E at
# first-stage mount; a loop mount on the build host proved nothing about the 4.14 kernel.
# Usage: kernel-mount-probe.sh --adb SERIAL   (root on x, unit booted with Magisk root)

set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=lib.sh
. "$HERE/lib.sh"
readonly REMOTE=/data/local/tmp/riposte-probe.img
readonly MNT=/data/local/tmp/riposte-probe-mnt

SERIAL=""
while [ $# -gt 0 ]; do case "$1" in --adb) SERIAL=$2; shift 2 ;; *) die "unknown arg $1" ;; esac; done
[ -n "$SERIAL" ] || die "need --adb SERIAL"
[ "$(id -u)" = 0 ] || die "run as root (repack needs mke2fs -d as root for xattrs)"

W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
mkdir -p "$W/tree/etc"; printf 'probe\n' > "$W/tree/etc/riposte-probe"
head -c 4194304 /dev/urandom > "$W/tree/filler"          # so the image has real extents
repack_image ext4 "$W/tree" "$W/probe.img" system
log "probe image: $(dumpe2fs -h "$W/probe.img" 2>/dev/null | sed -n 's/^Filesystem features: *//p')"

a() { timeout 60 adb -s "$SERIAL" "$@"; }
a push "$W/probe.img" "$REMOTE" >/dev/null
result=$(a shell "su -c 'mkdir -p $MNT; L=\$(losetup -f); losetup \$L $REMOTE && mount -o ro \$L $MNT && cat $MNT/etc/riposte-probe; umount $MNT 2>/dev/null; losetup -d \$L 2>/dev/null; rm -f $REMOTE'" | tr -d '\r')
kver=$(a shell uname -r | tr -d '\r')
if [ "$result" = probe ]; then
  log "kernel $kver mounted the probe: this feature set is safe to flash"
else
  log "kernel $kver did NOT mount the probe ($result): DO NOT FLASH images from this repack"
  exit 1
fi
