#!/usr/bin/env bash
# Build riposte-touchswap for the unit: static arm64, so it runs on the GSI without bionic
# or vendor libraries. Needs aarch64-linux-gnu-gcc (Arch: aarch64-linux-gnu-gcc; Debian:
# gcc-aarch64-linux-gnu). The result is committed at overlay/system/bin/, which build.sh
# copies into the system image; run this after editing the source and commit both.
set -euo pipefail
cd "$(dirname "$0")"

OUT=../overlay/system/bin/riposte-touchswap
aarch64-linux-gnu-gcc -O2 -static -Wall -Wextra -Werror -o "$OUT" riposte-touchswap.c
chmod 0755 "$OUT"
echo "built $OUT ($(stat -c %s "$OUT") bytes)"
