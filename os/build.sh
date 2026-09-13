#!/usr/bin/env bash
# Build Riposte OS images from a stock base.
#
#   base/{system,product}.img ──unpack──► trees ──overlay──► trees ──repack──► out/{system,product}.img
#   base/{vendor,boot,dtbo,vbmeta*}.img ─────────────────── copied verbatim ──► out/
#
# Usage: build.sh --base DIR --apps DIR --out DIR [--profile tier1|tier2] [--version V]
#   --apps holds carlauncher.apk (release-signed) and suite/*.apk.
# Runs as root on x (loop mounts). See README.md for why each step exists.

set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=lib.sh
. "$HERE/lib.sh"

readonly LAUNCHER_PKG=com.ripostelabs.carlauncher
readonly LAUNCHER_DIR=system/priv-app
readonly LAUNCHER_NAME=CarLauncher
readonly SUITE_DIR=product/app
readonly PRIVAPP_XML=system/etc/permissions/privapp-permissions-ripostelabs.xml
readonly BOOTANIM=product/media/bootanimation.zip   # bootanimation looks in /product before /system
readonly PASSTHROUGH="vendor boot dtbo vbmeta vbmeta_system"
readonly EDITED="system product"

BASE="" APPS="" OUT="" PROFILE=tier1 VERSION=""
while [ $# -gt 0 ]; do
  case "$1" in
    --base) BASE=$2; shift 2 ;;
    --apps) APPS=$2; shift 2 ;;
    --out) OUT=$2; shift 2 ;;
    --profile) PROFILE=$2; shift 2 ;;
    --version) VERSION=$2; shift 2 ;;
    *) die "unknown arg $1" ;;
  esac
done
[ -n "$BASE" ] && [ -n "$APPS" ] && [ -n "$OUT" ] || die "need --base --apps --out"
[ "$(id -u)" = 0 ] || die "run as root (loop mounts)"
[ -f "$APPS/carlauncher.apk" ] || die "$APPS/carlauncher.apk missing"
AAPT2=${AAPT2:-$(ls /home/*/Android/Sdk/build-tools/*/aapt2 /opt/android-sdk/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)}
[ -x "${AAPT2:-/nonexistent}" ] || die "aapt2 not found; set AAPT2"

WORK=$(mktemp -d "${TMPDIR:-/var/tmp}/riposte-os.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"

apk_package() { "$AAPT2" dump packagename "$1" 2>/dev/null || true; }

# Resolve a package list file to "<part>/<dir>" paths present in the trees.
resolve_pkgs() { # listfile -> stdout: pkg<TAB>partition-relative dir (only those found)
  local pkgs apk pkg
  pkgs=$(sed 's/#.*//' "$1" | awk 'NF{print $1}')
  [ -n "$pkgs" ] || return 0
  while read -r apk; do
    pkg=$(apk_package "$apk")
    if grep -qx "$pkg" <<<"$pkgs"; then
      printf '%s\t%s\n' "$pkg" "$(dirname "${apk#"$WORK"/tree/}")"
    fi
  done < <(find "$WORK/tree" -name '*.apk' -path '*app/*')
}

# ---- 1. unpack ----------------------------------------------------------------
declare -A KIND
for part in $EDITED; do
  [ -f "$BASE/$part.img" ] || die "$BASE/$part.img missing"
  unsparse "$BASE/$part.img" "$WORK/$part.raw"
  KIND[$part]=$(unpack_image "$WORK/$part.raw" "$WORK/tree/$part")
  log "unpacked $part (${KIND[$part]})"
done

# ---- 2. remove ----------------------------------------------------------------
KEEP=$(resolve_pkgs "$HERE/overlay/keep")
REMOVE=$(resolve_pkgs "$HERE/overlay/remove.tier1")
[ "$PROFILE" = tier2 ] && REMOVE+=$'\n'$(resolve_pkgs "$HERE/overlay/remove.tier2")
while IFS=$'\t' read -r pkg dir; do
  [ -n "$pkg" ] || continue
  grep -q "^$pkg	" <<<"$KEEP" && die "refusing to remove kept package $pkg"
  rm -rf "${WORK:?}/tree/$dir"
  log "removed $pkg ($dir)"
done <<<"$REMOVE"

# ---- 3. add apps ---------------------------------------------------------------
[ "$(apk_package "$APPS/carlauncher.apk")" = "$LAUNCHER_PKG" ] || die "carlauncher.apk is not $LAUNCHER_PKG"
install_apk "$WORK/tree" "$LAUNCHER_DIR" "$LAUNCHER_NAME" "$APPS/carlauncher.apk"
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
mkdir -p "$WORK/tree/$(dirname "$PRIVAPP_XML")"
{
  echo '<?xml version="1.0" encoding="utf-8"?>'
  echo "<permissions>"
  echo "    <privapp-permissions package=\"$LAUNCHER_PKG\">"
  "$AAPT2" dump permissions "$APPS/carlauncher.apk" \
    | sed -n "s/^uses-permission: name='\([^']*\)'.*/        <permission name=\"\1\"\/>/p"
  echo "    </privapp-permissions>"
  echo "</permissions>"
} > "$WORK/tree/$PRIVAPP_XML"
label_system_file "$WORK/tree/$PRIVAPP_XML"

# ---- 5. static overlay + props -------------------------------------------------
(cd "$HERE/overlay" && find . -type f -path './system/*' -o -type f -path './product/*' | sed 's|^\./||') | while read -r f; do
  mkdir -p "$WORK/tree/$(dirname "$f")"
  cp "$HERE/overlay/$f" "$WORK/tree/$f"
  label_system_file "$WORK/tree/$f"
  case "$f" in system/bin/*) chmod 0755 "$WORK/tree/$f" ;; esac
done
VERSION=${VERSION:-0.1+$(date -u +%Y%m%d).vc$("$AAPT2" dump badging "$APPS/carlauncher.apk" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")}
RIPOSTE_OS_VERSION=$VERSION envsubst < "$HERE/overlay/props" >> "$WORK/tree/system/build.prop"
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
  echo "version=$VERSION"; echo "profile=$PROFILE"; echo "built=$(date -u +%FT%TZ)"
  echo "launcher=$(apk_package "$APPS/carlauncher.apk") vc$("$AAPT2" dump badging "$APPS/carlauncher.apk" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p")"
  echo "suite=$SUITE_N"; echo "removed=$(awk -F'\t' 'NF{print $1}' <<<"$REMOVE" | paste -sd,)"
} > "$OUT/MANIFEST"
(cd "$OUT" && sha256sum ./*.img > SHA256SUMS)
log "done: $OUT"
