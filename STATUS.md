# Project status

_Last updated: 2026-09-01. Unit: Choiceway/AiNavi **GT6-EAU**, Qualcomm **QCM6125**, Android 13, in a 2019 Toyota RAV4 (XA50)._

## Done

- **Identified** the unit: GT6-EAU / QCM6125 / MCU `RLC0_GT6E` (Hangrui). Bootloader **unlocked**, SELinux **permissive**.
- **ADB over Wi-Fi** working (pairing required; the port rotates on each reboot).
- **Full EDL backup** taken: all 6 LUNs, both A/B slots, all EFS partitions. Verified. **This is the safety net.**
- **Rooted** with Magisk 30.7 (TWRP `fastboot boot` → install). Boots clean.
- **Recovery proven**: the EDL loader authenticates; fastboot works over the 4PIN USB port.
- **Reverse-camera lag fixed**: root cause was XS9922B signal auto-detection; pinning the rear signal format stops the reset loop ([FINDINGS.md](FINDINGS.md)).

## Key facts

- **4PIN USB port** = the EDL/fastboot port. Needs a **USB-A↔A data cable into a USB-A host port**. A USB-C-to-A cable into a USB-C port does NOT work (CC resistor).
- Wireless debugging turns **off on each reboot** and the connect port rotates.
- **Correct firmware** = GT6-EAU only (GT6E MCU). **Never flash another vendor's MCU.**
- Play Store is installed; Play/Android Auto flakiness comes from the spoofed Pixel 3 XL fingerprint. A Magisk Play Integrity module is the fix.

## Open goals

1. **Debloat / de-spyware.** Plan in [`debloat-plan.md`](debloat-plan.md), batch in `debloat.sh` (Tier 1, reversible `pm uninstall --user 0`).
2. **HVAC controls.** RAV4 climate over this CAN box is most likely display-only ([FINDINGS.md](FINDINGS.md)); writes are not shipped.
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
- **Root tier is the top tier there will be.** The vendor platform signing key is
  **confirmed unobtainable** ([`CUSTOM_ANDROID.md`](CUSTOM_ANDROID.md) §2b), so a platform-signed
  `/system/priv-app` build is ruled out; Magisk root replaced it. A root helper receives the
  `signature`-protected broadcasts (SWC, day/night, reverse). Without root it all degrades silently.
- **Blocked, do not read as done.** HVAC writes, radar byte layout (GUESSED; the manoeuvring strips
  stay hidden), radio scan / RDS station text (no such method in the vendor AIDL), and anything drawn
  over the reverse camera (vendor-composited, out of scope).
- **Docs:** [`launcher/README.md`](launcher/README.md) (what each release shipped) and
  [`LAUNCHER_DESIGN.md`](LAUNCHER_DESIGN.md) (UI/UX spec and capability tiers).

## Workspace layout (all under `$RAV4_HOME`, default `~/rav4-headunit/`)

- `run/` — loader, TWRP (`recovery_ADB.img` / `recovery_KB.img`), Magisk
- `edl/` — edl.py; `backup-<date>/` — the EDL backup
- `gt6-fw/` — vendor firmware + MCU image (not redistributed)
- `recon/` — getprop, packages, partitions
- Scripts: `backup.sh`, `root.sh`, `debloat.sh`, `camera-diag.sh`, `analyze-capture.sh`
