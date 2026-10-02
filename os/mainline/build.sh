#!/usr/bin/env bash
# Build a mainline Linux boot image for the GT6-EAU that runs from RAM only: the panel shows
# the console, the bench laptop sees a USB serial shell and a network link. Nothing is
# flashed; the image is for `fastboot boot` (BENCH.md).
#
# Usage: os/mainline/build.sh LINUX_TREE STOCK_BOOT BUSYBOX OUT_DIR
#   LINUX_TREE  a v6.18 checkout (git clone --depth 1 -b v6.18 …/stable/linux.git)
#   STOCK_BOOT  the unit's stock boot.img: addresses and os_version are copied from it
#   BUSYBOX     a static aarch64 busybox (the toolbelt's, tools/tools.lock)
# Heavy: run it on the build server, not the desk host.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)

readonly BOARD=sm6125-choiceway-gt6eau
readonly LLVM_SUFFIX=${LLVM_SUFFIX:--19}   # clang-19, llvm-nm-19 … (Debian's versioned names)
readonly CMDLINE="console=tty0 loglevel=7 rdinit=/init"

[ $# = 4 ] || { sed -n 2,11p "$0"; exit 2; }
TREE=$1 STOCK=$2 BUSYBOX=$3 OUT=$4
mkdir -p "$OUT"
KBUILD="$OUT/kbuild"

# 1. the board: DTS and config fragment go into the tree, the DTB into its Makefile
cp "$HERE/$BOARD.dts" "$TREE/arch/arm64/boot/dts/qcom/"
cp "$HERE/gt6eau.config" "$TREE/arch/arm64/configs/"
MK="$TREE/arch/arm64/boot/dts/qcom/Makefile"
grep -q "$BOARD.dtb" "$MK" || echo "dtb-\$(CONFIG_ARCH_QCOM)	+= $BOARD.dtb" >> "$MK"

# 1b. the panel: our variant of the S6D7AA0 driver. --forward skips a tree that already has it.
patch -d "$TREE" -p1 --forward --silent -r - < "$HERE/panel-s6d7aa0-yuntang.patch" || \
  grep -q yuntang "$TREE/drivers/gpu/drm/panel/panel-samsung-s6d7aa0.c"

# 2. kernel and DTB
make -C "$TREE" -s ARCH=arm64 LLVM="$LLVM_SUFFIX" O="$KBUILD" defconfig gt6eau.config
make -C "$TREE" -s ARCH=arm64 LLVM="$LLVM_SUFFIX" O="$KBUILD" -j"$(nproc)" Image "qcom/$BOARD.dtb"

# 3. initramfs: busybox and the init script, nothing else
ROOT="$OUT/rootfs"
rm -rf "$ROOT"
mkdir -p "$ROOT"/{bin,dev,proc,sys,tmp}
install -m 755 "$BUSYBOX" "$ROOT/bin/busybox"
install -m 755 "$HERE/init" "$ROOT/init"
(cd "$ROOT" && find . | sort | cpio -o -H newc -R 0:0 --quiet | gzip -9) > "$OUT/initramfs.cpio.gz"

# 4. boot image, header v2 like the stock one
python3 "$HERE/mkboot_v2.py" "$STOCK" "$KBUILD/arch/arm64/boot/Image" "$OUT/initramfs.cpio.gz" \
  "$KBUILD/arch/arm64/boot/dts/qcom/$BOARD.dtb" "$CMDLINE" "$OUT/boot-mainline.img"
sha256sum "$OUT/boot-mainline.img" | tee "$OUT/boot-mainline.img.sha256"
echo "kernel $(cat "$KBUILD/include/config/kernel.release")"
