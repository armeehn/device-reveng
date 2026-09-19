#!/usr/bin/env bash
# riposte-gt9cfg.sh must turn the driver's "0x5F,0x80,..." text into the forced config: version
# 0x00, bytes 1..183 kept, checksum recomputed, Config_Fresh 1. A table whose stored checksum
# does not verify is left untouched. Runs anywhere with sh, tr, sed, cut and xxd (toybox has
# them all).
set -euo pipefail
cd "$(dirname "$0")"

T=$(mktemp -d)
trap "rm -rf $T" EXIT

# A 186-byte table: version 0x5F, X 1920, Y 720, switch 0x3D, zeros, then the checksum the
# chip expects (two's complement of the sum) and fresh 0, as the unit's driver prints it.
python3 - $T/node <<'EOF'
import sys
b = bytearray(186)
b[0:7] = bytes.fromhex("5f8007d002053d")
b[184] = (-sum(b[:184])) & 0xff
open(sys.argv[1], "w").write(",".join("0x%02X" % v for v in b) + ",\n")
EOF
sh overlay/system/bin/riposte-gt9cfg.sh $T/node
python3 - $T/node <<'EOF'
import sys
b = open(sys.argv[1], "rb").read()
assert len(b) == 186, len(b)
assert b[0] == 0x00, "version not forced"
assert b[1:7] == bytes.fromhex("8007d002053d"), "body changed"
assert b[184] == (-sum(b[:184])) & 0xff, "checksum not recomputed"
assert b[185] == 0x01, "fresh flag not set"
EOF

# A table whose checksum does not verify is not written.
printf '0x5F,0x80,0x07,0xD0,0x02,0x05,0x3D,' > $T/bad
python3 -c "open('$T/bad','a').write(','.join(['0x00']*179)+',')"
cp $T/bad $T/bad.orig
sh overlay/system/bin/riposte-gt9cfg.sh $T/bad
cmp -s $T/bad $T/bad.orig || { echo "FAIL: wrote a table with a bad checksum"; exit 1; }

# No node: exits 0 and writes nothing.
sh overlay/system/bin/riposte-gt9cfg.sh $T/missing
[ ! -e $T/missing ] || { echo "FAIL: wrote a missing node"; exit 1; }
echo "GT9CFG PASS"
