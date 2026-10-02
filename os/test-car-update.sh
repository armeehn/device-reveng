#!/usr/bin/env bash
# setup.sh and rav4-usb-update run on zero, the laptop that updates the head unit over USB,
# where a syntax slip costs a trip to the car. They must parse, pass shellcheck, and setup.sh
# must fetch from car-update/ only files that live next to it, because the launcher.hq
# car-update/ publisher copies exactly that list from main.
set -euo pipefail
cd "$(dirname "$0")/car-update"

bash -n setup.sh
bash -n rav4-usb-update
bash -n road-noise-pull
bash -n road-noise-collect
python3 -m py_compile road-noise-file

# Warnings and errors only: the files are kept byte-identical to what zero already runs.
# SC2034: the wait loop counter is unused by design. SC2011: APK names are package ids,
# never spaces, so ls | xargs is safe.
if command -v shellcheck >/dev/null; then
    shellcheck -S warning setup.sh
    shellcheck -S warning -e SC2034,SC2011 rav4-usb-update
    shellcheck -S warning road-noise-pull road-noise-collect
else
    echo "shellcheck not installed; syntax only"
fi

# Example line in setup.sh: curl -sfo "$DIR/x.part" "$BASE/car-update/rav4-usb-update"
fetched=$(grep -oE '\$BASE/car-update/[A-Za-z0-9._-]+' setup.sh | sed 's#.*/##' | sort -u)
[ -n "$fetched" ] || { echo "FAIL: setup.sh fetches nothing from car-update/"; exit 1; }
for f in $fetched; do
    [ -f "$f" ] || { echo "FAIL: setup.sh fetches $f, which is not in os/car-update/"; exit 1; }
done
echo "CAR-UPDATE PASS"
