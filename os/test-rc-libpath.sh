#!/usr/bin/env bash
# No riposte init service may lean on `setenv LD_LIBRARY_PATH` while it carries a `seclabel`.
# init's switch into that domain is a secure exec (AT_SECURE), and the linker then drops
# LD_LIBRARY_PATH: riposte_ais died on every vc688 boot with 'library "libais.so" not found'
# (2026-09-24, bench) although the lib sat in the path it named. A /system/bin/sh wrapper that
# exports the path and execs the daemon keeps it (riposte-zlink.sh). Runs anywhere with awk.
set -euo pipefail
cd "$(dirname "$0")"

RC=overlay/system/etc/init/riposte.rc

# One line per offending service: a block runs from `service` to the next top-level line.
bad=$(awk '
  function flush() { if (name != "" && libpath && label && exe != "/system/bin/sh") print name }
  /^service / { flush(); name = $2; exe = $3; libpath = 0; label = 0; next }
  /^[^ \t#]/  { flush(); name = "" }
  name != "" && /^[ \t]+setenv[ \t]+LD_LIBRARY_PATH/ { libpath = 1 }
  name != "" && /^[ \t]+seclabel/                   { label = 1 }
  END { flush() }
' "$RC")

if [ -n "$bad" ]; then
  echo "FAIL: LD_LIBRARY_PATH beside a seclabel is dropped at exec; wrap in sh: $bad"
  exit 1
fi
echo "PASS"
