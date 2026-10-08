#!/usr/bin/env bash
# Test os/ota/publish-os.sh end to end on this host: a small synthetic image set with a build
# MANIFEST is published into a temp share, and the car ingest's own release code
# (os/uplink/ingest.py) must then offer it with the payload's real size and sha256. A second
# publish of the same version is a no-op; a half-built one is never offered. Needs python3 with
# protobuf >= 7.35 (forge), or PY=...
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
PY=${PY:-python3}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
fail() { echo "FAIL: $1"; exit 1; }

readonly MIB=$((1024 * 1024))
readonly VERSION="0.2+20261007.vc1060"

mkdir -p "$T/img" "$T/share"
for p in system system_ext product vendor boot dtbo vbmeta vbmeta_system; do
  head -c $((2 * MIB)) /dev/urandom > "$T/img/$p.img"
done
printf 'version=%s\nprofile=gsi\ncar_owner=1\nbench=0\n' "$VERSION" > "$T/img/MANIFEST"
openssl genrsa -out "$T/key.pem" 2048 2>/dev/null

# The ingest's view of the share: the rows it would put in manifest.json.
offered() {
  "$PY" - "$HERE/uplink" "$T/share" <<'EOF'
import json, sys
sys.path.insert(0, sys.argv[1])
import ingest
cfg = ingest.Config(None, None, None, None, None, 0, releases_root=sys.argv[2])
print(json.dumps(ingest.Releases(cfg)._os_rows()))
EOF
}

echo "== a half-built version is never offered"
mkdir -p "$T/share/os-ota/.$VERSION.partial"
[ "$(offered)" = "[]" ] || fail "offered a partial: $(offered)"

echo "== publish places payload, properties and MANIFEST under os-ota/<version>"
PY=$PY "$HERE/ota/publish-os.sh" "$T/img" "$T/key.pem" "$T/share" > "$T/out" 2>&1 || fail "publish: $(cat "$T/out")"
d=$T/share/os-ota/$VERSION
for f in payload.bin payload_properties.txt MANIFEST; do
  [ -s "$d/$f" ] || fail "missing $f"
done
[ ! -e "$T/share/os-ota/.$VERSION.partial" ] || fail "partial left behind"

echo "== the ingest offers it with the payload's size and sha256"
row=$(offered)
want_sha=$(sha256sum "$d/payload.bin" | cut -d' ' -f1)
want_size=$(stat -c %s "$d/payload.bin")
"$PY" - "$row" "$VERSION" "$want_sha" "$want_size" <<'EOF' || fail "row: $row"
import json, sys
rows, version, sha, size = json.loads(sys.argv[1]), sys.argv[2], sys.argv[3], int(sys.argv[4])
assert len(rows) == 1, rows
r = rows[0]
assert (r["version"], r["sha256"], r["size"]) == (version, sha, size), r
assert r["path"] == f"os/{version}/payload.bin" and r["car_owner"] and not r["bench"], r
EOF

echo "== publishing the same version again changes nothing"
before=$(stat -c %Y "$d/payload.bin")
PY=$PY "$HERE/ota/publish-os.sh" "$T/img" "$T/key.pem" "$T/share" > "$T/out" 2>&1 || fail "second publish: $(cat "$T/out")"
[ "$(stat -c %Y "$d/payload.bin")" = "$before" ] || fail "payload rebuilt"
grep -q "already published" "$T/out" || fail "no-op not said: $(cat "$T/out")"

echo "== a set without a usable version is refused"
sed -i 's/^version=.*/version=..\/escape/' "$T/img/MANIFEST"
PY=$PY "$HERE/ota/publish-os.sh" "$T/img" "$T/key.pem" "$T/share" > "$T/out" 2>&1 && fail "accepted a bad version"
[ ! -e "$T/share/escape" ] || fail "wrote outside os-ota"

echo "PASS"
