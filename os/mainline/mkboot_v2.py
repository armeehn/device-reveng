"""Pack a boot image (header v2) for the GT6-EAU from a kernel, ramdisk and DTB.

Addresses, page size and os_version come from the stock boot.img so ABL treats the new image
exactly like the old one. Only kernel, ramdisk, DTB and cmdline change.

Usage: mkboot_v2.py STOCK_BOOT KERNEL RAMDISK DTB CMDLINE OUT
"""
import struct
import sys

HEADER_V2 = 2
HEADER_SIZE_V2 = 1660


def pad(data, page):
    return data + b"\0" * ((page - len(data) % page) % page)


def main():
    stock, kernel, ramdisk, dtb, cmdline, out = sys.argv[1:7]
    h = open(stock, "rb").read(4096)
    assert h[:8] == b"ANDROID!", "stock image is not a boot image"
    (_, kaddr, _, raddr, _, saddr, tags, page, ver, osver) = struct.unpack("<10I", h[8:48])
    assert ver == HEADER_V2, f"stock header v{ver}, want v2"
    dtb_addr = struct.unpack("<Q", h[1652:1660])[0]

    k, r, d = (open(f, "rb").read() for f in (kernel, ramdisk, dtb))
    cmd = cmdline.encode()
    assert len(cmd) < 512, "cmdline longer than the v2 field"

    hdr = b"ANDROID!" + struct.pack("<10I", len(k), kaddr, len(r), raddr, 0, saddr, tags, page, ver, osver)
    hdr += b"\0" * 16                      # name
    hdr += cmd.ljust(512, b"\0")           # cmdline
    hdr += b"\0" * 32                      # id (unused by ABL)
    hdr += b"\0" * 1024                    # extra_cmdline
    hdr += struct.pack("<IQI", 0, 0, HEADER_SIZE_V2)   # recovery dtbo size, offset, header size
    hdr += struct.pack("<IQ", len(d), dtb_addr)
    assert len(hdr) == HEADER_SIZE_V2

    with open(out, "wb") as f:
        for part in (hdr, k, r, d):
            f.write(pad(part, page))


if __name__ == "__main__":
    main()
