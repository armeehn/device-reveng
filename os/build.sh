#!/usr/bin/env bash
# Build Riposte OS images from a stock base.
#
#   base/{system,product}.img ──unpack──► trees ──overlay──► trees ──repack──► out/{system,product}.img
#   base/{vendor,boot,dtbo,vbmeta*}.img ─────────────────── copied verbatim ──► out/
#
# Usage: build.sh --base DIR --apps DIR --out DIR [--profile tier1|tier2|gsi] [--system IMG]
#                 [--version V] [--car-owner] [--bench]
#   --apps holds carlauncher.apk (release-signed) and suite/*.apk.
#   --boot replaces base/boot.img (e.g. a magisk-patch.sh output for a rootable slot).
#   --system replaces base/system.img (an AOSP GSI, .img or .img.xz); profile gsi removes the
#   whole OEM stack from product and implies --car-owner.
#   --car-owner sets ro.riposte.os.car_owner=1: McuOwner may take /dev/ttyHS1. ONLY for a
#   build without eventcenter (0.2); on a stock-derived image two readers split the stream.
#   --bench keeps the @bench props (adb on Wi-Fi 5555, USB port in peripheral mode, persisted
#   logcat). Off by default: a car build must not answer adb on the car network.
# Runs as root on x (loop mounts). See README.md for why each step exists.

set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=lib.sh
. "$HERE/lib.sh"

readonly LAUNCHER_PKG=com.ripostelabs.carlauncher
readonly LAUNCHER_DIR=priv-app                 # under the system root, see system_root()
readonly LAUNCHER_NAME=CarLauncher
readonly SUITE_DIR=product/app
readonly PRIVAPP_XML=etc/permissions/privapp-permissions-ripostelabs.xml
readonly DEFPERM_XML=product/etc/default-permissions/default-permissions-ripostelabs.xml   # beside SUITE_DIR
readonly BOOTANIM=product/media/bootanimation.zip   # bootanimation looks in /product before /system
readonly PASSTHROUGH="vendor system_ext boot dtbo vbmeta vbmeta_system"   # one matched set, never mixed across builds
readonly EDITED="system product"

BASE="" APPS="" OUT="" PROFILE=tier1 VERSION="" CAR_OWNER=0 BT_CARKIT=0 SYSTEM="" BOOT="" BENCH=0
while [ $# -gt 0 ]; do
  case "$1" in
    --base) BASE=$2; shift 2 ;;
    --apps) APPS=$2; shift 2 ;;
    --out) OUT=$2; shift 2 ;;
    --profile) PROFILE=$2; shift 2 ;;
    --version) VERSION=$2; shift 2 ;;
    --car-owner) CAR_OWNER=1; shift ;;
    --bench) BENCH=1; shift ;;
    --system) SYSTEM=$2; shift 2 ;;
    --boot) BOOT=$2; shift 2 ;;
    *) die "unknown arg $1" ;;
  esac
done
[ -n "$BASE" ] && [ -n "$APPS" ] && [ -n "$OUT" ] || die "need --base --apps --out"
[ "$(id -u)" = 0 ] || die "run as root (loop mounts)"
[ -f "$APPS/carlauncher.apk" ] || die "$APPS/carlauncher.apk missing"
# gsi: we own the MCU port and the Bluetooth stack runs the car-kit roles (overlay/props).
case "$PROFILE" in tier1|tier2) ;; gsi) CAR_OWNER=1; BT_CARKIT=1 ;; *) die "profile $PROFILE" ;; esac
AAPT2=${AAPT2:-$(find_aapt2)}
[ -x "${AAPT2:-/nonexistent}" ] || die "aapt2 not found; set AAPT2"

WORK=$(mktemp -d "${TMPDIR:-/var/tmp}/riposte-os.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"

apk_package() { "$AAPT2" dump packagename "$1" 2>/dev/null || true; }

# Exact name, or a `prefix.*` line covering it.
pkg_listed() { # pkg list
  local line
  while read -r line; do
    case "$line" in
      *'*') [[ "$1" == "${line%\*}"* ]] && return 0 ;;
      *) [ "$1" = "$line" ] && return 0 ;;
    esac
  done <<<"$2"
  return 1
}

# Resolve a package list file to "<part>/<dir>" paths present in the trees.
resolve_pkgs() { # listfile -> stdout: pkg<TAB>partition-relative dir (only those found)
  local pkgs apk pkg
  pkgs=$(sed 's/#.*//' "$1" | awk 'NF{print $1}')
  [ -n "$pkgs" ] || return 0
  while read -r apk; do
    pkg=$(apk_package "$apk")
    [ -n "$pkg" ] || continue
    if pkg_listed "$pkg" "$pkgs"; then
      printf '%s\t%s\n' "$pkg" "$(dirname "${apk#"$WORK"/tree/}")"
    fi
  done < <(find "$WORK/tree" -name '*.apk' -path '*app/*')
}

# ---- 1. unpack ----------------------------------------------------------------
declare -A KIND
for part in $EDITED; do
  src="$BASE/$part.img"
  [ "$part" = system ] && [ -n "$SYSTEM" ] && src=$SYSTEM
  [ -f "$src" ] || die "$src missing"
  unsparse "$src" "$WORK/$part.raw"
  KIND[$part]=$(unpack_image "$WORK/$part.raw" "$WORK/tree/$part")
  log "unpacked $part (${KIND[$part]})"
done
SYS=$(system_root "$WORK/tree/system")
log "system root: ${SYS#"$WORK"/tree/}"

# ---- 2. remove ----------------------------------------------------------------
if [ "$PROFILE" = gsi ]; then
  KEEP=$(resolve_pkgs "$HERE/overlay/keep.gsi")
  REMOVE=$(resolve_pkgs "$HERE/overlay/remove.gsi")
else
  KEEP=$(resolve_pkgs "$HERE/overlay/keep")
  REMOVE=$(resolve_pkgs "$HERE/overlay/remove.tier1")
  [ "$PROFILE" = tier2 ] && REMOVE+=$'\n'$(resolve_pkgs "$HERE/overlay/remove.tier2")
fi
while IFS=$'\t' read -r pkg dir; do
  [ -n "$pkg" ] || continue
  grep -q "^$pkg	" <<<"$KEEP" && die "refusing to remove kept package $pkg"
  rm -rf "${WORK:?}/tree/$dir"
  log "removed $pkg ($dir)"
done <<<"$REMOVE"

if [ "$CAR_OWNER" = 1 ] && grep -q "^com.szchoiceway.eventcenter	" <<<"$KEEP"; then
  die "--car-owner with eventcenter in the image: two readers on one tty split the stream"
fi

# The GSI's compressed APEXes would be decompressed into /data at first boot; a full /data then
# leaves netd without its resolver and zygote in a restart loop (see decapex.py). Unpack them
# into /system/apex now so the boot path never touches /data for them.
if [ "$PROFILE" = gsi ] && [ -d "$SYS/apex" ]; then
  python3 "$HERE/decapex.py" "$SYS/apex" | while read -r l; do log "$l"; done
  for f in "$SYS"/apex/*.apex; do label_system_file "$f"; done
fi
if [ "$CAR_OWNER" = 1 ] && [ "$PROFILE" != gsi ]; then
  log "WARNING: --car-owner on a stock-derived system; eventcenter must not be in the image"
fi

# ---- 3. add apps ---------------------------------------------------------------
[ "$(apk_package "$APPS/carlauncher.apk")" = "$LAUNCHER_PKG" ] || die "carlauncher.apk is not $LAUNCHER_PKG"
install_apk "$SYS" "$LAUNCHER_DIR" "$LAUNCHER_NAME" "$APPS/carlauncher.apk"
# Where /product really is at runtime. A GSI ships its own /system/product and links /product
# to it, so the super's product partition never mounts there (unit, 2026-09-19): the suite and
# the boot animation go into the system image on that profile.
PRODUCT_ROOT=$WORK/tree
[ "$PROFILE" = gsi ] && PRODUCT_ROOT=$SYS
SUITE_N=0
for apk in "$APPS"/suite/*.apk; do
  [ -f "$apk" ] || continue
  name=$(apk_package "$apk"); [ -n "$name" ] || die "not an APK: $apk"
  install_apk "$PRODUCT_ROOT" "$SUITE_DIR" "$name" "$apk"
  SUITE_N=$((SUITE_N + 1))
done
log "installed $LAUNCHER_NAME + $SUITE_N suite apps ($([ "$PROFILE" = gsi ] && echo "system image's" || echo "product image's") $SUITE_DIR)"

# Default grants for the launcher and the suite: a head unit has no one to tap a permission
# prompt, and 14 of the 28 apps opened on one at first launch, the launcher on the camera one
# (bench, 2026-09-19). The framework grants what is
# listed here at the first boot of a /data (DefaultPermissionGrantPolicy reads every
# etc/default-permissions/*.xml on the product partition); non-runtime names are logged and
# skipped, so every requested permission goes in. fixed="false" keeps them user-changeable.
mkdir -p "$PRODUCT_ROOT/$(dirname "$DEFPERM_XML")"
{
  echo '<?xml version="1.0" encoding="utf-8"?>'
  echo "<exceptions>"
  for apk in "$APPS/carlauncher.apk" "$APPS"/suite/*.apk; do
    [ -f "$apk" ] || continue
    echo "    <exception package=\"$(apk_package "$apk")\">"
    perms=$("$AAPT2" dump permissions "$apk" | sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p")
    # Android 14 pairs READ_MEDIA_IMAGES/VIDEO with an implicit READ_MEDIA_VISUAL_USER_SELECTED;
    # with only the former granted the picker dialog still opens (bench, 2026-09-19).
    case "$perms" in *READ_MEDIA_IMAGES*|*READ_MEDIA_VIDEO*) perms="$perms
android.permission.READ_MEDIA_VISUAL_USER_SELECTED" ;; esac
    for perm in $perms; do echo "        <permission name=\"$perm\" fixed=\"false\"/>"; done
    echo "    </exception>"
  done
  echo "</exceptions>"
} > "$PRODUCT_ROOT/$DEFPERM_XML"
label_system_file "$PRODUCT_ROOT/$(dirname "$DEFPERM_XML")" "$PRODUCT_ROOT/$DEFPERM_XML"
log "default grants for the launcher + $SUITE_N suite apps"
if [ -f "$APPS/bootanimation.zip" ]; then      # rendered by bootanim/make.py where Pillow lives
  mkdir -p "$PRODUCT_ROOT/$(dirname "$BOOTANIM")"
  cp "$APPS/bootanimation.zip" "$PRODUCT_ROOT/$BOOTANIM"
  label_system_file "$PRODUCT_ROOT/$(dirname "$BOOTANIM")" "$PRODUCT_ROOT/$BOOTANIM"
  log "installed boot animation"
fi

# ---- 4. privapp allowlist ------------------------------------------------------
# ro.control_privapp_permissions=enforce: a priv-app requesting a privileged
# permission missing here stops the boot. Every requested permission goes in;
# entries for non-privileged ones are ignored by the framework.
mkdir -p "$SYS/$(dirname "$PRIVAPP_XML")"
{
  echo '<?xml version="1.0" encoding="utf-8"?>'
  echo "<permissions>"
  echo "    <privapp-permissions package=\"$LAUNCHER_PKG\">"
  "$AAPT2" dump permissions "$APPS/carlauncher.apk" \
    | sed -n "s/^uses-permission: name='\([^']*\)'.*/        <permission name=\"\1\"\/>/p"
  echo "    </privapp-permissions>"
  echo "</permissions>"
} > "$SYS/$PRIVAPP_XML"
label_system_file "$SYS/$PRIVAPP_XML"

# ---- 5. static overlay + props -------------------------------------------------
# overlay/system/... lands under the system root, overlay/product/... under product.
(cd "$HERE/overlay" && find . -type f -path './system/*' -o -type f -path './product/*' | sed 's|^\./||') | while read -r f; do
  case "$f" in
    system/*) dst="$SYS/${f#system/}" ;;
    *) dst="$WORK/tree/$f" ;;
  esac
  mkdir -p "$(dirname "$dst")"
  cp "$HERE/overlay/$f" "$dst"
  label_system_file "$dst"
  case "$f" in system/bin/*) chmod 0755 "$dst" ;; esac
done
MILESTONE=$([ "$PROFILE" = gsi ] && echo 0.2 || echo 0.1)   # 0.1 stock re-mastered, 0.2 GSI base
VERSION=${VERSION:-$MILESTONE+$(date -u +%Y%m%d).vc$("$AAPT2" dump badging "$APPS/carlauncher.apk" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")}
# `@carkit ` lines are kept (tag stripped) only when the stack runs the car-kit roles; `@gsi `
# lines only on the AOSP GSI base.
CARKIT_SED=$([ "$BT_CARKIT" = 1 ] && echo 's/^@carkit //' || echo '/^@carkit /d')
GSI_SED=$([ "$PROFILE" = gsi ] && echo 's/^@gsi //' || echo '/^@gsi /d')
# `@bench ` lines (adb over Wi-Fi, the USB port held in peripheral mode, persisted logcat) are
# for the bench only: a car build must not answer adb on the car's network.
BENCH_SED=$([ "$BENCH" = 1 ] && echo 's/^@bench //' || echo '/^@bench /d')
RIPOSTE_OS_VERSION=$VERSION RIPOSTE_CAR_OWNER=$CAR_OWNER RIPOSTE_BT_CARKIT=$BT_CARKIT \
  envsubst < "$HERE/overlay/props" | sed -e "$CARKIT_SED" -e "$GSI_SED" -e "$BENCH_SED" >> "$SYS/build.prop"
log "version $VERSION"

# ---- 6. repack + passthrough ---------------------------------------------------
for part in $EDITED; do
  repack_image "${KIND[$part]}" "$WORK/tree/$part" "$OUT/$part.img" "$part"
  log "repacked $part.img ($(du -h "$OUT/$part.img" | cut -f1))"
done
for part in $PASSTHROUGH; do
  src="$BASE/$part.img"
  [ "$part" = boot ] && [ -n "$BOOT" ] && src=$BOOT
  [ -f "$src" ] && cp --reflink=auto "$src" "$OUT/$part.img"
done
{
  echo "version=$VERSION"; echo "profile=$PROFILE"; echo "car_owner=$CAR_OWNER"; echo "bt_carkit=$BT_CARKIT"; echo "bench=$BENCH"; echo "built=$(date -u +%FT%TZ)"
  echo "launcher=$(apk_package "$APPS/carlauncher.apk") vc$("$AAPT2" dump badging "$APPS/carlauncher.apk" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")"
  echo "suite=$SUITE_N"; echo "system=${SYSTEM:-$BASE/system.img}"; echo "boot=${BOOT:-$BASE/boot.img}"
  echo "removed=$(awk -F'\t' 'NF{print $1}' <<<"$REMOVE" | paste -sd,)"
} > "$OUT/MANIFEST"
(cd "$OUT" && sha256sum ./*.img > SHA256SUMS)
log "done: $OUT"
