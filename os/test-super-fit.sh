#!/usr/bin/env bash
# super_fits (lib.sh) must refuse an image set larger than the unit's super group. The crDroid
# 12.12 build of 2026-09-30 came out 1.9 MiB over 6 GiB and build.sh still said "done": the
# set could never flash. Sizes below are that build's and 0.2's. Runs anywhere with bash.
set -euo pipefail
cd "$(dirname "$0")"
# shellcheck source=lib.sh
. ./lib.sh

T=$(mktemp -d)
trap "rm -rf $T" EXIT

# set DIR system system_ext product vendor: sparse files of the given byte sizes
set_of() {
  local dir=$1 part
  shift
  mkdir -p "$dir"
  for part in system system_ext product vendor; do
    truncate -s "$1" "$dir/$part.img"
    shift
  done
}

set_of $T/over 3534389248 590196736 1797369856 522436608
set_of $T/ok 3477000192 590196736 1799434240 522420224

if super_fits $T/over >/dev/null 2>&1; then echo "FAIL: 6145.9 MiB set accepted"; exit 1; fi
super_fits $T/ok >/dev/null || { echo "FAIL: 0.2's 6093 MiB set refused"; exit 1; }
echo "SUPER-FIT PASS"
