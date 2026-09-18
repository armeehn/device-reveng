#!/usr/bin/env bash
# Unit test for edl-extents.py: lpdump extents are 512-byte sectors, `edl ws` on UFS takes
# 4096-byte sectors, and the plan must convert. Runs anywhere with python3, no device.
set -euo pipefail
cd "$(dirname "$0")"

T=$(mktemp -d)
trap "rm -rf $T" EXIT

# super starts at byte 0x1000000 (16 MiB): device sector 4096 on UFS, 32768 in 512-byte units.
cat > $T/lpdump.txt <<"LP"
Partition table:
------------------------
  Name: system_b
  Group: qti_dynamic_partitions_b
  Attributes: readonly
  Extents:
    0 .. 2047 linear super 2048
    2048 .. 4095 linear super 8192
------------------------
  Name: vendor_b
  Group: qti_dynamic_partitions_b
  Attributes: readonly
  Extents:
    0 .. 4095 linear super 16384
------------------------
LP
mkdir $T/img
head -c $((2048 * 512 + 512)) /dev/zero > $T/img/system.img     # spills 512 bytes into extent 1
head -c $((4096 * 512)) /dev/zero > $T/img/vendor.img

python3 edl-extents.py --lpdump $T/lpdump.txt --super-offset $((0x1000000)) --sectorsize 4096 \
        --images $T/img --out $T/plan.sh 2>/dev/null

# Expected absolute UFS sectors: (0x1000000 + phys*512) / 4096
#   system_b extent 0: (16777216 + 1048576) / 4096 = 4352
#   system_b extent 1: (16777216 + 4194304) / 4096 = 5120
#   vendor_b extent 0: (16777216 + 8388608) / 4096 = 6144
got=$(grep -o "ws [0-9]*" $T/plan.sh | tr "\n" " ")
want="ws 4352 ws 5120 ws 6144 "
[ "$got" = "$want" ] || { echo "FAIL: got [$got] want [$want]"; exit 1; }

# The spill: extent 0 gets exactly 2048*512 bytes, extent 1 gets the remaining 512.
grep -q "count=1048576" $T/plan.sh && grep -q "count=512 " $T/plan.sh || { echo "FAIL: chunk sizes"; cat $T/plan.sh; exit 1; }

# A misaligned extent (not a whole 4096-byte sector) must be refused, never rounded.
sed -i "s/linear super 16384/linear super 16385/" $T/lpdump.txt
if python3 edl-extents.py --lpdump $T/lpdump.txt --super-offset $((0x1000000)) --sectorsize 4096 \
        --images $T/img --out $T/plan2.sh 2>/dev/null; then
    echo "FAIL: misaligned extent accepted"; exit 1
fi
echo "EDL-EXTENTS PASS"
