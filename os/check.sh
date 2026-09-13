#!/usr/bin/env bash
# Static verification of a built Riposte OS output, without a car.
#
# Usage: check.sh --base DIR --out DIR [--profile tier1|tier2] [--suite N]
# Exit 0 when every assertion holds; each failure is printed, nothing is hidden.
# Runs as root on x (loop mounts).

set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=lib.sh
. "$HERE/lib.sh"

readonly LAUNCHER_PKG=com.ripostelabs.carlauncher
readonly LAUNCHER_APK=system/priv-app/CarLauncher/CarLauncher.apk
readonly PRIVAPP_XML=system/etc/permissions/privapp-permissions-ripostelabs.xml
readonly FRAMEWORK=system/framework/framework.jar

BASE="" OUT="" PROFILE=tier1 SUITE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --base) BASE=$2; shift 2 ;;
    --out) OUT=$2; shift 2 ;;
    --profile) PROFILE=$2; shift 2 ;;
    --suite) SUITE=$2; shift 2 ;;
    *) die "unknown arg $1" ;;
  esac
done
[ -n "$BASE" ] && [ -n "$OUT" ] || die "need --base --out"
[ "$(id -u)" = 0 ] || die "run as root"
AAPT2=${AAPT2:-$(ls /home/*/Android/Sdk/build-tools/*/aapt2 /opt/android-sdk/build-tools/*/aapt2 2>/dev/null | sort -V | tail -1)}

WORK=$(mktemp -d "${TMPDIR:-/var/tmp}/riposte-chk.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
FAIL=0
ok()   { printf '  ok   %s\n' "$*"; }
bad()  { printf '  FAIL %s\n' "$*"; FAIL=$((FAIL + 1)); }
check() { if eval "$1"; then ok "$2"; else bad "$2"; fi; }

for part in system product; do
  for side in base out; do
    src=$([ $side = base ] && echo "$BASE" || echo "$OUT")
    [ -f "$src/$part.img" ] || die "$src/$part.img missing"
    unsparse "$src/$part.img" "$WORK/$side-$part.raw"
    unpack_image "$WORK/$side-$part.raw" "$WORK/$side/$part" >/dev/null
  done
done
T=$WORK/out

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

echo "launcher"
check "[ -f $T/$LAUNCHER_APK ]" "launcher APK at $LAUNCHER_APK"
check "[ \"\$(pkg_of $T/$LAUNCHER_APK)\" = $LAUNCHER_PKG ]" "launcher package is $LAUNCHER_PKG"
check "[ \"\$(stat -c %U:%G:%a $T/$LAUNCHER_APK)\" = root:root:644 ]" "launcher APK root:root 0644"
check "[ \"\$(getfattr --absolute-names -n security.selinux --only-values $T/$LAUNCHER_APK 2>/dev/null)\" = $SELINUX_SYSTEM_FILE ]" "launcher APK labelled system_file"

echo "privapp allowlist"
check "python3 -c 'import xml.etree.ElementTree as E; t=E.parse(\"$T/$PRIVAPP_XML\"); assert t.find(\"privapp-permissions\").get(\"package\")==\"$LAUNCHER_PKG\"'" "allowlist parses and names the launcher"
WANT=$("$AAPT2" dump permissions "$T/$LAUNCHER_APK" | sed -n "s/^uses-permission: name='\([^']*\)'.*/\1/p" | sort)
# shellcheck disable=SC2034  # used inside the eval below
HAVE=$(python3 -c 'import xml.etree.ElementTree as E,sys; print("\n".join(sorted(p.get("name") for p in E.parse(sys.argv[1]).iter("permission"))))' "$T/$PRIVAPP_XML")
check "[ \"\$WANT\" = \"\$HAVE\" ]" "allowlist == every permission the APK requests ($(wc -l <<<"$WANT") entries)"

echo "first boot"
check "[ -f $T/system/etc/init/riposte.rc ]" "init rc present"
check "[ \"\$(stat -c %a $T/system/bin/riposte-firstboot.sh)\" = 755 ]" "first-boot script executable"
check "grep -q '^ro.riposte.os.version=0\.' $T/system/build.prop" "ro.riposte.os.version in build.prop"

echo "boot animation"
if [ -f "$T/product/media/bootanimation.zip" ]; then
  check "python3 -c 'import zipfile,sys; z=zipfile.ZipFile(sys.argv[1]); assert {i.compress_type for i in z.infolist()}=={0}; assert \"desc.txt\" in z.namelist()' $T/product/media/bootanimation.zip" "bootanimation.zip is stored (uncompressed) with desc.txt"
else
  ok "none shipped"
fi

echo "removed"
LISTS="$HERE/overlay/remove.tier1"; [ "$PROFILE" = tier2 ] && LISTS+=" $HERE/overlay/remove.tier2"
for p in $(cat $LISTS | sed 's/#.*//' | awk 'NF{print $1}'); do
  if has_pkg "$WORK/base" "$p"; then
    check "! has_pkg $T $p" "$p gone"
  else
    ok "$p (not in base, nothing to remove)"
  fi
done

echo "kept"
for p in $(pkgs_in "$HERE/overlay/keep"); do
  if has_pkg "$WORK/base" "$p"; then
    check "has_pkg $T $p" "$p still present"
  else
    ok "$p (not in base)"
  fi
done
check "cmp -s $WORK/base/$FRAMEWORK $T/$FRAMEWORK" "framework.jar byte-identical to base"

echo "suite"
N=$(find "$T/product/app" -name '*.apk' -exec "$AAPT2" dump packagename {} \; 2>/dev/null | grep -c '^com.ripostelabs\.' || true)
if [ -n "$SUITE" ]; then check "[ $N -eq $SUITE ]" "$N suite apps (expected $SUITE)"; else ok "$N suite apps"; fi

echo "xattrs survive the round trip"
sample=$(find "$WORK/base/system" -type f -name '*.jar' | head -1)
if [ -n "$sample" ] && getfattr -n security.selinux "$sample" >/dev/null 2>&1; then
  rel=${sample#"$WORK"/base/}
  check "[ \"\$(getfattr --absolute-names -n security.selinux --only-values $sample)\" = \"\$(getfattr --absolute-names -n security.selinux --only-values $T/$rel)\" ]" "$rel keeps its label"
else
  ok "base carries no labels, nothing to preserve"
fi

echo "passthrough"
for part in vendor boot dtbo vbmeta vbmeta_system; do
  [ -f "$BASE/$part.img" ] || continue
  check "cmp -s $BASE/$part.img $OUT/$part.img" "$part.img copied verbatim"
done
check "(cd $OUT && sha256sum -c --quiet SHA256SUMS)" "SHA256SUMS verify"

echo
if [ $FAIL -eq 0 ]; then echo "PASS"; else echo "FAIL: $FAIL"; exit 1; fi
