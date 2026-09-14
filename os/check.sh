#!/usr/bin/env bash
# Static verification of a built Riposte OS output, without a car.
#
# Usage: check.sh --base DIR --out DIR [--profile tier1|tier2|gsi] [--system IMG] [--suite N]
# Exit 0 when every assertion holds; each failure is printed, nothing is hidden.
# Runs as root on x (loop mounts).

set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=lib.sh
. "$HERE/lib.sh"

readonly LAUNCHER_PKG=com.ripostelabs.carlauncher
# Relative to the system root (system_root): system/ on stock, system/system/ on a GSI.
readonly LAUNCHER_APK=priv-app/CarLauncher/CarLauncher.apk
readonly PRIVAPP_XML=etc/permissions/privapp-permissions-ripostelabs.xml
readonly FRAMEWORK=framework/framework.jar
# Car-kit Bluetooth roles (overlay/props): on, off, and the class of device on a gsi output.
readonly BT_CARKIT_ON="bluetooth.profile.a2dp.sink.enabled bluetooth.profile.hfp.hf.enabled
  bluetooth.profile.avrcp.controller.enabled bluetooth.profile.pbap.client.enabled
  bluetooth.profile.map.client.enabled"
readonly BT_CARKIT_OFF="bluetooth.profile.a2dp.source.enabled bluetooth.profile.hfp.ag.enabled
  bluetooth.profile.avrcp.target.enabled"
readonly BT_CARKIT_COD=38,4,8

BASE="" OUT="" PROFILE=tier1 SUITE="" SYSTEM="" BOOT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --base) BASE=$2; shift 2 ;;
    --out) OUT=$2; shift 2 ;;
    --profile) PROFILE=$2; shift 2 ;;
    --suite) SUITE=$2; shift 2 ;;
    --system) SYSTEM=$2; shift 2 ;;
    --boot) BOOT=$2; shift 2 ;;
    *) die "unknown arg $1" ;;
  esac
done
[ -n "$BASE" ] && [ -n "$OUT" ] || die "need --base --out"
[ "$(id -u)" = 0 ] || die "run as root"
AAPT2=${AAPT2:-$(find_aapt2)}

WORK=$(mktemp -d "${TMPDIR:-/var/tmp}/riposte-chk.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
FAIL=0
ok()   { printf '  ok   %s\n' "$*"; }
bad()  { printf '  FAIL %s\n' "$*"; FAIL=$((FAIL + 1)); }
check() { if eval "$1"; then ok "$2"; else bad "$2"; fi; }

for part in system product; do
  for side in base out; do
    src=$([ $side = base ] && echo "$BASE" || echo "$OUT")/$part.img
    [ $side = base ] && [ $part = system ] && [ -n "$SYSTEM" ] && src=$SYSTEM
    [ -f "$src" ] || die "$src missing"
    unsparse "$src" "$WORK/$side-$part.raw"
    unpack_image "$WORK/$side-$part.raw" "$WORK/$side/$part" >/dev/null
  done
done
T=$WORK/out
S=$(system_root "$WORK/out/system")
SB=$(system_root "$WORK/base/system")

pkg_of() { "$AAPT2" dump packagename "$1" 2>/dev/null || true; }
# package -> present in tree?
has_pkg() { # tree pkg
  local apk
  while read -r apk; do
    [ "$(pkg_of "$apk")" = "$2" ] && return 0
  done < <(find "$1" -name '*.apk' -path '*app/*')
  return 1
}
pkgs_in() { sed 's/#.*//' "$1" | awk 'NF{print $1}'; }
# Every package in a tree matching a list entry (exact or `prefix.*`).
pkgs_matching() { # tree listfile
  local apk pkg line
  while read -r apk; do
    pkg=$(pkg_of "$apk"); [ -n "$pkg" ] || continue
    while read -r line; do
      case "$line" in
        *'*') [[ "$pkg" == "${line%\*}"* ]] && { echo "$pkg"; break; } ;;
        *) [ "$pkg" = "$line" ] && { echo "$pkg"; break; } ;;
      esac
    done < <(pkgs_in "$2")
  done < <(find "$1" -name '*.apk' -path '*app/*')
}

echo "launcher"
check "[ -f $S/$LAUNCHER_APK ]" "launcher APK at $LAUNCHER_APK"
check "[ \"\$(pkg_of $S/$LAUNCHER_APK)\" = $LAUNCHER_PKG ]" "launcher package is $LAUNCHER_PKG"
check "[ \"\$(stat -c %U:%G:%a $S/$LAUNCHER_APK)\" = root:root:644 ]" "launcher APK root:root 0644"
check "[ \"\$(getfattr --absolute-names -n security.selinux --only-values $S/$LAUNCHER_APK 2>/dev/null)\" = $SELINUX_SYSTEM_FILE ]" "launcher APK labelled system_file"

echo "privapp allowlist"
check "python3 -c 'import xml.etree.ElementTree as E; t=E.parse(\"$S/$PRIVAPP_XML\"); assert t.find(\"privapp-permissions\").get(\"package\")==\"$LAUNCHER_PKG\"'" "allowlist parses and names the launcher"
WANT=$("$AAPT2" dump permissions "$S/$LAUNCHER_APK" | sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p" | sort)
# shellcheck disable=SC2034  # used inside the eval below
HAVE=$(python3 -c 'import xml.etree.ElementTree as E,sys; print("\n".join(sorted(p.get("name") for p in E.parse(sys.argv[1]).iter("permission"))))' "$S/$PRIVAPP_XML")
check "[ \"\$WANT\" = \"\$HAVE\" ]" "allowlist == every permission the APK requests ($(wc -l <<<"$WANT") entries)"

echo "first boot"
check "[ -f $S/etc/init/riposte.rc ]" "init rc present"
check "[ \"\$(stat -c %a $S/bin/riposte-firstboot.sh)\" = 755 ]" "first-boot script executable"
check "grep -q '^ro.riposte.os.version=0\.' $S/build.prop" "ro.riposte.os.version in build.prop"
# The owner flag may only be 1 when eventcenter is not in the image.
if grep -q '^ro.riposte.os.car_owner=1' "$S/build.prop"; then
  check "! has_pkg $T com.szchoiceway.eventcenter" "car_owner=1 only without eventcenter"
else
  check "grep -q '^ro.riposte.os.car_owner=0' $S/build.prop" "ro.riposte.os.car_owner=0 on a stock-derived build"
fi

echo "bluetooth"
# The car-kit roles land only on profile gsi (overlay/props `@carkit` lines); a stock-derived
# build keeps the vendor's bluetooth.* lines byte for byte. init keeps the LAST value of a
# duplicated key, so the GSI's own phone-side lines above ours do not count.
last_prop() { sed -n "s/^$1=//p" "$2" | tail -1; }
if [ "$PROFILE" = gsi ]; then
  check "grep -q '^ro.riposte.os.bt_carkit=1$' $S/build.prop" "ro.riposte.os.bt_carkit=1"
  for p in $BT_CARKIT_ON; do
    check "[ \"\$(last_prop $p $S/build.prop)\" = true ]" "$p=true"
  done
  for p in $BT_CARKIT_OFF; do
    check "[ \"\$(last_prop $p $S/build.prop)\" = false ]" "$p=false"
  done
  check "[ \"\$(last_prop bluetooth.device.class_of_device $S/build.prop)\" = $BT_CARKIT_COD ]" "class of device $BT_CARKIT_COD (car audio)"
else
  check "grep -q '^ro.riposte.os.bt_carkit=0$' $S/build.prop" "ro.riposte.os.bt_carkit=0"
  check "! grep -q '^@carkit ' $S/build.prop" "no @carkit tag leaked into build.prop"
  check "cmp -s <(grep '^bluetooth\.' $SB/build.prop) <(grep '^bluetooth\.' $S/build.prop)" "bluetooth.* props identical to the base"
fi

echo "boot animation"
if [ -f "$T/product/media/bootanimation.zip" ]; then
  check "python3 -c 'import zipfile,sys; z=zipfile.ZipFile(sys.argv[1]); assert {i.compress_type for i in z.infolist()}=={0}; assert \"desc.txt\" in z.namelist()' $T/product/media/bootanimation.zip" "bootanimation.zip is stored (uncompressed) with desc.txt"
else
  ok "none shipped"
fi

echo "removed"
if [ "$PROFILE" = gsi ]; then
  LISTS="$HERE/overlay/remove.gsi"; KEEPFILE="$HERE/overlay/keep.gsi"
else
  LISTS="$HERE/overlay/remove.tier1"; KEEPFILE="$HERE/overlay/keep"
  [ "$PROFILE" = tier2 ] && LISTS+=" $HERE/overlay/remove.tier2"
fi
for list in $LISTS; do
  for p in $(pkgs_matching "$WORK/base" "$list"); do
    check "! has_pkg $T $p" "$p gone"
  done
done
[ "$PROFILE" = gsi ] && check "[ -z \"\$(pkgs_matching $T $HERE/overlay/remove.gsi)\" ]" "no OEM package left in the image"

echo "kept"
for p in $(pkgs_in "$KEEPFILE"); do
  if has_pkg "$WORK/base" "$p"; then
    check "has_pkg $T $p" "$p still present"
  else
    ok "$p (not in base)"
  fi
done
check "cmp -s $SB/$FRAMEWORK $S/$FRAMEWORK" "framework.jar byte-identical to base"

echo "suite"
N=$(find "$T/product/app" -name '*.apk' -exec "$AAPT2" dump packagename {} \; 2>/dev/null | grep -c '^com.ripostelabs\.' || true)
if [ -n "$SUITE" ]; then check "[ $N -eq $SUITE ]" "$N suite apps (expected $SUITE)"; else ok "$N suite apps"; fi

echo "xattrs survive the round trip"
sample=$(find "$SB/framework" -type f -name '*.jar' | head -1)
if [ -n "$sample" ] && getfattr -n security.selinux "$sample" >/dev/null 2>&1; then
  rel=${sample#"$SB"/}
  check "[ \"\$(getfattr --absolute-names -n security.selinux --only-values $sample | tr -d '\0')\" = \"\$(getfattr --absolute-names -n security.selinux --only-values $S/$rel | tr -d '\0')\" ]" "$rel keeps its label"
else
  ok "base carries no labels, nothing to preserve"
fi

echo "passthrough"
for part in vendor system_ext boot dtbo vbmeta vbmeta_system; do
  src="$BASE/$part.img"
  [ "$part" = boot ] && [ -n "$BOOT" ] && src=$BOOT
  [ -f "$src" ] || continue
  check "cmp -s $src $OUT/$part.img" "$part.img copied verbatim from $(basename "$src")"
done
check "(cd $OUT && sha256sum -c --quiet SHA256SUMS)" "SHA256SUMS verify"

echo
if [ $FAIL -eq 0 ]; then echo "PASS"; else echo "FAIL: $FAIL"; exit 1; fi
