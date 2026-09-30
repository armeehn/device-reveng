#!/usr/bin/env bash
# flash-0.2.sh runs on zero at the car, where a syntax slip costs a trip. It must parse,
# pass shellcheck, and fetch only helpers that live next to it in os/, because the
# launcher.hq car-update/os/ publisher copies exactly that list from main.
set -euo pipefail
cd "$(dirname "$0")"

bash -n flash-0.2.sh

# SC2034: the wait loop counter is unused by design. SC2097/SC2098: OS_DIR is passed to
# the child on purpose and the parent already holds the same value.
if command -v shellcheck >/dev/null; then
    shellcheck -e SC2034,SC2097,SC2098 flash-0.2.sh
else
    echo "shellcheck not installed; syntax only"
fi

helpers=$(sed -n 's/^for f in \(.*\); do$/\1/p' flash-0.2.sh | head -1)
[ -n "$helpers" ] || { echo "FAIL: no helper list in flash-0.2.sh"; exit 1; }
for f in $helpers; do
    [ -f "$f" ] || { echo "FAIL: flash-0.2.sh fetches $f, which is not in os/"; exit 1; }
done
echo "FLASH-02 PASS"
