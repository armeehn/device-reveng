#!/usr/bin/env bash
# riposte-gt9cfg.sh must turn the driver's "0x5F,0x80,..." text into the same bytes and write
# them back to the node. Runs anywhere with sh, tr, sed and xxd (toybox has all three).
set -euo pipefail
cd "$(dirname "$0")"

T=$(mktemp -d)
trap "rm -rf $T" EXIT

printf '0x5F,0x80,0x07,0xD0,0x02,0x05,0x3D,0x00,\n0x01,0x08,' > $T/node
sh overlay/system/bin/riposte-gt9cfg.sh $T/node
[ "$(xxd -p $T/node | tr -d '\n')" = 5f8007d002053d000108 ] || { echo "FAIL: bytes $(xxd -p $T/node)"; exit 1; }

# No node: exits 0 and writes nothing.
sh overlay/system/bin/riposte-gt9cfg.sh $T/missing
[ ! -e $T/missing ] || { echo "FAIL: wrote a missing node"; exit 1; }
echo "GT9CFG PASS"
