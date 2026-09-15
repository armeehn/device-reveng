#!/usr/bin/env python3
"""Set the AVB "verification disabled" flags on a vbmeta image, as `fastboot
--disable-verity --disable-verification` does on the fly.

    AvbVBMetaImageHeader: magic "AVB0" at 0, ... rollback_index at 112, flags (u32 BE) at 120.
    flags = HASHTREE_DISABLED (1) | VERIFICATION_DISABLED (2) = 3

Why: a repacked system.img carries no hashtree, and the unit's live vbmeta has flags 0, so
init would still set up dm-verity from the descriptor and the slot would fail to mount.
Usage: vbmeta-disable.py IN OUT
"""
import struct
import sys

MAGIC = b"AVB0"
FLAGS_OFFSET = 120
HASHTREE_DISABLED = 1
VERIFICATION_DISABLED = 2


def main(src, dst):
    data = bytearray(open(src, "rb").read())
    if data[:4] != MAGIC:
        sys.exit(f"{src}: not a vbmeta image")
    before = struct.unpack(">I", data[FLAGS_OFFSET:FLAGS_OFFSET + 4])[0]
    struct.pack_into(">I", data, FLAGS_OFFSET, HASHTREE_DISABLED | VERIFICATION_DISABLED)
    open(dst, "wb").write(data)
    print(f"{dst}: flags {before} -> {HASHTREE_DISABLED | VERIFICATION_DISABLED}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
