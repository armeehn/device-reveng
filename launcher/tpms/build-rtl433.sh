#!/usr/bin/env bash
# Builds launcher/app/src/main/assets/tpms/rtl_433: rtl_433 for the head unit (Android arm64,
# API 29) with libusb and librtlsdr linked in, so one file runs from a root shell with nothing
# else installed. Runs on forge (NDK 27). Sources are pinned by sha256.
#
#   libusb 1.0.27 ──▶ librtlsdr 2.0.2 ──▶ rtl_433 25.02 ──▶ assets/tpms/rtl_433 + rtl_433.sha256
#
# Usage: build-rtl433.sh [OUT_DIR]   (default: next to this script's assets dir)
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-$HERE/../app/src/main/assets/tpms}
NDK=${NDK:-/opt/android-sdk/ndk/27.2.12479018}
API=29
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
CC="$TC/bin/aarch64-linux-android$API-clang"
W=$(mktemp -d)
trap 'rm -rf "$W"' EXIT
PFX=$W/prefix
mkdir -p "$PFX/lib" "$PFX/include"
cd "$W"

# fetch URL SHA256: download, refuse on a hash mismatch, unpack here.
fetch() {
  curl -fsSL -o src.tar "$1"
  echo "$2  src.tar" | sha256sum -c --quiet
  tar -xf src.tar && rm src.tar
}
fetch https://github.com/libusb/libusb/releases/download/v1.0.27/libusb-1.0.27.tar.bz2 \
  ffaa41d741a8a3bee244ac8e54a72ea05bf2879663c098c82fc5757853441575
fetch https://github.com/osmocom/rtl-sdr/archive/refs/tags/v2.0.2.tar.gz \
  d69943eb32df742bc38a00ce6615e41250fd57851174e5ff916ec31e9e9e68e9
fetch https://github.com/merbanan/rtl_433/archive/refs/tags/25.02.tar.gz \
  5a409ea10e6d3d7d4aa5ea91d2d6cc92ebb2d730eb229c7b37ade65458223432

# libusb: no udev on Android; root enumerates /dev/bus/usb directly.
(cd libusb-1.0.27 && ./configure --host=aarch64-linux-android --prefix="$PFX" --disable-shared \
  --enable-static --disable-udev CC="$CC" AR="$TC/bin/llvm-ar" RANLIB="$TC/bin/llvm-ranlib" >/dev/null \
  && make -j"$(nproc)" >/dev/null && make install >/dev/null)

# librtlsdr by hand: six C files; its CMake wants pkg-config for libusb. The kernel's DVB driver,
# if any, is detached when the dongle opens.
mkdir -p b-rtlsdr
for f in librtlsdr tuner_e4k tuner_fc0012 tuner_fc0013 tuner_fc2580 tuner_r82xx; do
  "$CC" -O2 -fPIC -DDETACH_KERNEL_DRIVER=1 -Irtl-sdr-2.0.2/include -I"$PFX/include/libusb-1.0" \
    -c rtl-sdr-2.0.2/src/$f.c -o b-rtlsdr/$f.o
done
"$TC/bin/llvm-ar" rcs "$PFX/lib/librtlsdr.a" b-rtlsdr/*.o
cp rtl-sdr-2.0.2/include/*.h "$PFX/include/"

# Bionic has no pthread_cancel; only the rtl_tcp output server uses it, and the car never does.
sed -i 's/int r = pthread_cancel(srv->thread);/int r = 0; (void)srv;/' rtl_433-25.02/src/output_rtltcp.c

# Tyre sensors only: keep the decoders that report "type": "TPMS" (25 of 276), so each packet in
# the car no longer runs through 250 weather-station and doorbell decoders.
H=rtl_433-25.02/include/rtl_433_devices.h
KEEP=$(grep -lE '"type",.*"TPMS"' rtl_433-25.02/src/devices/*.c | xargs grep -hoE 'r_device const [A-Za-z0-9_]+' | awk '{print $3}')
awk -v keep="$KEEP" 'BEGIN { n = split(keep, a, /[ \n]+/); for (i = 1; i <= n; i++) k[a[i]] = 1 }
  /^ *DECL\(/ { m = $0; sub(/^ *DECL\(/, "", m); sub(/\).*/, "", m); if (!(m in k)) next }
  { print }' "$H" > "$H.tpms" && mv "$H.tpms" "$H"
echo "decoders kept: $(grep -c '^ *DECL(' "$H")"

cmake -S rtl_433-25.02 -B b-rtl433 -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-$API -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_C_FLAGS_RELEASE="-Os -DNDEBUG -ffunction-sections -fdata-sections" -DCMAKE_FIND_ROOT_PATH="$PFX" -DCMAKE_POLICY_VERSION_MINIMUM=3.5 \
  -DENABLE_RTLSDR=ON -DENABLE_SOAPYSDR=OFF -DENABLE_OPENSSL=OFF -DBUILD_TESTING=OFF \
  -DBUILD_DOCUMENTATION=OFF -DLIBRTLSDR_INCLUDE_DIRS="$PFX/include" \
  -DLIBRTLSDR_LIBRARIES="$PFX/lib/librtlsdr.a;$PFX/lib/libusb-1.0.a" \
  -DCMAKE_EXE_LINKER_FLAGS="-static-libstdc++ -Wl,--gc-sections $PFX/lib/libusb-1.0.a" >/dev/null
cmake --build b-rtl433 -j"$(nproc)" --target rtl_433 >/dev/null
"$TC/bin/llvm-strip" b-rtl433/src/rtl_433

mkdir -p "$OUT"
cp b-rtl433/src/rtl_433 "$OUT/rtl_433"
(cd "$OUT" && sha256sum rtl_433 > rtl_433.sha256 && cat rtl_433.sha256)
