#!/bin/bash
# shots.py must call a repeat run clean, a changed screen dirty, and must never be
# fooled by the status-bar clock. Everything is generated here: the farm's example
# runs are not in the repo, and CI has no emulator.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# Fixture writer: a 400x120 RGB PNG whose pattern is a function of <seed>, with an
# optional magenta rectangle painted over it. <filters> picks the scanline filters,
# so the same pixels can be written two ways and the decoder has to agree.
cat > "$WORK/mkpng.py" <<'PY'
import struct
import sys
import zlib

path, seed, filters = sys.argv[1], int(sys.argv[2]), sys.argv[3]
rect = [int(v) for v in sys.argv[4:8]] if len(sys.argv) > 4 else None
WIDTH, HEIGHT, BPP = 400, 120, 3

rows = []
for y in range(HEIGHT):
    row = bytearray()
    for x in range(WIDTH):
        pixel = [(x * 3 + y * 5 + seed) % 256, (x + y * 2) % 256, (seed * 7 + x) % 256]
        if rect and rect[0] <= x < rect[0] + rect[2] and rect[1] <= y < rect[1] + rect[3]:
            pixel = [255, 0, 255]
        row += bytes(pixel)
    rows.append(row)


def paeth(a, b, c):
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    return b if pb <= pc else c


def encode(row, prev, kind):
    out = bytearray()
    for i, value in enumerate(row):
        left = row[i - BPP] if i >= BPP else 0
        up = prev[i]
        upper_left = prev[i - BPP] if i >= BPP else 0
        if kind == 1:
            value -= left
        elif kind == 2:
            value -= up
        elif kind == 3:
            value -= (left + up) >> 1
        elif kind == 4:
            value -= paeth(left, up, upper_left)
        out.append(value & 0xFF)
    return out


raw = bytearray()
prev = bytearray(WIDTH * BPP)
for y, row in enumerate(rows):
    kind = (y % 5) if filters == "mixed" else 0
    raw.append(kind)
    raw += encode(row, prev, kind)
    prev = row


def chunk(kind, body):
    payload = kind + body
    return struct.pack(">I", len(body)) + payload + struct.pack(">I", zlib.crc32(payload) & 0xFFFFFFFF)


header = struct.pack(">IIBBBBB", WIDTH, HEIGHT, 8, 2, 0, 0, 0)
with open(path, "wb") as handle:
    handle.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
                 + chunk(b"IDAT", zlib.compress(bytes(raw)))
                 + chunk(b"IEND", b""))
PY

shot() {  # shot <run-dir> <flow> <name> <seed> <filters> [x y w h]
  local run=$1 flow=$2 name=$3
  shift 3
  mkdir -p "$run/$flow/takeScreenshot"
  python3 "$WORK/mkpng.py" "$run/$flow/takeScreenshot/$name.png" "$@"
}

# Every case runs check and records its exit code, so a wrong verdict is an
# assertion failure rather than a dead script.
check() {
  local rc=0
  python3 "$HERE/shots.py" check "$@" > "$WORK/out" 2>&1 || rc=$?
  return $rc
}

fail() {
  echo "shots test: $1"
  cat "$WORK/out" 2>/dev/null || true
  exit 1
}

# A settled run: Home plus one top-bar screen.
make_run() {  # make_run <run-dir> <filters>
  shot "$1" "Home comes up" home 11 "$2"
  shot "$1" "Top bar opens" topbar-settings 47 "$2"
}

BASE="$WORK/baseline"
make_run "$WORK/run-a" none
python3 "$HERE/shots.py" accept "$WORK/run-a" "$BASE" > "$WORK/out" || fail "accept failed"
[ "$(ls "$BASE" | wc -l)" = "2" ] || fail "accept wrote $(ls "$BASE" | wc -l) baselines, expected 2"
[ -f "$BASE/home-comes-up__home.png" ] || fail "accept did not name the baseline after flow + shot"

# 1. The same build twice: clean.
make_run "$WORK/run-b" none
check "$WORK/run-b" "$BASE" || fail "an identical run must pass"
grep -qE 'home-comes-up__home +OK' "$WORK/out" || fail "identical run did not report OK"

# 2. Same pixels written with every scanline filter: the decoder has to undo them.
make_run "$WORK/run-filters" mixed
check "$WORK/run-filters" "$BASE" || fail "filtered PNGs must decode to the same pixels"

# 3. A changed screen: drift, and a non-zero exit.
make_run "$WORK/run-drift" none
shot "$WORK/run-drift" "Home comes up" home 11 none 40 80 120 30
check "$WORK/run-drift" "$BASE" && fail "a changed shot must fail"
grep -qE 'home-comes-up__home +DRIFT' "$WORK/out" || fail "changed shot was not reported as DRIFT"
grep -qE 'top-bar-opens__topbar-settings +OK' "$WORK/out" || fail "the untouched shot must stay OK"

# 4. --ignore drops that same shot, so the run passes again.
check "$WORK/run-drift" "$BASE" --ignore '__home$' || fail "--ignore did not drop the drifting shot"
grep -q 'home-comes-up__home' "$WORK/out" && fail "an ignored shot must not be reported"

# 5. The clock. A change confined to the masked rectangle is invisible by default,
#    and visible again with --no-mask — which is what stops a ticking clock from
#    turning every run red.
make_run "$WORK/run-clock" none
shot "$WORK/run-clock" "Home comes up" home 11 none 230 28 58 40
check "$WORK/run-clock" "$BASE" || fail "a change inside the clock mask must not fail"
check "$WORK/run-clock" "$BASE" --no-mask && fail "--no-mask must see the clock change"

# 6. A shot with no baseline is NEW, and NEW does not fail.
make_run "$WORK/run-new" none
shot "$WORK/run-new" "Brand new flow" fresh 91 none
check "$WORK/run-new" "$BASE" || fail "a shot with no baseline must not fail"
grep -qE 'brand-new-flow__fresh +NEW' "$WORK/out" || fail "unbaselined shot was not reported as NEW"

# 7. A baseline with no shot is MISSING, and MISSING does fail.
mkdir -p "$WORK/run-gap"
shot "$WORK/run-gap" "Home comes up" home 11 none
check "$WORK/run-gap" "$BASE" && fail "a missing shot must fail"
grep -qE 'top-bar-opens__topbar-settings +MISSING' "$WORK/out" || fail "absent shot was not reported as MISSING"

# 8. accept overwrites, so blessing the drifting run makes it the new truth.
python3 "$HERE/shots.py" accept "$WORK/run-drift" "$BASE" > "$WORK/out" || fail "re-accept failed"
check "$WORK/run-drift" "$BASE" || fail "a re-accepted run must pass"

echo "shots check: ok"
