# The unit on the bench

Since 2026-09-18 the GT6-EAU lives on a bench instead of the dash: every flash is a 5-minute
loop from the laptop, and a bad boot costs a 2-minute test-point cycle instead of a drive.

```
 12 V PSU (5 A) ─┬─ B+  (constant)      head unit ── 4PIN USB pigtail ── USB-A ── laptop
                 └─ ACC (tied to B+)                      │
 GND ────────────┬─ GND                                   └── Wi-Fi: adb <ip>:5555 (pinned)
                 └─ BRAKE (grounded = parked; the MCU never reports it, see below)
 open: ILL, AMP, BACK, KEY1/2, speakers, CAN box
```

- The harness BRAKE wire does nothing the MCU reports: its `71` brake bit stayed 0 at GND and
  at 12 V (raw frames, 2026-09-19). Stock ships with brake detection off. Do not gate anything
  on that bit.
- The unit wakes only with ACC high. Idle ~1 A, boot peaks ~3 A, the amp draws nothing
  without speakers. No CAN box needed: the car tiles stay empty.
- The 4PIN header is plain USB 2.0: `5V D- D+ GND`. Find 5 V and GND with a meter (the two
  outer pins), the middle two are data; swapping D+/D- is harmless, swapping 5 V/GND is not.

## Three doors into the unit

| State | Door | How |
|---|---|---|
| Booted, on Wi-Fi | adb over TCP | `adb connect <unit-ip>:5555` from any host on the same network (`--bench` builds pin the port); launcher installs, logs, shell. No laptop needed. |
| Booted, adb reachable, build host online | `bench-cycle.sh <vcNNN>` on the build host | Build + ship + flash in one command (see README) |
| Booted, adb reachable | fastbootd | `adb reboot fastboot`, then `fastboot-flash-set.sh DIR [wipe]` |
| Booted, no adb | none | 0.1 keeps the port in host mode after boot; 0.2 built with `--bench` keeps adb on it |
| Anything else | EDL | test point, then `edl-write-set.sh DIR SUPER` (with `mksuper.sh` first) |

Test point (vendor guide, step 4): unit unpowered, tweezers across the pads **1P8** and **B0**,
beside RST by the TO KB connector on the core board (photo in the workspace's `os/edl/`). Power
on with the short held; release after 5 to 10 s. Success: core-board LED off, backlight on,
`05c6:9008` on the laptop within seconds. Every reset variant (RST pinhole, ACC cycle, Sahara
reset) leaves a crashed unit in `05c6:900e`; none of them reaches 9008.

The loader upload works on a fresh enumeration only. Arm the laptop *before* the test point
(`arm9008.sh`-style: poll lsusb, then run the write set) so no seconds are lost.

## Rules learned the hard way

- **vbmeta flags = 1 only** (`vbmeta-disable.py`). flags=3 skips the bootloader path that reads
  the screen config from privdata2 and the panel comes up 720x1280 portrait.
- **0.1 (Android 13) <-> 0.2 (Android 14) needs a /data wipe each way**; `wipe`/`WIPE=1`.
- **fastbootd resizes one logical partition at a time**: shrink all four first
  (`fastboot-flash-set.sh` does), or a bigger system cannot grow past the old product.
- **Never reboot a slot after a failed flash step.** A mixed slot (0.1 system with 0.2
  product/vendor/boot) neither crashes to 900E nor brings up adb: unreachable until the test point.
- **edl-restore.sh only fits images into the extents the last flash left.** For anything
  bigger, `mksuper.sh` + `edl-write-set.sh`.
- **The laptop's login shell is zsh**: it does not word-split `$CMD args`. Run every multi-step
  job as a bash script, never as an inline ssh one-liner.
- **adb after a /data wipe**: pair once with Android's Wireless debugging (`adb pair ip:port
  code`), find the connect port (`nmap -p 30000-50000`), then `adb tcpip 5555` pins it. The
  vendor's `/system/xbin/su` fails; root is `/debug_ramdisk/su -c …` and needs the Magisk Grant
  tap once.
- **Timed tap experiments with a human fail.** Read a counter (`grep gt9xx /proc/interrupts`),
  ask for "tapped" afterwards, read it again.
- **The pigtail flips about 4 bits per GB on fastboot writes and reports nothing** (2026-09-20:
  73 bad blocks across the four images; `libsqlite.so` and `init.qcom.sh` broke, zygote32
  looped). A second fastboot pass flips other bits, so a reflash never converges. Run
  `bench-blockfix.sh` from the laptop instead: it diffs each partition against its image over
  USB adb and rewrites only the differing 4 KiB blocks, each hash-checked on the unit first.
  It also stalls: `usbreset 18d1:4ee0` right before a single `fastboot` call gets a write
  through, a run of commands in one script does not.
- **`bench-verify.sh` can never match `system` after a boot on the GSI.** Its `rw-system.sh`
  writes ~280 KB of ext4 metadata every boot (the same 24 blocks each time); the other three
  partitions do match. A `system` mismatch alone is proven or refuted at file level:
  `find /system -xdev -type f | xargs sha256sum` on the unit against the loop-mounted image.

## Facts about this unit

- Kernel 4.14.190, `ro.vendor.api_level=30`. TrebleDroid Android 14 builds from ci-20240401
  refuse it (`NetBpfLoad: Android U QPR2 requires kernel 4.19`); **ci-20240226 (UQ1A)** boots.
- The GSI's compressed APEXes need free /data to unpack; `decapex.py` unpacks them at build
  time instead. Four logical images must sum under the 6 GiB super.
- Panel: 1920x720 via a THCV33x SerDes; touch: Goodix GT9xx at i2c 1-0014, IRQ 221
  (gpio 88). The GSI needs two things stock did for it. A reader on `/dev/zxw_io`, else no interrupt
(`riposte-zxwio.sh`). And the real axis ranges: the chip scales X to 0..720 and Y to 0..1920
while the driver advertises the reverse, so `riposte-touchswap` (a uinput proxy, source in
`os/touchswap/`) re-emits the panel with the right ones. Writing `/proc/gt9xx_config` is accepted and changes nothing; an IDC
  cannot change a range. Read raw touches with `getevent -lt`, never from dumpsys.
