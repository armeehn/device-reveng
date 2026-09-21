#!/bin/bash
# Build the signed overlay APK into ../../overlay/product/overlay/ (committed: the image build
# on x has no JDK). Runs in LXC 111: aapt2 -> apksigner, a throwaway key is fine for a static
# system overlay.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
BT=/opt/android-sdk/build-tools/34.0.0
export PATH=/opt/jdk17/bin:$PATH   # keytool and the JVM behind apksigner
PLATFORM=/opt/android-sdk/platforms/android-34/android.jar
OUT=$HERE/../../overlay/product/overlay
KS=${RRO_KEYSTORE:-$HOME/.rav4/rro.keystore}
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
[ -f "$KS" ] || { mkdir -p "$(dirname "$KS")"; keytool -genkeypair -keystore "$KS" -alias rro -storepass android \
  -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=riposte-rro"; }
"$BT/aapt2" compile --dir "$HERE/res" -o "$WORK/res.zip"
"$BT/aapt2" link -I "$PLATFORM" --manifest "$HERE/AndroidManifest.xml" -o "$WORK/unsigned.apk" "$WORK/res.zip"
"$BT/zipalign" -f 4 "$WORK/unsigned.apk" "$WORK/aligned.apk"
mkdir -p "$OUT"
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT/RiposteBluetoothCarkit.apk" "$WORK/aligned.apk"
echo ">> built: $OUT/RiposteBluetoothCarkit.apk"
