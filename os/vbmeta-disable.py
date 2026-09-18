#!/usr/bin/env python3
"""Set the AVB HASHTREE_DISABLED flag on a vbmeta image (what `fastboot --disable-verity`
does on the fly), and nothing else.

    AvbVBMetaImageHeader: magic "AVB0" at 0, ... rollback_index at 112, flags (u32 BE) at 120.
    flags = HASHTREE_DISABLED (1)

Why: a repacked system.img carries no hashtree, and the unit's live vbmeta has flags 0, so
init would still set up dm-verity from the descriptor and the slot would fail to mount.

Why NOT VERIFICATION_DISABLED (2) as well: the unit's ABL reads the vendor's screen config
(`[zxw_Config]` at offset 0 of privdata2 -> `panel_size=`, `ZXWNoDebug`) inside its AVB
verification path. flags=3 skips that path and the panel comes up 720x1280 portrait instead
of 1920x720 (2026-09-18, bench). With flags=1 verification still runs; the unit is unlocked
(ro.boot.flash.locked=0), so a hash mismatch is an orange state, not a refusal.
Only the top-level vbmeta may carry flags: flash vbmeta_system verbatim.
Usage: vbmeta-disable.py IN OUT
"""
import struct
import sys

MAGIC = b"AVB0"
FLAGS_OFFSET = 120
HASHTREE_DISABLED = 1


def main(src, dst):
    data = bytearray(open(src, "rb").read())
    if data[:4] != MAGIC:
        sys.exit(f"{src}: not a vbmeta image")
    before = struct.unpack(">I", data[FLAGS_OFFSET:FLAGS_OFFSET + 4])[0]
    struct.pack_into(">I", data, FLAGS_OFFSET, HASHTREE_DISABLED)
    open(dst, "wb").write(data)
    print(f"{dst}: flags {before} -> {HASHTREE_DISABLED}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
