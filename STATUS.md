# RAV4 AiNavi Head-Unit Project — STATUS

_Last updated: 2026-08-27. Unit: Choiceway/AiNavi **GT6-EAU**, Qualcomm **QCM6125**, Android 13, in a 2019 Toyota RAV4 (XA50)._

## ✅ Done
- **Identified** the unit: GT6-EAU / QCM6125 / MCU `RLC0_GT6E` (Hangrui). Bootloader **unlocked** (orange), SELinux **permissive**.
- **ADB over WiFi** working (pairing required; port rotates each reboot).
- **Full EDL backup** — `backup-20260827-092204/` (9.4 GB, all 6 LUNs, both A/B slots, all EFS: persist/modemst1-2/fsg/fsc, super 6.4 GB). Verified, zero empty files. `rawprogram0-5.xml` present. **This is the safety net.**
- **ROOTED** — Magisk 30.7 (TWRP `fastboot boot` → install). `su -c id` → uid 0. Boots clean.
- **Recovery proven** — EDL loader `run/prog_firehose_Qcm6125_ddr.elf` authenticates; fastboot works over the 4PIN USB port.

## 🔑 Key facts / gotchas
- **4PIN USB port** = the EDL/fastboot port. Needs a **USB-A↔A DATA cable into the laptop's USB-A (host) port** — a USB-C-to-A cable into the laptop's C port does NOT work (CC resistor).
- `pkexec` needs `systemctl --user start hyprpolkitagent` first, then approve the GUI dialog. (Plain sudo can't prompt here.)
- Wireless debugging turns **OFF on each reboot** + connect port rotates — re-enable + read new `ip:port` each time.
- **Correct firmware** = GT6-EAU only (GT6E MCU). `gt6-fw/update13.zip` (Mar-2025) + `gt6-fw/RLC0.bin` (MCU). NEVER flash another vendor's MCU. The wrong Doro/HCT `~/Downloads/update.zip` + `hmcu.img` = DO NOT USE.
- Play Store IS installed; "unreliable Play/AA" = spoofed Pixel-3-XL fingerprint → fix with Magisk Play Integrity module.

## 🎯 Open goals (need device — owner at car)
1. **Reverse-camera lag (#1).** Root cause found: XS9922B signal **auto-detection** on each reverse (`persist.camera.sensorcfg.signal`, AUXCamera `CamerasSignalDetection`). Fix = pin the signal / trim detect loop. Reverse already forced 2D. Also: Feb-2026 firmware "improves rear camera." Drive capture pending at `/sdcard/rav4_capture/logcat.txt`.
2. **Debloat / de-spyware.** Plan in `debloat-plan.md`, batch in `debloat.sh` (Tier-1 active, reversible `pm uninstall --user 0`). Remove: logcatupload, logcapture, update, ES File Explorer, syu.market, partnersetup.
3. **HVAC controls don't work.** Investigating whether RAV4 climate is CAN-controllable or display-only (likely display-only).
4. **Play / Android Auto reliability** — Magisk Play Integrity module.
5. **Performance / boot-time.**

## 🗂️ Staged assets (all under /home/sasha/rav4-headunit/)
- `run/` — loader, TWRP (recovery_ADB.img / recovery_KB.img), Magisk.apk/.zip
- `edl/` — working edl.py; `backup-20260827-092204/` — the backup
- `gt6-fw/` — correct firmware + MCU (RLC0.bin) + rlc0-unpack/
- `mcu-analysis/` — jadx, pulled APKs, `auxcamera-src/` decompiled
- `recon/` — getprop, packages, partitions
- Scripts: `backup.sh`, `root.sh`, `debloat.sh`, `camera-diag.sh`, `analyze-capture.sh` (pending)

## ▶️ Next session (when back at the car)
1. Re-enable Wireless debugging → give new `ip:port` → reconnect.
2. Pull `/sdcard/rav4_capture/logcat.txt` → analyze reverse latency → pin camera signal.
3. Run `debloat.sh` (Tier 1) → reboot → verify car functions.
4. Apply Play Integrity module; re-pull truncated `canbus2.apk`.
5. Test HVAC per the investigator's live-test plan.

_16 offline analysis workers ran 2026-08-27; synthesized findings appended to project docs._
