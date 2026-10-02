#!/usr/bin/env python3
"""Build a signed, full A/B OTA payload (payload.bin) from a Riposte OS image set.

The GT6-EAU runs Virtual A/B (`ro.virtual_ab.enabled=true`, super group
`qti_dynamic_partitions`). update_engine on the unit applies a full payload into
the inactive slot as dm-snapshots, so no `_a` space is needed in super. This
script writes the same shape the vendor's own update13.zip uses (block 4096,
minor version 0, REPLACE / REPLACE_XZ operations, snapshot_enabled) without
needing AOSP's delta_generator, which ships only inside otatools.

    image set ──▶ 2 MiB chunks ──▶ xz (or raw) blobs ──▶ manifest ──▶ sign ──▶ payload.bin
                                                                          └──▶ payload_properties.txt

Usage:
  mkpayload.py IMAGE_DIR KEY.pem OUT_DIR [--firmware DIR] [--timestamp UTC]

IMAGE_DIR holds the logical images (system system_ext product vendor) and the
physical ones (boot dtbo vbmeta vbmeta_system). --firmware adds abl.img and
xbl.img from the unit's own slot dump; leave it out to keep the target slot's
bootloader as it is. KEY.pem is an RSA-2048 private key whose certificate is
in the running image's /system/etc/security/otacerts.zip.
"""

import argparse
import base64
import hashlib
import lzma
import os
import struct
import subprocess
import sys
import tempfile
import time
from multiprocessing import Pool

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import update_metadata_pb2 as um  # noqa: E402  (generated, AOSP update_engine)

MAGIC = b"CrAU"
MAJOR_VERSION = 2
FULL_MINOR_VERSION = 0
BLOCK_SIZE = 4096
CHUNK_BYTES = 2 * 1024 * 1024
RSA_SIG_BYTES = 256

# Unit geometry: lpdump of the live super, 2026-09-18 (mksuper.sh carries the same).
GROUP_NAME = "qti_dynamic_partitions"
GROUP_BYTES = 6438256640
LOGICAL = ("system", "system_ext", "product", "vendor")
PHYSICAL = ("boot", "dtbo", "vbmeta", "vbmeta_system")
FIRMWARE = ("abl", "xbl")

# xz-embedded in update_engine decodes LZMA2 with a CRC32 check; a dict no
# larger than the chunk keeps its allocation small.
XZ_FILTERS = [{"id": lzma.FILTER_LZMA2, "preset": 9, "dict_size": CHUNK_BYTES}]


def _compress(chunk):
    """Return (op type, blob) for one chunk: xz when it is smaller, raw otherwise."""
    xz = lzma.compress(chunk, format=lzma.FORMAT_XZ, check=lzma.CHECK_CRC32,
                       filters=XZ_FILTERS)
    if len(xz) < len(chunk):
        return um.InstallOperation.REPLACE_XZ, xz

    return um.InstallOperation.REPLACE, chunk


def _chunk_job(args):
    """Worker: read one chunk of an image and compress it."""
    path, offset = args
    with open(path, "rb") as f:
        f.seek(offset)
        chunk = f.read(CHUNK_BYTES)

    # Pad the tail to a whole block: ops write whole blocks.
    tail = len(chunk) % BLOCK_SIZE
    if tail:
        chunk += b"\0" * (BLOCK_SIZE - tail)

    kind, blob = _compress(chunk)
    return offset, len(chunk), kind, blob


def _sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)

    return h.digest()


def _add_partition(manifest, name, path, data, pool):
    """Append one partition's operations to the manifest and its blobs to data."""
    size = os.path.getsize(path)
    if size % BLOCK_SIZE:
        raise SystemExit(f"{name}: size {size} is not a multiple of {BLOCK_SIZE}")

    part = manifest.partitions.add()
    part.partition_name = name
    part.new_partition_info.size = size
    part.new_partition_info.hash = _sha256_file(path)

    jobs = [(path, off) for off in range(0, size, CHUNK_BYTES)]
    for offset, length, kind, blob in pool.imap(_chunk_job, jobs):
        op = part.operations.add()
        op.type = kind
        op.data_offset = data.tell()
        op.data_length = len(blob)
        op.data_sha256_hash = hashlib.sha256(blob).digest()

        ext = op.dst_extents.add()
        ext.start_block = offset // BLOCK_SIZE
        ext.num_blocks = length // BLOCK_SIZE
        data.write(blob)

    print(f"{name}: {size} bytes, {len(part.operations)} ops", flush=True)


def _sign(key, digest):
    """RSA PKCS#1 v1.5 over a SHA-256 digest, as update_engine verifies it."""
    out = subprocess.run(
        ["openssl", "pkeyutl", "-sign", "-inkey", key, "-pkeyopt", "digest:sha256"],
        input=digest, capture_output=True, check=True).stdout
    if len(out) != RSA_SIG_BYTES:
        raise SystemExit(f"signature is {len(out)} bytes; need an RSA-2048 key")

    return out


def _sig_blob(sig):
    sigs = um.Signatures()
    s = sigs.signatures.add()
    s.data = sig
    s.unpadded_signature_size = len(sig)
    return sigs.SerializeToString()


def _image_list(args):
    names = list(LOGICAL) + list(PHYSICAL)
    paths = {n: os.path.join(args.image_dir, n + ".img") for n in names}
    if not args.firmware:
        return paths

    for n in FIRMWARE:
        paths[n] = os.path.join(args.firmware, n + ".img")

    return paths


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("image_dir")
    ap.add_argument("key")
    ap.add_argument("out_dir")
    ap.add_argument("--firmware", help="dir with the unit's own abl.img and xbl.img")
    ap.add_argument("--timestamp", type=int, default=int(time.time()),
                    help="max_timestamp; must not be older than the unit's ro.build.date.utc")
    ap.add_argument("--jobs", type=int, default=os.cpu_count())
    args = ap.parse_args()

    paths = _image_list(args)
    missing = [p for p in paths.values() if not os.path.isfile(p)]
    if missing:
        raise SystemExit(f"missing: {' '.join(missing)}")

    # Logical images must fit the group, or update_engine refuses the payload.
    logical_bytes = sum(os.path.getsize(paths[n]) for n in LOGICAL)
    if logical_bytes > GROUP_BYTES:
        raise SystemExit(f"logical images {logical_bytes} > group {GROUP_BYTES}")

    os.makedirs(args.out_dir, exist_ok=True)
    manifest = um.DeltaArchiveManifest()
    manifest.block_size = BLOCK_SIZE
    manifest.minor_version = FULL_MINOR_VERSION
    manifest.max_timestamp = args.timestamp

    group = manifest.dynamic_partition_metadata.groups.add()
    group.name = GROUP_NAME
    group.size = GROUP_BYTES
    group.partition_names.extend(LOGICAL)
    manifest.dynamic_partition_metadata.snapshot_enabled = True

    # Blobs go to a temp file first: their offsets go into the manifest.
    with tempfile.TemporaryFile(dir=args.out_dir) as data, Pool(args.jobs) as pool:
        for name, path in paths.items():
            _add_partition(manifest, name, path, data, pool)

        # The signature blob sits after the data; its size is fixed for RSA-2048.
        manifest.signatures_offset = data.tell()
        manifest.signatures_size = len(_sig_blob(b"\0" * RSA_SIG_BYTES))
        man = manifest.SerializeToString()
        meta_sig_size = manifest.signatures_size
        header = struct.pack(">4sQQI", MAGIC, MAJOR_VERSION, len(man), meta_sig_size)

        # Metadata signature covers header + manifest; payload signature covers
        # header + manifest + data (not the metadata signature).
        meta_digest = hashlib.sha256(header + man).digest()
        meta_sig = _sig_blob(_sign(args.key, meta_digest))
        payload_hash = hashlib.sha256(header + man)
        data.seek(0)
        for block in iter(lambda: data.read(1 << 20), b""):
            payload_hash.update(block)

        pay_sig = _sig_blob(_sign(args.key, payload_hash.digest()))
        if len(meta_sig) != meta_sig_size or len(pay_sig) != manifest.signatures_size:
            raise SystemExit("signature blob size changed after signing")

        out = os.path.join(args.out_dir, "payload.bin")
        file_hash = hashlib.sha256()
        with open(out, "wb") as f:
            for part in (header, man, meta_sig):
                f.write(part)
                file_hash.update(part)

            data.seek(0)
            for block in iter(lambda: data.read(1 << 20), b""):
                f.write(block)
                file_hash.update(block)

            f.write(pay_sig)
            file_hash.update(pay_sig)

    # The four headers update_engine_client --headers wants.
    size = os.path.getsize(out)
    meta_size = len(header) + len(man)
    props = (f"FILE_HASH={base64.b64encode(file_hash.digest()).decode()}\n"
             f"FILE_SIZE={size}\n"
             f"METADATA_HASH={base64.b64encode(meta_digest).decode()}\n"
             f"METADATA_SIZE={meta_size}\n")
    with open(os.path.join(args.out_dir, "payload_properties.txt"), "w") as f:
        f.write(props)

    print(f"payload.bin {size} bytes, metadata {meta_size}")
    print(props, end="")


if __name__ == "__main__":
    main()
