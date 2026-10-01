#!/usr/bin/env bash
# mkboot_v2.py must keep the stock header's addresses, page size and os_version and place
# kernel, ramdisk and DTB on page boundaries where ABL reads them. On the real stock
# boot.img a repack of its own parts differed only in the 32-byte id field (2026-09-30).
# Runs anywhere with python3.
set -euo pipefail
cd "$(dirname "$0")"

T=$(mktemp -d)
trap "rm -rf $T" EXIT

python3 - "$T" <<'PY'
import struct, subprocess, sys
t = sys.argv[1]
PAGE = 4096

# a stock-like v2 header: distinctive addresses so a copied field is visible
hdr = b"ANDROID!" + struct.pack("<10I", 0, 0x8000, 0, 0x1000000, 0, 0xf00000, 0x100, PAGE, 2, 0x1a000123)
hdr = hdr.ljust(1652, b"\0") + struct.pack("<Q", 0x1f00000)
open(f"{t}/stock.img", "wb").write(hdr.ljust(PAGE, b"\0"))
for name, size in (("k", 5000), ("r", 300), ("d", 4096)):
    open(f"{t}/{name}", "wb").write(bytes([len(name) + size % 251]) * size)

subprocess.run([sys.executable, "mkboot_v2.py", f"{t}/stock.img", f"{t}/k", f"{t}/r", f"{t}/d",
                "console=tty0", f"{t}/out.img"], check=True)
b = open(f"{t}/out.img", "rb").read()

def fail(msg):
    print("FAIL:", msg)
    sys.exit(1)

ks, ka, rs, ra, ss, sa, tags, page, ver, osv = struct.unpack("<10I", b[8:48])
if (ka, ra, sa, tags, page, ver, osv) != (0x8000, 0x1000000, 0xf00000, 0x100, PAGE, 2, 0x1a000123):
    fail("stock header fields not copied")
if (ks, rs, ss) != (5000, 300, 0):
    fail(f"sizes {ks} {rs} {ss}")
if b[64:76] != b"console=tty0":
    fail("cmdline")
hsz, ds, da = struct.unpack("<I", b[1644:1648])[0], *struct.unpack("<IQ", b[1648:1660])
if (hsz, ds, da) != (1660, 4096, 0x1f00000):
    fail(f"v2 tail {hsz} {ds} {da:#x}")

# kernel at page 1, ramdisk at the next page boundary, DTB after it
k_off, r_off, d_off = PAGE, 3 * PAGE, 4 * PAGE
if b[k_off:k_off + 5000] != open(f"{t}/k", "rb").read():
    fail("kernel misplaced")
if b[r_off:r_off + 300] != open(f"{t}/r", "rb").read():
    fail("ramdisk misplaced")
if b[d_off:d_off + 4096] != open(f"{t}/d", "rb").read():
    fail("dtb misplaced")
if len(b) % PAGE:
    fail("image not page aligned")
PY

# a v1 stock header is refused, not silently converted
python3 -c "
import struct; h=b'ANDROID!'+struct.pack('<10I',0,0,0,0,0,0,0,4096,1,0)
open('$T/v1.img','wb').write(h.ljust(4096,b'\0'))"
if python3 mkboot_v2.py $T/v1.img $T/v1.img $T/v1.img $T/v1.img x $T/bad.img 2>/dev/null; then
  echo "FAIL: v1 header accepted"
  exit 1
fi
echo "MKBOOT-V2 PASS"
