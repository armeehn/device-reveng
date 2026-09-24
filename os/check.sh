#!/usr/bin/env bash
# Static verification of a built Riposte OS output, without a car.
#
# Usage: check.sh --base DIR --out DIR [--profile tier1|tier2|gsi] [--system IMG] [--suite N] [--tools]
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
readonly DEFPERM_XML=etc/default-permissions/default-permissions-ripostelabs.xml
readonly FRAMEWORK=framework/framework.jar
# Car-kit Bluetooth roles (overlay/props): on, off, and the class of device on a gsi output.
readonly BT_CARKIT_ON="bluetooth.profile.a2dp.sink.enabled bluetooth.profile.hfp.hf.enabled
  bluetooth.profile.avrcp.controller.enabled bluetooth.profile.pbap.client.enabled
  bluetooth.profile.map.client.enabled"
readonly BT_CARKIT_OFF="bluetooth.profile.a2dp.source.enabled bluetooth.profile.hfp.ag.enabled
  bluetooth.profile.avrcp.target.enabled"
readonly BT_CARKIT_COD=38,4,8
# The logcat ring (overlay/system/bin/riposte-logring.sh): 8 MiB x (1 live + 12 rotated) files.
readonly LOGRING_ROTATE_KB=8192
readonly LOGRING_ROTATE_COUNT=12
# Secure settings the first-boot hook must turn off: the GSI's doze dream and screen savers.
readonly DOZE_OFF_KEYS="doze_enabled doze_always_on doze_pulse_on_pick_up doze_pulse_on_double_tap
  screensaver_enabled screensaver_activate_on_dock screensaver_activate_on_sleep"

BASE="" OUT="" PROFILE=tier1 SUITE="" SYSTEM="" BOOT="" TOOLS=0
while [ $# -gt 0 ]; do
  case "$1" in
    --base) BASE=$2; shift 2 ;;
    --out) OUT=$2; shift 2 ;;
    --profile) PROFILE=$2; shift 2 ;;
    --suite) SUITE=$2; shift 2 ;;
    --tools) TOOLS=1; shift ;;
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

  # Our rw-system.sh replaces TrebleDroid's; init execs it by label, so the label is the test.
  check "cmp -s $HERE/overlay/system/bin/rw-system.sh $S/bin/rw-system.sh" "rw-system.sh is ours"
  check "[ \"\$(getfattr --absolute-names -n security.selinux --only-values $S/bin/rw-system.sh 2>/dev/null | tr -d '\\0')\" = $SELINUX_PHHSU_EXEC ]" "rw-system.sh labelled phhsu_exec"
  check "[ -x $S/riposte/zlink/bin/z-link ] && [ -f $S/riposte/zlink/lib/libzjL10001.so ]" "OEM projection daemon lifted under riposte/zlink"
  check "grep -q '^service riposte_zlink ' $S/etc/init/riposte.rc" "riposte_zlink init service"
  check "[ -x $S/bin/z-mdnsd ]" "z-mdnsd at the daemon's fixed path"
  # The AIS camera server the reverse picture rides on (build.sh step 3d, riposte.rc).
  check "[ -x $S/riposte/ais/bin/ais_server ] && [ -f $S/riposte/ais/lib/libais_pr2000.so ]" "AIS camera server lifted under riposte/ais"
  check "[ -f $S/riposte/ais/lib/libmmosal.so ]" "libmmosal.so beside it (vendor lib, off a /system daemon's search path)"
  check "grep -q '^service riposte_ais ' $S/etc/init/riposte.rc" "riposte_ais init service"
  check "[ \"\$(stat -c %a $S/bin/riposte-ais.sh)\" = 755 ]" "riposte-ais.sh executable (sets the lib path the secure exec drops)"
  # The launcher's dlopen of the AIS client (AisCameraNative): on the public list, deps beside it.
  # Exactly the 64-bit entry: a bare name is preloaded by both zygotes and the 32-bit one has no
  # such file, which is the boot loop of 0.2 vc688. Then every path the entry commits to exists.
  check "grep -qx '$AIS_PUBLIC_ENTRY' $S/etc/public.libraries.txt" "'$AIS_PUBLIC_ENTRY' on the public list"
  check "! grep -qx '${AIS_PUBLIC_ENTRY%% *}' $S/etc/public.libraries.txt" "no bare (both-zygote) entry for it"
  # shellcheck disable=SC2086
  for p in $(public_lib_paths $AIS_PUBLIC_ENTRY); do
    check "[ -f $S/$p ]" "'$AIS_PUBLIC_ENTRY' has its $p"
  done
  check "[ -f $S/lib64/libais_camera.so ] && [ -f $S/lib64/libais_fibo_carcam.so ] && [ -f $S/lib64/libmmosal.so ]" "AIS client libs in /system/lib64"
  check "grep -q '^service riposte_hotspot ' $S/etc/init/riposte.rc" "riposte_hotspot init service"
  # Root for the launcher is by construction: seeded every boot, checked every boot.
  check "grep -q '^service riposte_root ' $S/etc/init/riposte.rc" "riposte_root init service (launcher root grant, every boot)"
  check "[ \"\$(stat -c %a $S/bin/riposte-root.sh)\" = 755 ]" "riposte-root.sh executable"
  check "! grep -q 'uid_policy' $S/bin/riposte-firstboot.sh" "the grant has one owner: not in the first-boot hook"
  # The GSI dozes and dreams by default; stock did neither (car, 2026-09-23: black panel after boot).
  for key in $DOZE_OFF_KEYS; do
    check "grep -q '^settings put secure $key 0' $S/bin/riposte-firstboot.sh" "first boot turns $key off"
  done
  # The logcat ring on flash: started as soon as /data is there, restarted by init, sized as
  # riposte-logring.sh documents (8 MiB x 13 files), marked once per boot.
  check "grep -q '^service riposte_logring ' $S/etc/init/riposte.rc" "riposte_logring init service"
  check "grep -q '^on post-fs-data && property:ro.riposte.os.car_owner=1' $S/etc/init/riposte.rc" "riposte_logring starts at post-fs-data"
  check "! sed -n '/^service riposte_logring /,/^$/p' $S/etc/init/riposte.rc | grep -q oneshot" "riposte_logring is not oneshot (init restarts it)"
  check "[ \"\$(stat -c %a $S/bin/riposte-logring.sh)\" = 755 ]" "riposte-logring.sh executable"
  check "grep -q '^ROTATE_KB=$LOGRING_ROTATE_KB$' $S/bin/riposte-logring.sh && grep -q '^ROTATE_COUNT=$LOGRING_ROTATE_COUNT$' $S/bin/riposte-logring.sh" "ring is $LOGRING_ROTATE_KB KiB x (1 + $LOGRING_ROTATE_COUNT) files"
  check "grep -q '^exec logcat -b all -v threadtime -f \"\$FILE\" -r \"\$ROTATE_KB\" -n \"\$ROTATE_COUNT\"$' $S/bin/riposte-logring.sh" "logcat -b all -v threadtime, rotating"
  check "grep -q '^service riposte_logring_mark ' $S/etc/init/riposte.rc && grep -q '^    start riposte_logring_mark$' $S/etc/init/riposte.rc" "riposte_logring_mark init service, started"
  check "[ \"\$(stat -c %a $S/bin/riposte-logring-mark.sh)\" = 755 ]" "riposte-logring-mark.sh executable"
  # One property drives the reverse-camera decoder.
  check "grep -q '^on property:persist.riposte.camera.mode=\*' $S/etc/init/riposte.rc" "persist.riposte.camera.mode init trigger"
  check "[ \"\$(stat -c %a $S/bin/riposte-camera-mode.sh)\" = 755 ]" "riposte-camera-mode.sh executable"
  # One property drives the USB controller's role.
  check "grep -q '^on property:persist.riposte.usb.role=\*' $S/etc/init/riposte.rc" "persist.riposte.usb.role init trigger"
  check "[ \"\$(stat -c %a $S/bin/riposte-usb-role.sh)\" = 755 ]" "riposte-usb-role.sh executable"
else
  check "[ ! -f $S/bin/rw-system.sh ]" "no rw-system.sh on a stock base"
  check "grep -q '^ro.riposte.os.bt_carkit=0$' $S/build.prop" "ro.riposte.os.bt_carkit=0"
  check "! grep -q '^@carkit ' $S/build.prop" "no @carkit tag leaked into build.prop"
  check "cmp -s <(grep '^bluetooth\.' $SB/build.prop) <(grep '^bluetooth\.' $S/build.prop)" "bluetooth.* props identical to the base"
fi

# A GSI links /product to its own /system/product, so build.sh puts product files there.
PR=$T/product
[ "$PROFILE" = gsi ] && PR=$S/product
echo "boot animation"
if [ -f "$PR/media/bootanimation.zip" ]; then
  check "python3 -c 'import zipfile,sys; z=zipfile.ZipFile(sys.argv[1]); assert {i.compress_type for i in z.infolist()}=={0}; assert \"desc.txt\" in z.namelist()' $PR/media/bootanimation.zip" "bootanimation.zip is stored (uncompressed) with desc.txt"
else
  ok "none shipped"
fi

echo "suite default grants"
check "python3 -c 'import xml.etree.ElementTree as E; E.parse(\"$PR/$DEFPERM_XML\")'" "default-permissions XML parses"
# shellcheck disable=SC2034  # used inside the eval below
SUITE_PKGS=$( { for a in "$PR"/app/*/*.apk; do pkg_of "$a"; done | grep "^com\.ripostelabs\."; echo "$LAUNCHER_PKG"; } | sort)
# shellcheck disable=SC2034
GRANT_PKGS=$(python3 -c 'import xml.etree.ElementTree as E,sys; print("\n".join(sorted(e.get("package") for e in E.parse(sys.argv[1]).iter("exception"))))' "$PR/$DEFPERM_XML")
check "[ \"\$SUITE_PKGS\" = \"\$GRANT_PKGS\" ]" "the launcher and every suite package have a default-permissions entry"

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
# A real GSI ships com.android.resolv.capex; the test fixture ships no APEX at all.
[ "$PROFILE" = gsi ] && check "[ -z \"\$(ls $S/apex/*.capex 2>/dev/null)\" ] && { [ ! -d $SB/apex ] || [ -f $S/apex/com.android.resolv.apex ]; }" "no compressed APEX left (resolv unpacked)"

echo "kept"
for p in $(pkgs_in "$KEEPFILE"); do
  if has_pkg "$WORK/base" "$p"; then
    check "has_pkg $T $p" "$p still present"
  else
    ok "$p (not in base)"
  fi
done
# On the GSI profile the framework is AOSP's, not the vendor's: the comparison is meaningless.
[ "$PROFILE" = gsi ] || check "cmp -s $SB/$FRAMEWORK $S/$FRAMEWORK" "framework.jar byte-identical to base"

echo "suite"
N=$(find "$PR/app" -name '*.apk' -exec "$AAPT2" dump packagename {} \; 2>/dev/null | grep -c '^com.ripostelabs\.' || true)
if [ -n "$SUITE" ]; then check "[ $N -eq $SUITE ]" "$N suite apps (expected $SUITE)"; else ok "$N suite apps"; fi

# The debug toolbelt (build.sh step 3c): every non-data entry of tools.lock in the image, on
# PATH through /system/bin, Termux with its native libraries pre-extracted.
if [ "$TOOLS" = 1 ]; then
  echo "debug toolbelt"
  while read -r name kind sha url; do
    case "$name" in ''|'#'*) continue ;; esac
    case "$kind" in
      # Links carry absolute /system targets, so -x on them resolves against the host: test -L.
      bin) check "[ -x $S/riposte/bin/$name ] && { [ -L $S/bin/$name ] || [ -x $S/bin/$name ]; }" "$name under riposte/bin and on PATH via bin" ;;
      tar) check "[ -x $S/riposte/nmap/nmap ] && [ -d $S/riposte/nmap/data ] && [ -x $S/bin/nmap ] && [ -L $S/bin/ncat ]" "nmap tree + wrapper, ncat linked" ;;
      apk) check "[ -f $PR/app/$name/$name.apk ] && ls $PR/app/$name/lib/arm64/*.so >/dev/null 2>&1" "$name installed with lib/arm64 extracted" ;;
    esac
  done < "$HERE/tools/tools.lock"
  check "[ $(ls $S/riposte/bin/bb | wc -l) -eq $(grep -cv '^#' $HERE/tools/busybox.applets) ]" "one applet link per line of busybox.applets"
  check "[ \"\$(readlink $S/riposte/bin/bb/nslookup)\" = /system/riposte/bin/busybox ]" "applet links point at riposte/bin/busybox"
fi

echo "filesystem features the unit's kernel accepts"
# The vendor's images carry exactly this set; anything beyond it (metadata_csum, 64bit …) is a
# 900E crash at first-stage mount on the 4.14 kernel (2026-09-15). Repacked ext4 only.
readonly VENDOR_EXT4="dir_index dir_nlink ext_attr extent extra_isize filetype huge_file large_file sparse_super uninit_bg"
for part in system product; do
  [ -f "$WORK/out-$part.raw" ] || continue
  [ "$(image_kind "$WORK/out-$part.raw")" = ext4 ] || continue
  feats=$(dumpe2fs -h "$WORK/out-$part.raw" 2>/dev/null | sed -n 's/^Filesystem features: *//p' | tr ' ' '\n' | grep -v shared_blocks | sort | tr '\n' ' ' | sed 's/ $//')
  check "[ \"$feats\" = \"$VENDOR_EXT4\" ]" "$part ext4 features == vendor set ($feats)"
done

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
