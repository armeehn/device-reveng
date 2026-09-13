# Riposte OS — the head unit's system image

A flashable, reversible Android system for the GT6-EAU, built from the stock
firmware by a repeatable pipeline. The same overlay is meant to land on a
Riposte-designed head unit later, so nothing here depends on Choiceway's
software beyond what `/vendor` provides.

    ┌──────────────────────────────────────────────────────────────┐
    │ apps        CarLauncher (HOME, priv-app) + the 26-app suite   │  ours
    ├──────────────────────────────────────────────────────────────┤
    │ car layer   MCU serial owner on /dev/ttyS1, car state         │  today: eventcenter (OEM)
    │             broadcasts, reverse cam, SWC, radio, climate      │  0.2:   our car owner
    ├──────────────────────────────────────────────────────────────┤
    │ system      Android 13 framework — stock, unmodified          │  0.1: stock re-mastered
    │             (framework.jar / services.jar carry no vendor     │  0.2: AOSP 14 GSI
    │             code: verified 2026-09-08 and 2026-09-13)         │
    ├──────────────────────────────────────────────────────────────┤
    │ vendor      HALs: GPU, panel, audio, Wi-Fi/BT, camera decode  │  kept as-is, always
    └──────────────────────────────────────────────────────────────┘

## Why this is possible

`CUSTOM_ANDROID.md` §2b names one hard blocker: the platform signing key. It only
bites a system whose *framework* is re-signed. 0.1 keeps the stock framework, so
the platform-signed `SysVarProvider` and the OEM car apps keep running while our
apps sit beside them as ordinary priv-apps. §2d then found the car layer is one
serial link behind a public API, and the framework check closed the last unknown.

## Pipeline

    base/{system,product}.img ─unpack─► tree ─overlay─► tree ─repack─► out/{system,product}.img
    base/{boot,dtbo,vbmeta*}.img ──────────── copied verbatim ─────────► out/

| Script | Runs where | Does |
|---|---|---|
| `dump-base.sh` | x, as `sasha` | Pulls the active slot's `system product boot dtbo vbmeta vbmeta_system` over adb + su, in 64 MiB chunks that resume across the car's short appearances. Output `share/carlauncher/os/base/`. |
| `build.sh` | x, as root | Unpacks (ext4 loop mount / `fsck.erofs --extract`), removes packages, adds apps, writes the privapp allowlist, first-boot hook and props, repacks in the base's format. |
| `check.sh` | x, as root | Static proof of an output against its base. Framework byte-identical, kept packages present, removed ones gone, allowlist == the APK's permissions, labels preserved. |
| `test-fixture.sh` | x, as root | Builds a synthetic base (ext4 system with labels, erofs product with OEM package names) and runs build + check + a negative control. `FIXTURE PASS` is the gate for changes here. |
| `flash.sh` | laptop at the car | fastbootd to the **inactive** slot, `set_active`, reboot. Dry-run unless `--yes`. Rollback is one `fastboot set_active`. |

```
os/build.sh --base share/carlauncher/os/base --apps APPS --out share/carlauncher/os/0.1 --profile tier2
os/check.sh --base share/carlauncher/os/base --out share/carlauncher/os/0.1 --profile tier2 --suite 26
```
```
os/build.sh --base BASE --system share/carlauncher/os/gsi/system-td-arm64-ab-vanilla.img.xz \
            --apps APPS --out share/carlauncher/os/0.2 --profile gsi
```
`APPS/carlauncher.apk` is a release-signed launcher from launcher.hq; `APPS/suite/*.apk`
the served suite; `APPS/bootanimation.zip` optional. Version is `0.1+<date>.vc<launcher
versionCode>` (stock base) or `0.2+…` (GSI base), written to `ro.riposte.os.version`.

Profile `gsi` takes an AOSP GSI as `--system` (`.img` or `.img.xz`), keeps `product` from the
stock dump minus every OEM package (`overlay/remove.gsi`, prefix matches), and implies
`--car-owner`. The staged GSI is TrebleDroid `ci-20240508` (Android 14, fixes for old kernels;
the unit runs 4.14.190), both `arm64-ab-vanilla` and `-vndklite`, at
`share/carlauncher/os/gsi/` with `SHA256SUMS`. Try plain first, `vndklite` if it bootloops.

## Overlay

- `overlay/remove.tier1` — phone-home and adware packages, deleted from the image.
- `overlay/remove.tier2` — OEM apps the suite replaces. `CustomerUI` is never removed:
  eventcenter inflates its windows by name.
- `overlay/keep` — the build refuses to remove these and `check.sh` asserts them.
- `overlay/system/etc/init/riposte.rc` + `bin/riposte-firstboot.sh` — once per `/data`,
  hands the HOME role to CarLauncher.
- `bootanim/make.py` renders `bootanimation.zip` (wordmark + marigold bar, RL-BRAND-001 night
  palette, JetBrains Mono from the launcher) with Pillow, which lives in LXC 111, not on x.
  Drop the zip in `APPS/` and `build.sh` puts it at `product/media/`.
- The privapp allowlist is generated from the launcher APK. The unit runs
  `ro.control_privapp_permissions=enforce`: a priv-app requesting an unlisted
  privileged permission stops the boot, so every requested permission is listed.

## The car owner (0.2)

`launcher/carlib` `McuOwner` is our process on `/dev/ttyHS1`: `McuLink` (toybox `stty` at
115200 + file streams, no native code) → `McuSerial.Reader` → `McuOwnerProtocol` (the vendor's
startup handshake, SYS_EVENT/volume/key decode, power-off) → listener + `CanSignal` for the
`0xA5` relay. It refuses to start while `com.szchoiceway.eventcenter` is installed or
`ro.riposte.os.car_owner` is not `1`, because two readers on one tty split the stream. There is
no keepalive to send: the MCU never times out, and ACC comes from `sys.gotoSleep.state`.

## What the desk cannot prove

The fixture proves the pipeline, not the phone. Boot, reverse camera, wheel keys,
radio and climate readout need the car (Plane RAV4-82). The emulator farm is x86_64
and cannot run these images.

## Roadmap (Plane RAV4-78)

- RAV4-79 base images off the car · RAV4-80 pipeline (this) · RAV4-81 overlay content
- RAV4-82 flash 0.1 to the inactive slot, prove rollback
- RAV4-83 car owner daemon replacing eventcenter — the portability layer
- RAV4-84 0.2 on an AOSP 14 GSI · RAV4-85 `CARHAL.md`, the contract a Riposte board must meet
