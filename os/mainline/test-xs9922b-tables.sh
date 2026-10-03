#!/usr/bin/env bash
# xs9922b_tables.h must be exactly what xs9922b-tables.py generates from xs9922b/*.tsv; each
# row must be a 16-bit register, a byte value and a delay that fits the driver's u8, and the
# start table must end on the MIPI start (0x5007 = 1). Runs anywhere with python3.
set -euo pipefail
cd "$(dirname "$0")"

T=$(mktemp -d)
trap "rm -rf $T" EXIT

python3 xs9922b-tables.py > $T/gen.h
cmp -s $T/gen.h xs9922b_tables.h || { echo "FAIL: xs9922b_tables.h differs from xs9922b-tables.py output"; diff $T/gen.h xs9922b_tables.h | head; exit 1; }

python3 - xs9922b/*.tsv <<'PY'
import sys
for path in sys.argv[1:]:
    rows = [[int(x, 0) for x in l.split()[:3]] for l in open(path) if l.strip() and not l.startswith("#")]
    bad = [r for r in rows if r[0] > 0xffff or r[1] > 0xff or r[2] > 0xff]
    if bad:
        sys.exit(f"FAIL: {path} has a field out of range: {bad[0]}")
    if path.endswith("stream-all.tsv") and rows[-1][:2] != [0x5007, 1]:
        sys.exit(f"FAIL: {path} does not end on the MIPI start 0x5007=1")
PY
echo "XS9922B-TABLES PASS"
