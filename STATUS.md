# Project status

_Last updated: 2026-10-22 (`main` at `81d0de2`). Unit: Choiceway/AiNavi **GT6-EAU**, Qualcomm **QCM6125**, Android 13 stock, in a 2019 Toyota RAV4 (XA50); back in the car on Riposte OS 0.2 since 2026-09-22._

## Done

- **Identified** the unit: GT6-EAU / QCM6125 / MCU `RLC0_GT6E` (Hangrui). Bootloader **unlocked**, SELinux **permissive**.
- **ADB over Wi-Fi** working (pairing required; the port rotates on each reboot).
- **Full EDL backup** taken: all 6 LUNs, both A/B slots, all EFS partitions. Verified. **This is the safety net.**
- **Rooted** with Magisk 30.7 (TWRP `fastboot boot` → install). Boots clean.
- **Recovery proven**: the EDL loader authenticates; fastboot works over the 4PIN USB port.
- **Reverse-camera lag fixed**: root cause was XS9922B signal auto-detection; pinning the rear signal format stops the reset loop ([FINDINGS.md](FINDINGS.md)).

## Riposte OS (`os/`)

- **0.1** (stock Android 13 re-mastered, our apps beside the OEM ones) boots on the unit.
- **0.2** (AOSP 14 GSI, no OEM app, our own MCU owner) runs on the bench: boot, touch,
  gesture navigation, Wi-Fi, radio tune and seek on the real MCU, volume from the MCU,
  Bluetooth car-kit profiles, all 28 suite apps launching clean, Setup Doctor green.
- In the car since 2026-09-22. Car sessions drove fixes to the reverse feed, ACC standby,
  wireless CarPlay with a paired iPhone, and call audio. Headlamps and wired CarPlay are not
  yet proven; the ordered car checklist is [`os/ACCEPTANCE.md`](os/ACCEPTANCE.md).
- **Car service** (`launcher/carservice`): a priv-app signed with the image's own platform
  key (AOSP's public test key). It hosts the one MCU owner, draws the nav bar and serves an
  `IEventService` subset, so apps written for the vendor gateway still find one.
- **Updates.** The launcher, car service and suite update themselves over the network; signed
  A/B OS payloads go through `update_engine` with a slot that falls back if the launcher does
  not stay up. OS updates stay off until the supervised bench test passes ([`os/ota/`](os/ota/)).
- **Mainline Linux 6.18**: a RAM-only boot image for the unit, with panel, touch, MCU link,
  backlight and camera-decoder drivers ([`os/mainline/`](os/mainline/)). Not a usable system yet.
- Details, pipeline and the bench tools: [`os/README.md`](os/README.md).

## Key facts

- **4PIN USB port** = the EDL/fastboot port. Needs a **USB-A↔A data cable into a USB-A host port**. A USB-C-to-A cable into a USB-C port does NOT work (CC resistor).
- Wireless debugging turns **off on each reboot** and the connect port rotates.
- **Correct firmware** = GT6-EAU only (GT6E MCU). **Never flash another vendor's MCU.**
- Play Store is installed; Play/Android Auto flakiness comes from the spoofed Pixel 3 XL fingerprint. A Magisk Play Integrity module is the fix.

## Open goals

1. **Debloat / de-spyware.** Plan in [`debloat-plan.md`](debloat-plan.md), batch in `debloat.sh` (Tier 1, reversible `pm uninstall --user 0`).
2. **HVAC controls.** The stock climate keys are sent (0.1 through canbus2, 0.2 as the stock
   box frames), but the RAV4's answer is unverified; it may be display-only ([FINDINGS.md](FINDINGS.md)).
3. **Play / Android Auto reliability** via a Play Integrity module.
4. **Boot time** ([`boot-speed/`](boot-speed/)).

## Car Launcher (`launcher/`)

- **What it is.** `com.ripostelabs.carlauncher`, a Kotlin + Jetpack Compose HOME launcher written for
  this unit, talking to the vendor gateway `com.szchoiceway.eventcenter` per [`CAR_API.md`](CAR_API.md).
  Two Gradle modules: `carlib` (car integration layer) and `app` (UI). 1.0 is reserved for a polished
  public release; builds are numbered `0.7 (<versionCode>)` until then.
- **It is a HOME app, side-loaded.** Registers `MAIN + HOME + DEFAULT + LAUNCHER` and is picked
  from the Android home chooser; the stock `com.szchoiceway.customerui` stays installed.
- **What it does.** Home (media / nav / quick-launch + climate readout), app drawer, MediaSession
  player, radio over the vendor AIDL, read-only climate, vehicle dashboard with a provenance line per
  signal, driver profiles, notification shelf, on-screen keyboard, theming, Setup Doctor,
  backup/restore, self-update, and a full reskin of the vendor settings. Fully drivable from the
  steering-wheel keys. GPS-derived speed gates the distracting parts to parked-only.
- **What it does on Riposte OS 0.2**, where no OEM app is left. Cameras: the reverse picture with
  stock's dynamic guide lines, and a 360-camera grid. Chrome: its own nav bar and edge hotbar,
  call and volume popups over any app. Car: wheel-key learning, radio regions and presets, the
  stock DSP EQ and sound settings, factory JBL amp controls, tyre pressures (CAN box or a USB
  radio), the stock Toyota car settings and cluster text. Also a screensaver, one home widget
  and ACC standby.
- **It reads the car's own bus.** A CANable on the Toyota body bus behind the unit, over the
  Android USB host API — no kernel driver, no root. Speed, wheel speeds, steering, yaw and
  acceleration, brake pressure, rpm, gear, odometer, temperatures and ambient light, all verified
  against the car on 2026-09-09. Captures record unattended and roll at 16 MB.
- **Root tier is the top tier there will be.** The vendor platform signing key is
  **confirmed unobtainable** ([`CUSTOM_ANDROID.md`](CUSTOM_ANDROID.md) §2b), so a platform-signed
  `/system/priv-app` build is ruled out; Magisk root replaced it. A root helper receives the
  `signature`-protected broadcasts (SWC, day/night, reverse). Without root it all degrades silently.
  On 0.2 the image is ours, so the car service is platform-signed with the image's own key.
- **Blocked, do not read as done.** HVAC writes are sent but unverified on the car. The radar
  byte layout is UNCONFIRMED, so the manoeuvring strips stay hidden. Radio scan and RDS station
  text have no method in the vendor AIDL on 0.1; 0.2 decodes the station name from the MCU.
  Nothing is drawn over the reverse camera on 0.1 (vendor-composited); on 0.2 the launcher draws
  the picture.
- **Docs:** [`launcher/README.md`](launcher/README.md) (what each release shipped) and
  [`LAUNCHER_DESIGN.md`](LAUNCHER_DESIGN.md) (UI/UX spec and capability tiers).

## Workspace layout (all under `$RAV4_HOME`, default `~/rav4-headunit/`)

- `run/` — loader, TWRP (`recovery_ADB.img` / `recovery_KB.img`), Magisk
- `edl/` — edl.py; `backup-<date>/` — the EDL backup
- `gt6-fw/` — vendor firmware + MCU image (not redistributed)
- `recon/` — getprop, packages, partitions
- Scripts: `backup.sh`, `root.sh`, `debloat.sh`, `camera-diag.sh`, `analyze-capture.sh`
