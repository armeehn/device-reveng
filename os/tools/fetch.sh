#!/usr/bin/env bash
# Fill the toolbelt cache from tools.lock: download each entry, check its sha256, unpack a zip
# member when the URL names one. Idempotent: a cached file with the right hash is left alone.
#
#   tools.lock ──curl──▶ CACHE/<name>[.tar.gz|.apk|.xz] ──sha256──▶ build.sh --tools CACHE
#
# Usage: fetch.sh CACHE          (default $RIPOSTE_SHARE/os/tools/unit)
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
# Site values (hosts, addresses, share paths) come from $RIPOSTE_OS_ENV, never from this
# file: the repo is public. os/riposte-os.env.example lists them.
RIPOSTE_OS_ENV=${RIPOSTE_OS_ENV:-/etc/riposte-os.env}
# shellcheck source=/dev/null
[ -r "$RIPOSTE_OS_ENV" ] && . "$RIPOSTE_OS_ENV"
CACHE=${1:-${RIPOSTE_SHARE:?set RIPOSTE_SHARE}/os/tools/unit}
mkdir -p "$CACHE"

log() { printf '[tools] %s\n' "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

# The cached file name keeps the archive suffix so build.sh and install-data.sh know how to
# open it: nmap.tar.gz, com.termux.apk, frida-server.xz; plain binaries carry no suffix.
cached_name() { # name url
  case "$2" in
    *!*) echo "$1" ;;
    *.tar.gz) echo "$1.tar.gz" ;;
    *.apk) echo "$1.apk" ;;
    *.xz) echo "$1.xz" ;;
    *) echo "$1" ;;
  esac
}

sha_ok() { [ -f "$1" ] && [ "$(sha256sum "$1" | cut -d' ' -f1)" = "$2" ]; }

N=0
while read -r name kind sha url; do
  case "$name" in ''|'#'*) continue ;; esac
  dst="$CACHE/$(cached_name "$name" "$url")"
  if sha_ok "$dst" "$sha"; then
    N=$((N + 1)); continue
  fi

  # A `url!member` entry is one file inside a zip (busybox inside the Magisk APK).
  member=""
  case "$url" in *!*) member=${url#*!}; url=${url%%!*} ;; esac
  tmp=$(mktemp "$CACHE/.$name.XXXXXX")
  curl -sfL -o "$tmp" "$url" || die "download failed: $name $url"
  if [ -n "$member" ]; then
    python3 - "$tmp" "$member" <<'PY'
import sys, zipfile
path, member = sys.argv[1], sys.argv[2]
data = zipfile.ZipFile(path).read(member)
open(path, "wb").write(data)
PY
  fi
  sha_ok "$tmp" "$sha" || { rm -f "$tmp"; die "sha256 mismatch: $name (lock says $sha)"; }

  mv "$tmp" "$dst"
  chmod 0644 "$dst"
  log "fetched $name ($kind)"
  N=$((N + 1))
done < "$HERE/tools.lock"
log "$N tools in $CACHE"
