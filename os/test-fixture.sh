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
mint_apk com.szchoiceway.providers.settings "$W/sys/priv-app/SysVarProvider/SysVarProvider.apk"
printf 'ro.build.version.release=13\nro.build.type=userdebug\n' > "$W/sys/build.prop"
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

echo "== negative control: --car-owner with eventcenter in the base must refuse"
if "$HERE/build.sh" --base "$W/base" --apps "$W/apps" --out "$W/out-owner" --car-owner >/dev/null 2>&1; then
  die "--car-owner built beside eventcenter: the guard is gone"
fi
echo "refused as expected"
echo "FIXTURE PASS"
