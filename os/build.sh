#!/usr/bin/env bash
# Build Riposte OS images from a stock base.
#
#   base/{system,product}.img ──unpack──► trees ──overlay──► trees ──repack──► out/{system,product}.img
#   base/{vendor,boot,dtbo,vbmeta*}.img ─────────────────── copied verbatim ──► out/
#
# Usage: build.sh --base DIR --apps DIR --out DIR [--profile tier1|tier2|gsi] [--system IMG]
#                 [--version V] [--car-owner]
#   --apps holds carlauncher.apk (release-signed) and suite/*.apk.
#   --system replaces base/system.img (an AOSP GSI, .img or .img.xz); profile gsi removes the
#   whole OEM stack from product and implies --car-owner.
#   --car-owner sets ro.riposte.os.car_owner=1: McuOwner may take /dev/ttyHS1. ONLY for a
#   build without eventcenter (0.2); on a stock-derived image two readers split the stream.
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
readonly BOOTANIM=product/media/bootanimation.zip   # bootanimation looks in /product before /system
readonly PASSTHROUGH="vendor boot dtbo vbmeta vbmeta_system"
readonly EDITED="system product"

BASE="" APPS="" OUT="" PROFILE=tier1 VERSION="" CAR_OWNER=0 SYSTEM=""
while [ $# -gt 0 ]; do
  case "$1" in
    --base) BASE=$2; shift 2 ;;
    --apps) APPS=$2; shift 2 ;;
    --out) OUT=$2; shift 2 ;;
    --profile) PROFILE=$2; shift 2 ;;
    --version) VERSION=$2; shift 2 ;;
    --car-owner) CAR_OWNER=1; shift ;;
    --system) SYSTEM=$2; shift 2 ;;
    *) die "unknown arg $1" ;;
  esac
done
[ -n "$BASE" ] && [ -n "$APPS" ] && [ -n "$OUT" ] || die "need --base --apps --out"
[ "$(id -u)" = 0 ] || die "run as root (loop mounts)"
[ -f "$APPS/carlauncher.apk" ] || die "$APPS/carlauncher.apk missing"
case "$PROFILE" in tier1|tier2) ;; gsi) CAR_OWNER=1 ;; *) die "profile $PROFILE" ;; esac
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
if [ "$CAR_OWNER" = 1 ] && [ "$PROFILE" != gsi ]; then
  log "WARNING: --car-owner on a stock-derived system; eventcenter must not be in the image"
fi

# ---- 3. add apps ---------------------------------------------------------------
[ "$(apk_package "$APPS/carlauncher.apk")" = "$LAUNCHER_PKG" ] || die "carlauncher.apk is not $LAUNCHER_PKG"
install_apk "$SYS" "$LAUNCHER_DIR" "$LAUNCHER_NAME" "$APPS/carlauncher.apk"
SUITE_N=0
for apk in "$APPS"/suite/*.apk; do
  [ -f "$apk" ] || continue
  name=$(apk_package "$apk"); [ -n "$name" ] || die "not an APK: $apk"
  install_apk "$WORK/tree" "$SUITE_DIR" "$name" "$apk"
  SUITE_N=$((SUITE_N + 1))
done
log "installed $LAUNCHER_NAME + $SUITE_N suite apps"
if [ -f "$APPS/bootanimation.zip" ]; then      # rendered by bootanim/make.py where Pillow lives
  mkdir -p "$WORK/tree/$(dirname "$BOOTANIM")"
  cp "$APPS/bootanimation.zip" "$WORK/tree/$BOOTANIM"
  label_system_file "$WORK/tree/$(dirname "$BOOTANIM")" "$WORK/tree/$BOOTANIM"
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
RIPOSTE_OS_VERSION=$VERSION RIPOSTE_CAR_OWNER=$CAR_OWNER envsubst < "$HERE/overlay/props" >> "$SYS/build.prop"
log "version $VERSION"

# ---- 6. repack + passthrough ---------------------------------------------------
for part in $EDITED; do
  repack_image "${KIND[$part]}" "$WORK/tree/$part" "$OUT/$part.img" "$part"
  log "repacked $part.img ($(du -h "$OUT/$part.img" | cut -f1))"
done
for part in $PASSTHROUGH; do
  [ -f "$BASE/$part.img" ] && cp --reflink=auto "$BASE/$part.img" "$OUT/$part.img"
done
{
  echo "version=$VERSION"; echo "profile=$PROFILE"; echo "car_owner=$CAR_OWNER"; echo "built=$(date -u +%FT%TZ)"
  echo "launcher=$(apk_package "$APPS/carlauncher.apk") vc$("$AAPT2" dump badging "$APPS/carlauncher.apk" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")"
  echo "suite=$SUITE_N"; echo "system=${SYSTEM:-$BASE/system.img}"
  echo "removed=$(awk -F'\t' 'NF{print $1}' <<<"$REMOVE" | paste -sd,)"
} > "$OUT/MANIFEST"
(cd "$OUT" && sha256sum ./*.img > SHA256SUMS)
log "done: $OUT"
