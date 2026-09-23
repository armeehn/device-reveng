#!/usr/bin/env bash
# End-to-end test of build.sh + check.sh on a synthetic base, so the pipeline is
# proven before a real base image exists. Needs root on x, aapt2 and the SDK's
# android.jar (to mint tiny APKs carrying the OEM package names).
#
# Fixture:  system.img = ext4 with labels   product.img = erofs
# Apps:     the real launcher + suite from the launcher.hq webroot by default.
# Negative control: check.sh against the untouched base must FAIL.

set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=lib.sh
. "$HERE/lib.sh"
[ "$(id -u)" = 0 ] || die "run as root"

SDK=${ANDROID_SDK_ROOT:-$(ls -d /home/*/Android/Sdk 2>/dev/null | head -1)}
AAPT2=${AAPT2:-$(ls "$SDK"/build-tools/*/aapt2 | sort -V | tail -1)}; export AAPT2
ANDROID_JAR=$(ls "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)
WEBROOT=${WEBROOT:-/z1-pool/subvol-104-disk-1/var/www/launcher}
LAUNCHER_APK=${LAUNCHER_APK:-$(ls "$WEBROOT"/carlauncher-*-vc*.apk | sort -t c -k3 -V | tail -1)}

TOOLS_CACHE=${TOOLS_CACHE:-/z1-pool/share/carlauncher/os/tools/unit}
W=$(mktemp -d "${TMPDIR:-/var/tmp}/riposte-fx.XXXXXX")
trap 'rm -rf "$W"' EXIT
mkdir -p "$W/base" "$W/apps/suite" "$W/out" "$W/sys" "$W/prod"

# A valid, unsigned APK whose only content is a package name.
mint_apk() { # pkg out
  local d; d=$(mktemp -d "$W/mint.XXXX")
  printf '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="%s"><application/></manifest>' "$1" > "$d/AndroidManifest.xml"
  "$AAPT2" link --manifest "$d/AndroidManifest.xml" -I "$ANDROID_JAR" -o "$2"
}

# ---- system: ext4, every file labelled like a real build ----------------------
mkdir -p "$W/sys/framework" "$W/sys/priv-app/SysVarProvider" "$W/sys/etc/permissions" "$W/sys/bin"
head -c 1048576 /dev/urandom > "$W/sys/framework/framework.jar"
# A userdebug GSI ships its own tcpdump; the toolbelt must leave it in place (build.sh 3c).
printf '#!/system/bin/sh\n' > "$W/sys/bin/tcpdump"; chmod 0755 "$W/sys/bin/tcpdump"
mint_apk com.szchoiceway.providers.settings "$W/sys/priv-app/SysVarProvider/SysVarProvider.apk"
# The OEM projection daemon build.sh lifts on the gsi profile (step 3b): its loader, the two
# helpers and one library under the stock paths. Empty stand-ins; only the lift is checked.
mkdir -p "$W/sys/priv-app/zlink5/lib/arm"
: > "$W/sys/priv-app/zlink5/lib/arm/libzjL10001.so"
for b in z-link z-mdnsd z-usbmuxd; do printf '#!/system/bin/sh\n' > "$W/sys/bin/$b"; chmod 0755 "$W/sys/bin/$b"; done
# Phone-side Bluetooth roles as a stock or GSI build.prop carries them: tier2 must keep them
# byte for byte, gsi must override them (init keeps the last value of a duplicated key).
printf 'ro.build.version.release=13\nro.build.type=userdebug\nbluetooth.profile.a2dp.source.enabled=true\nbluetooth.profile.hfp.ag.enabled=true\n' > "$W/sys/build.prop"
find "$W/sys" -exec setfattr -n security.selinux -v "$SELINUX_SYSTEM_FILE" {} +
repack_image ext4 "$W/sys" "$W/base/system.img" system

# ---- product: erofs with kept + removable OEM apps -----------------------------
for p in com.szchoiceway.eventcenter:EventCenter com.szchoiceway.customerui:CustomerUI \
         com.szchoiceway.logcatupload:logcatupload com.mmbox.xbrowser:xbrowser \
         com.szchoiceway.musicplayer:musicplayer; do
  mkdir -p "$W/prod/app/${p#*:}"
  mint_apk "${p%%:*}" "$W/prod/app/${p#*:}/${p#*:}.apk"
done
repack_image erofs "$W/prod" "$W/base/product.img" product
head -c 65536 /dev/urandom > "$W/base/vbmeta.img"   # passthrough sample

# ---- apps ------------------------------------------------------------------------
cp "$LAUNCHER_APK" "$W/apps/carlauncher.apk"
cp "$WEBROOT"/suite/*.apk "$W/apps/suite/"
N=$(ls "$W/apps/suite" | wc -l)
# A stand-in animation: Pillow is not on x, and only the packaging is checked here.
python3 -c 'import zipfile,sys; z=zipfile.ZipFile(sys.argv[1],"w",zipfile.ZIP_STORED); z.writestr("desc.txt","1920 720 30\np 0 0 part0\n"); z.writestr("part0/0000.png","x")' "$W/apps/bootanimation.zip"

echo "== build (tier2) with $(basename "$LAUNCHER_APK") + $N suite apps"
"$HERE/build.sh" --base "$W/base" --apps "$W/apps" --out "$W/out" --profile tier2
cat "$W/out/MANIFEST"

echo "== check"
"$HERE/check.sh" --base "$W/base" --out "$W/out" --profile tier2 --suite "$N"

echo "== negative control: the untouched base must fail check.sh"
if "$HERE/check.sh" --base "$W/base" --out "$W/base" --profile tier2 >/dev/null 2>&1; then
  die "negative control PASSED: check.sh proves nothing"
fi
echo "negative control failed as expected"

echo "== gsi profile: a system-as-root stand-in GSI (.img.xz), the whole OEM stack out of product"
# A GSI nests /system under system/ and leaves absolute symlinks at the root (etc -> /system/etc),
# which a naive mkdir -p writes through. The stand-in has both.
mkdir -p "$W/sar/system"
cp -a "$W/sys/." "$W/sar/system/"
ln -s /system/etc "$W/sar/etc"
ln -s /system/bin "$W/sar/bin"
repack_image ext4 "$W/sar" "$W/sar.img" system
xz -c "$W/sar.img" > "$W/gsi.img.xz"
# The toolbelt rides along when the share cache is filled (tools/fetch.sh); the fixture never
# downloads 70 MB itself.
TOOLS_ARG="" TOOLS_CHECK=""
if [ -f "$TOOLS_CACHE/busybox" ]; then TOOLS_ARG="--tools $TOOLS_CACHE"; TOOLS_CHECK=--tools; fi
# shellcheck disable=SC2086
"$HERE/build.sh" --base "$W/base" --system "$W/gsi.img.xz" --apps "$W/apps" --out "$W/out-gsi" --profile gsi $TOOLS_ARG
grep -q '^car_owner=1$' "$W/out-gsi/MANIFEST" || die "gsi profile did not imply --car-owner"
grep -q '^bt_carkit=1$' "$W/out-gsi/MANIFEST" || die "gsi profile did not turn the car-kit roles on"
if [ -n "$TOOLS_ARG" ]; then
  OUTSYS=$(mktemp -d "$W/outsys.XXXXXX"); unsparse "$W/out-gsi/system.img" "$W/out-gsi-system.raw"
  mount -o ro,loop "$W/out-gsi-system.raw" "$OUTSYS"
  [ -f "$OUTSYS/system/bin/tcpdump" ] && [ ! -L "$OUTSYS/system/bin/tcpdump" ] || { umount "$OUTSYS"; die "toolbelt replaced the base's own tcpdump"; }
  umount "$OUTSYS"
fi
# shellcheck disable=SC2086
"$HERE/check.sh" --base "$W/base" --system "$W/gsi.img.xz" --out "$W/out-gsi" --profile gsi --suite "$N" $TOOLS_CHECK

echo "== negative control: --car-owner with eventcenter in the base must refuse"
if "$HERE/build.sh" --base "$W/base" --apps "$W/apps" --out "$W/out-owner" --car-owner >/dev/null 2>&1; then
  die "--car-owner built beside eventcenter: the guard is gone"
fi
echo "refused as expected"
echo "FIXTURE PASS"
