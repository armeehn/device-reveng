#!/usr/bin/env bash
# Test os/ota/mkpayload.py: build a payload from a small synthetic image set,
# then check it the way update_engine does: both RSA signatures against the
# public key, every blob's sha256, and the images rebuilt from the operations
# byte-exact. A second key must fail. Needs python3 with protobuf >= 7.35
# (forge: ~/ota-proto/venv/bin/python, or PY=...).
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
PY=${PY:-python3}
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT

readonly MIB=$((1024 * 1024))

# Synthetic images: random head, zero tail, sizes that are not chunk multiples.
mkdir -p "$T/img"
for p in system system_ext product vendor boot dtbo vbmeta vbmeta_system; do
  head -c $((3 * MIB)) /dev/urandom > "$T/img/$p.img"
  head -c $((2 * MIB + 8192)) /dev/zero >> "$T/img/$p.img"
done

openssl genrsa -out "$T/key.pem" 2048 2>/dev/null
openssl rsa -in "$T/key.pem" -pubout -out "$T/key.pub" 2>/dev/null
openssl genrsa -out "$T/other.pem" 2048 2>/dev/null
openssl rsa -in "$T/other.pem" -pubout -out "$T/other.pub" 2>/dev/null

"$PY" "$HERE/ota/mkpayload.py" "$T/img" "$T/key.pem" "$T/out" --jobs 4 >/dev/null

# Verifier: independent of mkpayload's code paths except the proto.
cat > "$T/verify.py" <<'EOF'
import hashlib, lzma, os, struct, subprocess, sys
sys.path.insert(0, sys.argv[1])
import update_metadata_pb2 as um
payload, pub, img = sys.argv[2:5]
d = open(payload, "rb").read()
magic, ver, msize, ssize = struct.unpack(">4sQQI", d[:24])
assert magic == b"CrAU" and ver == 2
m = um.DeltaArchiveManifest(); m.ParseFromString(d[24:24 + msize])
data = 24 + msize + ssize

def check(sig_blob, digest):
    s = um.Signatures(); s.ParseFromString(sig_blob)
    out = subprocess.run(["openssl", "pkeyutl", "-verifyrecover", "-pubin", "-inkey", pub],
                         input=s.signatures[0].data, capture_output=True).stdout
    return out[-32:] == digest

meta_ok = check(d[24 + msize:data], hashlib.sha256(d[:24 + msize]).digest())
body = d[:24 + msize] + d[data:data + m.signatures_offset]
pay_ok = check(d[data + m.signatures_offset:], hashlib.sha256(body).digest())
if not (meta_ok and pay_ok):
    print("SIGNATURE-FAIL"); sys.exit(2)

assert m.dynamic_partition_metadata.snapshot_enabled and m.minor_version == 0
for p in m.partitions:
    out = bytearray(p.new_partition_info.size)
    for op in p.operations:
        blob = d[data + op.data_offset:data + op.data_offset + op.data_length]
        assert hashlib.sha256(blob).digest() == op.data_sha256_hash, p.partition_name
        raw = lzma.decompress(blob) if op.type == um.InstallOperation.REPLACE_XZ else blob
        start = op.dst_extents[0].start_block * m.block_size
        out[start:start + len(raw)] = raw
    out = bytes(out[:p.new_partition_info.size])
    assert out == open(os.path.join(img, p.partition_name + ".img"), "rb").read(), p.partition_name
    assert hashlib.sha256(out).digest() == p.new_partition_info.hash
print("OK", len(m.partitions))
EOF

"$PY" "$T/verify.py" "$HERE/ota" "$T/out/payload.bin" "$T/key.pub" "$T/img" | grep -q "^OK 8$"
echo "PASS: payload signed, hashed, rebuilds 8 images byte-exact"

# A payload must not verify against a key that did not sign it.
WRONG=$("$PY" "$T/verify.py" "$HERE/ota" "$T/out/payload.bin" "$T/other.pub" "$T/img" || true)
if [ "$WRONG" = "SIGNATURE-FAIL" ]; then
  echo "PASS: wrong key refused"
else
  echo "FAIL: wrong key accepted"; exit 1
fi

grep -q "^METADATA_SIZE=" "$T/out/payload_properties.txt"
echo "PASS: payload_properties.txt"
