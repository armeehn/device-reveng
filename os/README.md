# Riposte OS: the head unit's system image

A flashable, reversible Android system for the GT6-EAU, built from a backup of the stock
firmware by a repeatable pipeline. The same overlay is meant to run on a head unit of our
own design later, so nothing here depends on the vendor's software beyond what `/vendor`
provides.

    ┌──────────────────────────────────────────────────────────────┐
    │ apps        Car Launcher (HOME, priv-app) + the 28-app suite  │  ours
    ├──────────────────────────────────────────────────────────────┤
    │ car layer   MCU serial owner on /dev/ttyHS1, car state,       │  0.1: eventcenter (OEM)
    │             keys, radio, volume, reverse, power               │  0.2: our car owner
    ├──────────────────────────────────────────────────────────────┤
    │ system      Android framework, unmodified                     │  0.1: stock 13, re-mastered
    │             (the vendor's framework.jar / services.jar carry  │  0.2: AOSP 14 GSI
    │             no vendor code: verified twice in September 2026) │
    ├──────────────────────────────────────────────────────────────┤
    │ vendor      HALs: GPU, panel, audio, Wi-Fi/BT, camera decode  │  kept as-is, always
    └──────────────────────────────────────────────────────────────┘

## Status

| | 0.1 | 0.2 |
|---|---|---|
| Base | stock Android 13 system, re-mastered | TrebleDroid AOSP 14 GSI (`ci-20240226`, the last one that boots on kernel 4.14) |
| OEM apps | phone-home and adware removed, car apps kept | none |
| Car link | vendor gateway | `McuOwner`, our process on the serial link |
| Proven on the unit | boots, touch, launcher | boot, touch, gesture navigation, Wi-Fi, radio tune and seek, volume from the MCU, Bluetooth car-kit profiles, 28 suite apps launch clean, Setup Doctor green, wireless CarPlay on the panel (the OEM daemon with the projection suite as its app, `ZLINK_REWRITE.md` §9) |
| Waiting for the car | reverse camera, wheel keys, headlamps, a paired phone | reverse camera, wheel keys, headlamps, CarPlay audio through the amp, wired CarPlay |

Switching between 0.1 and 0.2 on one unit needs a `/data` wipe: Android 13 refuses a
`/data` that Android 14 has touched.

## Why this is possible

`CUSTOM_ANDROID.md` names one hard blocker: the platform signing key. It only bites a system
whose framework is re-signed. 0.1 keeps the stock framework, so the platform-signed
`SysVarProvider` and the OEM car apps keep running while our apps sit beside them as
ordinary priv-apps. The car layer then turned out to be one serial link behind a public
API, which is what 0.2 replaces.

## Pipeline

    base/{system,product}.img ─unpack─► tree ─overlay─► tree ─repack─► out/{system,product}.img
    base/{boot,dtbo,vbmeta*}.img ──────────── copied verbatim ─────────► out/

| Script | Runs where | Does |
|---|---|---|
| `from-edl.sh` | build host | Turns an EDL dump (`backup.sh` output) into the base directory: logical partitions out of `super.bin` with a checksum-pinned `lpunpack.py`, physical ones copied. |
| `dump-base.sh` | build host | Pulls the active slot's `system product boot dtbo vbmeta vbmeta_system` over adb + su, in 64 MiB chunks that resume across the car's short appearances. |
| `build.sh` | build host, root | Unpacks (ext4 loop mount or `fsck.erofs --extract`), removes packages, adds apps, writes the privapp allowlist, the suite's default permissions, the first-boot hook and props, repacks in the base's format. |
| `check.sh` | build host, root | Static proof of an output against its base: framework byte-identical, kept packages present, removed ones gone, allowlist equal to the APK's permissions, labels preserved. |
| `test-fixture.sh` | build host, root | Builds a synthetic base (ext4 system with labels, erofs product with OEM package names), runs build + check + a negative control. `FIXTURE PASS` is the gate for changes here. |
| `magisk-patch.sh` | build host | Magisk-patches a boot image on the desk with the Magisk APK's own `magiskboot`; `build.sh --boot` takes the result. |
| `flash.sh` | laptop at the car | fastbootd to the inactive slot, `set_active`, reboot. Dry run unless `--yes`. Rollback is one `fastboot set_active`. |
| `edl-restore.sh` | laptop at the car | Restores a slot backup over EDL in one go: verifies the sums, reads the GPT, writes the physical partitions, reads super's metadata, writes each logical image into its existing extents, resets. `EDL_RESTORE.md` has the steps by hand. |
| `edl-extents.py` | laptop at the car | Turns an `lpdump` listing into `edl ws` writes, converting liblp's 512-byte sectors to the device's 4096-byte sectors. |
| `test-edl-extents.sh`, `test-edl-restore.sh`, `test-vbmeta-flags.sh` | anywhere | Host-only tests against stubbed tools (CI: `os-ci.yml`). |
| `mksuper.sh` | laptop at the bench | Builds a full `super` image for any image set with the unit's geometry (6 GiB, virtual A/B), for `edl-write-set.sh` when the images no longer fit the extents the last flash left. |
| `edl-write-set.sh` | laptop at the bench | Writes super + boot/dtbo/vbmeta over EDL from 9008; `WIPE=1` erases userdata; then resets. |
| `fastboot-flash-set.sh` | laptop at the bench | Flashes an image set in place from fastbootd, shrinking the logical partitions first; `wipe` erases userdata. `BENCH.md` has the wiring, the doors and the rules. |
| `bench-verify.sh` | laptop at the bench | Hashes every logical partition on the unit against the set's `SHA256SUMS` over adb root, before the first boot is trusted. fastboot has no payload checksum and a marginal link once flipped bits in a dozen files without an error. |
| `bench-blockfix.sh` | laptop at the bench | Repairs a flash the pigtail corrupted without another fastboot pass: diffs each logical partition against its image over USB adb and rewrites only the differing 4 KiB blocks, each hash-checked on the unit before it is written, then re-hashes the partition. |
| `bench-cycle.sh` | build host, root | One 0.2 bench iteration: a release launcher (checksum verified), `build.sh --profile gsi --bench`, rsync to the laptop, `fastboot-flash-set.sh`, then `bench-verify.sh`. Fails loudly on a failed flash or a partition that differs. |
| `bench-ui.sh` | any host with adb | Reads the panel without a camera: `texts`, `tap <label>`, `find <label>`, `doctor` (opens Setup Doctor, prints its rows), `sweep` (launches every suite app, reports the ones that crash or stay behind a dialog). Compose exposes its texts to uiautomator. |

```
os/build.sh --base BASE --apps APPS --out OUT --profile tier2
os/check.sh --base BASE --out OUT --profile tier2 --suite 28
```
```
os/build.sh --base BASE --system system-td-arm64-ab-vanilla-ci20240226.img.xz \
            --apps APPS --out OUT --profile gsi [--bench] [--tools CACHE]
```

`APPS/carlauncher.apk` is a release-signed launcher; `APPS/suite/*.apk` the suite;
`APPS/bootanimation.zip` optional. The version is `0.1+<date>.vc<launcher versionCode>`
(stock base) or `0.2+…` (GSI base), written to `ro.riposte.os.version`.

`--tools CACHE` adds the debug toolbelt (`tools/README.md`): static nmap, ncat, nping,
tcpdump, socat, strace, gdb, gdbserver and busybox under `/system/riposte/bin`,
each on PATH through `/system/bin`, plus Termux as a product app. `tools/fetch.sh CACHE`
fills the cache from `tools/tools.lock` (sha256-pinned); `bench-cycle.sh` passes it by
default and pushes the data-side tools (frida-server) to `/data/local/riposte/bin` after
the flash.

Profile `gsi` takes an AOSP GSI as `--system` (`.img` or `.img.xz`), removes every OEM
package (`overlay/remove.gsi`, prefix matches) and implies `--car-owner`. TrebleDroid
`ci-20240226` (Android 14 QPR1) is the last build that boots on this kernel: from
`ci-20240401` on, Android's BPF loader requires kernel 4.19. A GSI is system-as-root;
`system_root()` in `lib.sh` finds the right directory for either layout. A GSI links
`/product` to its own `/system/product`, so on this profile the suite and the boot animation
go into the system image. The GSI's compressed APEXes need free `/data` to unpack;
`decapex.py` unpacks them at build time instead. `--bench` keeps the bench aids (adb over
Wi-Fi, the USB port held in peripheral mode, persisted logcat); a car build must not carry
them.

## What the GSI does not do by itself

Each of these cost a bench session; each is one small service or prop in the overlay.

- **Panel orientation.** The vendor's SurfaceFlinger honours `ro.sf.hwrotation=90` from the
  bootloader; AOSP 14 reads `ro.surface_flinger.primary_display_orientation` instead.
  The bootloader's screen config also lives inside its AVB path, so `vbmeta` must carry
  flag 1 (hashtree disabled), never 3, or the panel comes up portrait.
- **Touch.** The Goodix panel only raises interrupts while `/dev/zxw_io` is held open
  (`riposte-zxwio.sh`), and it scales X to 0..720 and Y to 0..1920 under a driver that
  advertises the reverse. `riposte-touchswap` (source in `touchswap/`) grabs the driver's
  device and re-emits it on a uinput touchscreen with the real ranges. Writing
  `/proc/gt9xx_config` is accepted and changes nothing; an IDC cannot change a range.
- **The MCU port.** On the GSI `/dev/ttyHS1` carries a label a priv-app cannot open.
  `riposte-mcubridge.sh` (root, from init) serves it on a loopback socket and `McuOwner`
  rides that carrier (`riposte.mcu.link=tcp:`).
- **Navigation.** The vendor's fascia keys inject HOME and BACK system-wide; a priv-app
  cannot. Gesture navigation stays on (swipe up is HOME) so a foreign app is never a trap.
- **First boot.** `riposte-firstboot.sh` runs once per `/data`: HOME role to the launcher,
  no screen timeout, no lock screen, the grants Setup Doctor would otherwise ask for, the
  gestural overlay. `build.sh` also writes a `default-permissions` XML so the suite never
  opens on a permission dialog.
- **Clock and zone.** No cell network, so the image sets `America/Vancouver`; the MCU's
  battery-backed RTC (`0x83` frames) sets the clock while offline, and the first GPS fix of
  each wake corrects both (`GpsClock`, then `0x13` to the MCU). The first boot turns the
  location master switch on, which a GSI leaves off.

## Bluetooth on 0.2: car-kit roles

A GSI is a phone build: its Bluetooth stack runs the phone-side profiles (A2DP source, HFP
AG, AVRCP target) and leaves the car-kit roles off, so a phone can neither stream music to
the unit nor hand it a call. Android 13+ picks profiles by system property at stack start.
Profile `gsi` keeps the `@carkit` lines of `overlay/props`:

    bluetooth.profile.a2dp.sink.enabled=true        unit plays the phone's music
    bluetooth.profile.hfp.hf.enabled=true           unit is the hands-free
    bluetooth.profile.avrcp.controller.enabled=true unit drives the phone's player, gets metadata
    bluetooth.profile.pbap.client.enabled=true      contacts / call log from the phone
    bluetooth.profile.map.client.enabled=true       messages from the phone
    bluetooth.profile.a2dp.source.enabled=false     ┐ the phone-side roles the GSI ships;
    bluetooth.profile.hfp.ag.enabled=false          │ off as in AOSP automotive
    bluetooth.profile.avrcp.target.enabled=false    ┘
    bluetooth.device.class_of_device=38,4,8         Audio/Video · Car Audio

`ro.riposte.os.bt_carkit` says which set the image carries. Setup Doctor reads the three
car-kit profiles on the bench; audio through the amp and a call are car tests. The
launcher's `BtCarKit` (carlib) drives the profiles on 0.2.

## Overlay

- `overlay/remove.tier1`: phone-home and adware packages, deleted from the image.
- `overlay/remove.tier2`: OEM apps the suite replaces. `CustomerUI` is never removed on 0.1:
  eventcenter inflates its windows by name.
- `overlay/remove.gsi`: every OEM package.
- `overlay/keep`: the build refuses to remove these and `check.sh` asserts them.
- `overlay/props`: appended to `build.prop` through `envsubst`; `@carkit`, `@gsi` and
  `@bench` lines by profile and flag.
- `overlay/system/etc/init/riposte.rc` and `overlay/system/bin/riposte-*`: the first-boot
  hook and the services above.
- `bootanim/make.py` renders `bootanimation.zip` (wordmark and marigold bar, night palette,
  JetBrains Mono from the launcher). Drop the zip in `APPS/` and `build.sh` places it.
- The privapp allowlist is generated from the launcher APK. The unit runs
  `ro.control_privapp_permissions=enforce`: a priv-app requesting an unlisted privileged
  permission stops the boot, so every requested permission is listed.

## The car owner (0.2)

`launcher/carlib` `McuOwner` is our process on the MCU link: `McuLink` (a tty or a socket,
no native code) → `McuSerial.Reader` → `McuOwnerProtocol` (the vendor's startup handshake,
SYS_EVENT / volume / key / radio / RTC decode, power-off) → listeners, plus `CanSignal` for
the `0xA5` relay. It refuses to start while the vendor's eventcenter is installed or
`ro.riposte.os.car_owner` is not `1`, because two readers on one tty split the stream. It
reopens the link after any failure. There is no keepalive to send: the MCU never times out,
and ACC comes from the MCU's own state frames. The MCU also streams a 6-byte `0x8E` frame at
10 Hz (a G-sensor sample the vendor only stores); the owner logs an unhandled opcode once.

## The session at the car

`ACCEPTANCE.md`: the ordered step list with expected outcomes and the undo at each step.
`BENCH.md`: how the unit lives on a bench between car sessions, which door (adb over Wi-Fi,
fastbootd, EDL) fits which state, and the rules that keep a flash from bricking it.

## What the desk cannot prove

The fixture proves the pipeline, not the phone, and the bench proves the unit, not the car.
Reverse camera, wheel keys, headlamps and a phone on the car-kit profiles need the car.
The emulator farm is x86_64 and cannot run these images.

## Next

- Prove the RTC clock path on a fresh flash (the image's allowlist grants `SET_TIME`).
- A proper SELinux label for the MCU port instead of the loopback bridge.
- `CARHAL.md`: the contract a head unit of our own design must meet so this overlay runs on
  it unchanged.
