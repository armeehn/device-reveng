# Findings & Action Plan (synthesized)

Consolidated results of a 15-worker static-analysis pass over the decompiled vendor
apps, the stock firmware, and the on-device recon. Device-specific identifiers are
omitted. Confidence is marked **[confirmed]** (from decompiled code) vs **[inferred]**.

---

## Reverse-camera lag — ROOT CAUSE FOUND
The reverse camera is handled by **`com.szchoiceway.eventcenter` → `BackcarEvent`**, NOT by
`AUXCamera` (which suppresses itself while reverse is active). The 360/AVM app
(`com.ivicar.avm`) is **dormant** here (`persist.ivicar.avm.state = 0`), so the rear view is
the simple XS9922B-decoded camera. **[confirmed]**

The "slow to appear" delay is the **XS9922B signal auto-detection**:
- Signal polled every **1000 ms** (`CamerasSignalDetection`, XS9922B path).
- "No-signal"/format debounce needs **>3 samples** (~3 s) before it commits.
- On a detected format change it does `closeCamera(); sleep(150ms); reopen`.
- The gear-trigger dispatch itself adds **0 ms** (the delayed-start handler is dead code).

### Fix (safe, reversible — apply rooted, validate with the drive capture)
- **Pin the rear signal** so detection is skipped/short. Props:
  - `persist.camera.sensorcfg.signal` = CSV; **field[1]** = rear cam; last char = signal code.
  - `persist.camera.sensorcfg.resolution` = `TYP1_CID0_VCH1_RES<res>` for the rear (vch=1).
  - Signal codes (this build): `0`=nosignal, `1`=CVBS-NTSC, `2`=CVBS-PAL, `3`=AHD720p25,
    `4`=AHD1080p25, `5`=AHD720p60, `6`=AHD1080p30, `7`=AHD720p30, `8`=CVBS-PAL60.
- Relevant vendor keys: `SYS_BACKCAR_VIDEO_TYPE`, `SYS_XS9922B_REVERSE_TYPE_KEY`,
  `SYS_REVERSE_SIGNAL_WEAKEN` (packed into MCU factory-set cmd `0x0F`).
- The drive capture (`/sdcard/rav4_capture/logcat.txt`) tells us the actual channel + signal
  code used, so we pin the exact value. Also consider the **Feb-2026 firmware** (below).

---


### ✅ RESOLVED (2026-08-27, confirmed on-device)
Root cause CONFIRMED live: `BackcarEvent` msg-513 health loop calls `isSignalOK()`; with the
rear signal on AUTO the PR2000 decoder oscillated (camera_status 7↔5↔3) and never passed the
check, so it ran `stopDetection→closeCamera→reset("r")→reconfigure→startDetection` every 1-3 s
= perpetual black-flash. **Fix that worked:** pin the rear format via
**Factory Settings → rear camera signal type → AHD 720p/30Hz** (setting `Sys_backcar_Video_Type`
in `content://com.szchoiceway.eventcenter.SysVarProvider/SysVar`). After pinning: one `start_stream`,
`camera_status` stable at 7, **zero resets** across a ~9 s reverse hold. Owner confirmed "seems better."
Note: ~0.6 s initial black at startup is normal signal-lock. If any residual issues, the Feb-2026
firmware ("improves rear camera performance") is the deeper demod-level fix.

## MCU ↔ Android serial protocol [confirmed]
- Port: **`/dev/ttyHS1` @ 115200 8N1** (`EventService.openSerialPort`).
- Internal MCU↔ARM framing: `0x0D 0x0A | len | payload | checksum(~(len+Σpayload)) | 0x00`.
  (The `0x2E … cksum=0xFF-Σ` frame is the **external CANBOX↔MCU** HiWorld link.)
- Reverse gear = opcode **`0x71`** (`onCmdSysEvent`), status **bit `0x02`**; sets
  `sys.backcar.state`. Air/climate = opcode `0x28` in; air-key commands out (A/C=8, Temp+=3,
  Fan+=1, AUTO=7 …).
- The truncated `canbus2.apk` was dex-carved and decompiled — full opcode set recovered.

## HVAC controls — likely a car limitation, not a bug
The app HAS a full climate **control** path (builds `0x2E` frames, sends over ttyHS1), but:
no **2019 RAV4 (XA50)** climate profile exists (only Gen4 + the Gen5 twins Wildlander/Harrier),
the HiWorld TYF2 box lists Toyota climate as **display-only**, and Toyota's HVAC ECU doesn't
accept injected set-commands. **[inferred, high]** Try Factory-Settings car-profile
(`Sys_CarType`) + decoder flag (`persist.zxw.sys.zhty.decoder.flag`); realistic ceiling is
accurate display. Live test: log ttyHS1 + logcat while tapping A/C vs. using the physical panel.

## Privacy / de-spyware
- **One** external phone-home in the analyzed apps: `com.ivicar.avm` → **`http://iov.ivicar.cn/`**,
  sends **IMEI over cleartext HTTP** (car-model store). AVM is dormant here, but removing/blocking
  it is worthwhile. **[confirmed]**
- **No** third-party analytics SDKs (Umeng/Baidu/Tencent/Bugly/etc.) in any analyzed app.
- Tier-1 debloat (`logcatupload`, `logcapture`, `update`, ES File Explorer, `syu.market`,
  `partnersetup`) validated — no launcher/car-function dependency. **[confirmed]**

## Play Store / Android Auto
Fingerprint spoof (Pixel 3 XL) is **baked into the ROM**, security patch 2022-08-05. For reliable
Play/AA on root, use a maintained **Play Integrity Fix / PlayIntegrityFork** Magisk module. Zlink
(`com.zjinnova.zlink`) handles wireless CarPlay/AA.

## Firmware / recovery
- **Feb-27-2026 GT6-EAU firmware exists and "improves rear camera performance"**, targets our
  RLC0_GT6E MCU (NOT GT6SE), on the thread's Google Drive. Risk: a 2026 ROM may lock the EDL
  programmer — but we already have root + full backup + a working loader, so it's low-risk to try.
- The backup's `lun4/boot_b.bin` is a **clean pre-root stock boot** (taken before Magisk) — ideal
  for recovery. Current on-device boot is Magisk-patched (root confirmed: `su → uid 0`).

## Audio / DSP
Tone/EQ/gain/balance is **MCU/amp-side over HiWorld serial**, not the Qualcomm ADSP.
`com.choiceway.dsp` is just a UI writing to EventCenter, which forwards to the amp. So audio
improvements route through MCU/amp settings, not Android audio HALs.

## Custom ROM — not recommended
No custom ROM/GSI supports the RLC0_GT6E / HiWorld MCU platform (the one QCM6125 ROM, Malaysk,
targets a different MTCH/HCTGQ MCU). **Stay stock (eng.ubuntu.20241227) + Magisk 30.7.**

## Performance
QCM6125, 8 cores (4×A73 + 4×A53), Adreno, kernel 4.14.190-perf, userdebug. Root enables
Magisk-based service trimming, governor/zram tuning, and disabling the phone-home/telemetry apps.
