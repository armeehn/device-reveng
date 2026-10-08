#!/usr/bin/env bash
# Publish one OS build where the car's OsUpdater finds it.
#
#   publish-os.sh IMAGE_DIR KEY.pem SHARE_ROOT [--host HOST] [--firmware DIR] [--jobs N]
#
#   IMAGE_DIR (os/build.sh output: *.img + MANIFEST)
#      ──▶ mkpayload.py, here or on --host (forge: python3 with protobuf >= 7.35, 20 threads)
#      ──▶ SHARE_ROOT/os-ota/.<version>.partial/ ──rename──▶ SHARE_ROOT/os-ota/<version>/
#            payload.bin  payload_properties.txt  MANIFEST
#
# SHARE_ROOT is the car ingest's --releases-root (share/carlauncher); it serves the folder as
# /v1/releases/os/<version>/payload.bin and lists it only once the rename has happened. The
# version comes from the build's MANIFEST; a version already published is left alone.
# KEY.pem must match a certificate in the running image's otacerts.zip (share
# carlauncher/os-ota.md, "Signing"). With --host the key is copied to a temp folder there and
# removed after the build.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
PY=${PY:-python3}
readonly VERSION_RE='^[0-9][A-Za-z0-9.+_-]*$'

die() { echo "publish-os: $*" >&2; exit 1; }

[ $# -ge 3 ] || die "usage: publish-os.sh IMAGE_DIR KEY.pem SHARE_ROOT [--host HOST] [--firmware DIR] [--jobs N]"
img=$1 key=$2 share=$3
shift 3
host="" fw="" extra=()
while [ $# -gt 0 ]; do
  case "$1" in
    --host) host=$2; shift 2 ;;
    --firmware) fw=$2; shift 2 ;;
    --jobs) extra+=("$1" "$2"); shift 2 ;;
    *) die "unknown option $1" ;;
  esac
done

[ -f "$img/MANIFEST" ] || die "$img has no MANIFEST (not an os/build.sh output)"
[ -f "$key" ] || die "no key at $key"
[ -z "$fw" ] || { [ -f "$fw/abl.img" ] && [ -f "$fw/xbl.img" ]; } || die "--firmware $fw needs abl.img and xbl.img"
version=$(sed -n 's/^version=//p' "$img/MANIFEST" | head -1)
[[ $version =~ $VERSION_RE ]] || die "MANIFEST version '$version' is not a folder name"

final=$share/os-ota/$version
partial=$share/os-ota/.$version.partial
if [ -d "$final" ]; then
  echo "publish-os: $version already published at $final"
  exit 0
fi
rm -rf "$partial"
mkdir -p "$partial"

# The build: here, or on HOST with the images, the key and os/ota copied to a temp folder.
if [ -z "$host" ]; then
  [ -n "$fw" ] && extra+=(--firmware "$fw")
  "$PY" "$HERE/mkpayload.py" "$img" "$key" "$partial" "${extra[@]}"
else
  remote=$(ssh "$host" mktemp -d)
  trap 'ssh "$host" rm -rf "$remote"' EXIT
  ssh "$host" mkdir -p "$remote/img" "$remote/out"
  scp -q "$img"/*.img "$host:$remote/img/"
  scp -q "$key" "$host:$remote/key.pem"
  scp -q "$HERE/mkpayload.py" "$HERE/update_metadata_pb2.py" "$host:$remote/"
  # The unit's own bootloader images go along; a local path means nothing on HOST.
  if [ -n "$fw" ]; then
    ssh "$host" mkdir -p "$remote/fw"
    scp -q "$fw/abl.img" "$fw/xbl.img" "$host:$remote/fw/"
    extra+=(--firmware "$remote/fw")
  fi
  ssh "$host" "python3 $remote/mkpayload.py $remote/img $remote/key.pem $remote/out ${extra[*]}"
  scp -q "$host:$remote/out/payload.bin" "$host:$remote/out/payload_properties.txt" "$partial/"
fi

[ -s "$partial/payload.bin" ] && [ -s "$partial/payload_properties.txt" ] || die "mkpayload left no payload"
cp "$img/MANIFEST" "$partial/MANIFEST"
mv "$partial" "$final"
echo "publish-os: $version published, $(stat -c %s "$final/payload.bin") bytes at $final"
