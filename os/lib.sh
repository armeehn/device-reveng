#!/usr/bin/env bash
# Shared helpers for the Riposte OS image pipeline. Sourced, not run.
#
#   image (ext4 | erofs | sparse-wrapped) ──unpack──► tree/ ──overlay──► tree/ ──repack──► image
#
# Everything here runs as root on a Linux host with loop devices (x), never inside
# an unprivileged container: unpacking ext4 needs a loop mount.

set -euo pipefail

# Magic numbers from the on-disk formats.
readonly SPARSE_MAGIC="ed26ff3a"      # Android sparse header, offset 0, little-endian 0x3AFF26ED
readonly EXT4_MAGIC="53ef"            # ext4 superblock s_magic, offset 0x438
readonly EROFS_MAGIC="e2e1f5e0"       # EROFS super_block magic, offset 0x400
readonly BLOCK=4096
readonly SELINUX_SYSTEM_FILE="u:object_r:system_file:s0"
readonly SELINUX_PHHSU_EXEC="u:object_r:phhsu_exec:s0"   # init `exec` transitions it into su

log() { printf '[os] %s\n' "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

hex_at() { # file offset length -> lowercase hex bytes as stored
  od -An -tx1 -j "$2" -N "$3" "$1" | tr -d ' \n'
}

# Newest aapt2 from any SDK on the host, or nothing. A plain `ls glob` here exits 2 when a
# glob has no match, and under `set -e` that kills the caller mid-assignment.
find_aapt2() {
  local candidates=()
  local p
  for p in /home/*/Android/Sdk/build-tools/*/aapt2 /opt/android-sdk/build-tools/*/aapt2; do
    [ -x "$p" ] && candidates+=("$p")
  done
  [ ${#candidates[@]} -gt 0 ] || return 0
  printf '%s\n' "${candidates[@]}" | sort -V | tail -1
}

# Prints ext4 | erofs | sparse | unknown.
image_kind() {
  local img=$1
  [ "$(hex_at "$img" 0 4)" = "$SPARSE_MAGIC" ] && { echo sparse; return; }
  [ "$(hex_at "$img" $((0x438)) 2)" = "$EXT4_MAGIC" ] && { echo ext4; return; }
  [ "$(hex_at "$img" $((0x400)) 4)" = "$EROFS_MAGIC" ] && { echo erofs; return; }
  echo unknown
}

# Sparse images (what `fastboot` and OTA zips carry) become raw first.
unsparse() { # in out
  case "$1" in
    *.xz) xz -dc "$1" > "$2" ;;    # GSI releases ship as .img.xz
    *)
      if [ "$(image_kind "$1")" = sparse ]; then
        simg2img "$1" "$2"
      else
        cp --reflink=auto "$1" "$2"
      fi
      ;;
  esac
  [ "$(image_kind "$2")" != sparse ] || { simg2img "$2" "$2.raw" && mv "$2.raw" "$2"; }
}

# Extract an image to a directory, preserving mode, owner and xattrs
# (security.selinux, security.capability). The tree is what the overlay edits.
unpack_image() { # img tree
  local img=$1 tree=$2 kind mnt
  kind=$(image_kind "$img")
  mkdir -p "$tree"
  case "$kind" in
    ext4)
      mnt=$(mktemp -d)
      mount -o loop,ro "$img" "$mnt"
      # --preserve=all carries xattrs; --one-file-system keeps a nested mount out.
      cp -a --one-file-system "$mnt/." "$tree/"
      umount "$mnt"; rmdir "$mnt"
      ;;
    erofs)
      fsck.erofs --extract="$tree" --overwrite --preserve-perms --preserve-owner "$img" >/dev/null
      ;;
    *) die "unpack: $img is $kind" ;;
  esac
  echo "$kind"
}

# Where /system's content lives inside an unpacked system image. A stock non-system-as-root
# image puts build.prop at the root; a GSI is system-as-root and nests it under system/, with
# absolute symlinks (etc -> /system/etc) at the root that must not be written through.
system_root() { # tree-of-system-image -> path
  if [ -f "$1/system/build.prop" ] && [ ! -L "$1/system" ]; then
    echo "$1/system"
  else
    echo "$1"
  fi
}

# Bytes a tree will need as ext4: content + 15 % metadata/slack, rounded to blocks.
# Read-only ext4 without journal or reserved blocks needs ~2 % for inode tables and bitmaps;
# 6 % + 16 MiB keeps four images inside the unit's 6 GiB super once the GSI's APEXes are
# unpacked (115 % overflowed it by 0.2 GiB on 2026-09-18). mke2fs -d fails loudly if short.
ext4_size_for() { # tree
  local used
  used=$(du -sB1 --apparent-size "$1" | cut -f1)
  echo $(( (used * 106 / 100 / BLOCK + 4096) * BLOCK ))
}

# Rebuild an image from a tree in the same format the base used. ext4 output is
# the plain AOSP shape (no journal, 256-byte inodes, 0 % reserved) so fastbootd
# accepts it as a logical partition image; xattrs come from the tree.
repack_image() { # kind tree out mountpoint(label, e.g. system)
  local kind=$1 tree=$2 out=$3 name=$4
  rm -f "$out"
  case "$kind" in
    ext4)
      # Feature set copied from the vendor's own images (dumpe2fs on the OTA system.img): the
      # unit's 4.14 kernel mounts /system in first-stage init, where the host mke2fs defaults
      # (metadata_csum needs crc32c, 64bit, flex_bg, metadata_csum_seed) are not guaranteed.
      # A repack with those defaults crashed the unit into 900E on 2026-09-15.
      mke2fs -q -t ext4 -b $BLOCK -I 256 -m 0 \
        -O "^has_journal,^64bit,^flex_bg,^metadata_csum,^metadata_csum_seed,^resize_inode,uninit_bg" \
        -E lazy_itable_init=0,lazy_journal_init=0 -L "$name" -M "/$name" \
        -d "$tree" "$out" $(( $(ext4_size_for "$tree") / BLOCK ))
      e2fsck -fy "$out" >/dev/null 2>&1 || [ $? -le 1 ]
      ;;
    erofs)
      mkfs.erofs -zlz4hc --mount-point="/$name" "$out" "$tree" >/dev/null
      ;;
    *) die "repack: unsupported $kind" ;;
  esac
}

# A file we add must look like it was built into the image.
label_system_file() { # path...
  local p
  for p in "$@"; do
    chown root:root "$p"
    if [ -d "$p" ]; then chmod 0755 "$p"; else chmod 0644 "$p"; fi
    setfattr -n security.selinux -v "$SELINUX_SYSTEM_FILE" "$p" 2>/dev/null || true
  done
}

# Install an APK as <part>/<dir>/<Name>/<Name>.apk, the shape PackageManager scans.
install_apk() { # tree appdir Name apk
  local tree=$1 appdir=$2 name=$3 apk=$4 dst
  dst="$tree/$appdir/$name"
  mkdir -p "$dst"
  cp "$apk" "$dst/$name.apk"
  label_system_file "$dst" "$dst/$name.apk"
}
