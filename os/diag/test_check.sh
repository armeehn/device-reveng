#!/bin/bash
# check.py must pass the healthy fixture and fail the bench-build-in-the-car one on exactly
# the invariants that install broke.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
python3 "$HERE/check.py" "$HERE/fixtures/diag-car-healthy.log" > /tmp/diag-healthy.out
grep -q "FAIL" /tmp/diag-healthy.out && { echo "healthy fixture failed:"; cat /tmp/diag-healthy.out; exit 1; }
if python3 "$HERE/check.py" "$HERE/fixtures/diag-car-bench-build.log" > /tmp/diag-bench.out; then
  echo "bench fixture passed, must fail"; exit 1
fi
for want in "car build (not bench)" "usb port is host" "canable enumerated" "launcher holds the mcu"; do
  grep -q "\[FAIL\] $want" /tmp/diag-bench.out || { echo "missing FAIL: $want"; cat /tmp/diag-bench.out; exit 1; }
done
echo "diag check: ok"
