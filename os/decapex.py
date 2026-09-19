#!/usr/bin/env python3
"""Turn every compressed APEX (`X.capex`) in a system tree's /apex into the plain `X.apex` it
wraps, so apexd activates the module straight from /system.

    /system/apex/com.android.resolv.capex ──(zip member original_apex)──► com.android.resolv.apex

Why: an AOSP GSI ships its core modules compressed and apexd decompresses them into
/data/apex/decompressed at first boot. On a unit whose /data is full (the owner's media,
2026-09-18) that step fails, netd cannot link libnetd_resolv.so and zygote restarts forever
behind the boot animation. A pre-decompressed /system/apex takes /data out of the boot path.
Usage: decapex.py APEX_DIR
"""
import os
import sys
import zipfile

CAPEX = ".capex"
MEMBER = "original_apex"


def main(apex_dir):
    done = 0
    for name in sorted(os.listdir(apex_dir)):
        if not name.endswith(CAPEX):
            continue
        src = os.path.join(apex_dir, name)
        dst = os.path.join(apex_dir, name[:-len(CAPEX)] + ".apex")
        with zipfile.ZipFile(src) as z:
            with z.open(MEMBER) as m, open(dst, "wb") as out:
                out.write(m.read())
        os.chmod(dst, 0o644)
        os.remove(src)
        done += 1
    print(f"{apex_dir}: {done} compressed APEX(es) unpacked in place")


if __name__ == "__main__":
    main(sys.argv[1])
