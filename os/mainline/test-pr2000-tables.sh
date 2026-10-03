#!/usr/bin/env bash
# pr2000_tables.h must be exactly what pr2000-tables.py generates from pr2000/*.tsv, and each
# table must carry a page select first and only byte-sized values. A hand edit to the header
# or a table that drifts from its source fails here. Runs anywhere with python3.
set -euo pipefail
cd "$(dirname "$0")"

T=$(mktemp -d)
trap "rm -rf $T" EXIT

python3 pr2000-tables.py > $T/gen.h
cmp -s $T/gen.h pr2000_tables.h || { echo "FAIL: pr2000_tables.h differs from pr2000-tables.py output"; diff $T/gen.h pr2000_tables.h | head; exit 1; }

python3 - pr2000/*.tsv <<'PY'
import sys
for path in sys.argv[1:]:
    rows = [l.split()[:2] for l in open(path) if l.strip() and not l.startswith("#")]
    if rows[0][0] != "0xff":
        sys.exit(f"FAIL: {path} does not start with a page select")
    bad = [r for r in rows if int(r[0], 16) > 0xff or int(r[1], 16) > 0xff]
    if bad:
        sys.exit(f"FAIL: {path} has a value past one byte: {bad[0]}")
PY
echo "PR2000-TABLES PASS"
