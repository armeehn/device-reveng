#!/bin/sh
# Run the accessory-board firmware under a real MicroPython, not only CPython.
#
# CPython proves the contract; it does not prove the file runs on the chip. This does the three
# things the chip would: compile main.py with mpy-cross, import board.py under the unix port, and
# run the contract tests under it with micropython-lib's unittest.
#
#   MICROPYTHON=/path/to/micropython MPY_CROSS=/path/to/mpy-cross sh check-mpy.sh
#
# Defaults are the build in LXC 111 (micropython v1.25.0, built with CFLAGS_EXTRA=-Wno-error
# because a newer gcc trips its -Werror). Exits 2 when no interpreter is present: absent is not
# the same as passing, and a caller must be able to tell.
set -eu
cd "$(dirname "$0")"

MICROPYTHON="${MICROPYTHON:-/home/user/micropython/ports/unix/build-standard/micropython}"
MPY_CROSS="${MPY_CROSS:-/home/user/micropython/mpy-cross/build/mpy-cross}"

if [ ! -x "$MICROPYTHON" ] || [ ! -x "$MPY_CROSS" ]; then
  echo "check-mpy: no MicroPython at $MICROPYTHON / $MPY_CROSS (set MICROPYTHON, MPY_CROSS)" >&2
  exit 2
fi

echo "check-mpy: $("$MICROPYTHON" -c 'import sys; print(sys.implementation.name, sys.version)')"

out=$(mktemp)
trap 'rm -f "$out"' EXIT
"$MPY_CROSS" -o "$out" main.py
echo "check-mpy: main.py compiles ($(wc -c < "$out") bytes of bytecode)"

"$MICROPYTHON" -c 'import board' && echo "check-mpy: board.py imports"

# unittest is not in the core interpreter; mip fetches it once into ~/.micropython/lib.
"$MICROPYTHON" -c 'import unittest' 2>/dev/null || "$MICROPYTHON" -m mip install unittest >/dev/null
"$MICROPYTHON" test_board.py
