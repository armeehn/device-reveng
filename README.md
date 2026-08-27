# RAV4 GT6-EAU Head-Unit: Root & Reverse-Engineering

Rooting and reverse-engineering a Choiceway / AiNavi **"GT6-EAU"** aftermarket Android 13 head unit (Qualcomm **QCM6125**) installed in a 2019 Toyota RAV4 (XA50) — full EDL backup, TWRP + Magisk root, decompiled vendor apps, and a documented MCU serial + reverse-camera protocol.

---

## ⚠️ Disclaimer

- This is **personal research on hardware I own**. It is shared in case it helps other owners of the same unit.
- **Not affiliated** with Choiceway, AiNavi, Shenzhen Choiceway, Toyota, or Qualcomm. All trademarks belong to their owners.
- **No firmware, ROM, or MCU images are included** — the stock firmware and decompiled vendor APKs are copyrighted by their vendors and are **not redistributed here**. Package names and protocol findings are documented for interoperability/repair purposes only.
- **No device secrets are included** — the full EDL backup, any credentials, pairing codes, IP addresses, and personal data are deliberately kept out of this repo. Placeholders like `<ip:port>` and `<pkg>` are used throughout.
- **This can brick your unit.** Flashing the wrong firmware or MCU image is a well-known way to permanently break these head units. Everything here is provided as-is, with no warranty. Proceed only if you understand the recovery path and accept the risk.

---

## Hardware summary

| Item | Value |
|---|---|
| Marketing name | AiNavi / Choiceway **GT6-EAU** (XDA "Ainavi H6" family) |
| Product/model/device (Android) | `GT6-CAR` |
| SoC | Qualcomm **QCM6125** (Trinket family, `ro.board.platform=trinket`, soc_id 467, Adreno GPU) |
| OS | Android **13** (`userdebug`, `ro.debuggable=0`) |
| Storage | **UFS**, Qualcomm A/B slot layout (~256 GB unit, `/data` ~221 GB) |
| Kernel | 4.14.190-perf |
| MCU | **RLC0_GT6E** (Hangrui / HR) — image `RLC0.bin` |
| CAN box | **HiWorld**, speaks the **0x2E serial protocol** |
| Reverse camera | External-HAL camera via **XS9922B** video decoder + AVM/Xyq360 stack |
| Bootloader | **Unlocked** (`verifiedbootstate=orange`, `flash.locked=0`, `vbmeta … unlocked`) |
| SELinux | **Permissive** |
| Build fingerprint | Spoofed to Pixel 3 XL (`google/crosshatch:13`) for Play/Android-Auto integrity |
| Flash port | Port labelled **"4PIN"** = EDL (`05c6:9008`) / fastboot |

> **Variant matters.** This is a **GT6-E** (MCU `RLC0_GT6E`), **not** GT6-SE (MCU `AT01_GT6SE`). Only GT6-EAU firmware is correct for this unit. The Android ROM is common across QCM6125 units, but the **MCU image is manufacturer-specific — never flash another vendor's MCU.**

---

## How it was rooted (high level)

The bootloader is already unlocked, so root is a standard EDL-backup → TWRP → Magisk flow. Order matters: **back up first.**

1. **Connect over Wi-Fi ADB.** Android "Wireless debugging" (pair once, then `adb connect <ip:port>`). Note: it turns **off on every reboot** and the port rotates — re-enable and re-read `<ip:port>` each session.
2. **Full EDL backup** (`backup.sh`). `adb reboot edl`, connect the **4PIN** USB cable, then dump every partition read-only. This is the safety net and a **hard prerequisite** for everything below.
3. **Boot TWRP in RAM** (`root.sh`). `adb reboot bootloader` → `fastboot boot recovery_ADB.img`. TWRP is *booted*, not flashed — nothing is persisted until Magisk installs.
4. **Install Magisk from TWRP over ADB.** The TWRP touchscreen is buggy on this unit, so it is driven entirely via `adb shell "twrp install /sdcard/Magisk.zip"`. The unlocked bootloader boots the patched `boot` image despite dm-verity, so there is no bootloop.
5. **Verify.** Install the Magisk app; `su -c id` → `uid=0 … u:r:magisk:s0`. The first `su` pops a **Grant dialog on the head-unit screen** that must be tapped physically.

Result: Magisk **30.7**, root confirmed, unit boots clean.

### Hardware access notes

- The **4PIN** USB port is the EDL/fastboot port. It needs a **USB-A ↔ USB-A data cable into the laptop's USB-A (host) port**. A USB-C-to-A cable into a laptop's USB-C port does **not** work — the CC resistor makes the C host ignore the device. This was a laptop-*port* problem, not a cable problem.
- On Linux, EDL/fastboot USB access needs root; here that is done via `pkexec` (start a polkit agent first).
- EDL entry: `adb reboot edl` on a booting unit (screen goes black-but-backlit = the EDL indicator). Hardware fallback for a dead unit is shorting two mainboard test points on power-on — not covered here.

---

## Repo contents

**Included (safe to share):**

- `README.md`, `STATUS.md`, `debloat-plan.md` — project docs and current state.
- `backup.sh`, `root.sh`, `debloat.sh`, `camera-diag.sh` — the runbook scripts (below).
- Reverse-engineering **findings** written up in this README (MCU protocol, camera signal-detection logic, package inventory).

**Not included (deliberately):**

- **Stock firmware / ROM / MCU images** (`update*.zip`, `RLC0.bin`, boot images) — copyrighted vendor material; source them yourself from the vendor / XDA thread.
- **Decompiled vendor APK sources** — copyrighted; only behavioral findings are documented here.
- **The EDL backup** (`backup-*/`) — it contains device-unique EFS/`persist`/modem data and is a security/privacy risk. Yours is your own safety net; keep it private.
- **Any credentials, IPs, pairing codes, or serials.**

---

## The scripts

All scripts default to the first `adb` device; most accept an explicit `<ip:port>` as `$1`.

### `backup.sh` — full EDL partition backup (READ-ONLY)
Precondition: unit already in EDL (`adb reboot edl`) with the 4PIN cable attached.
1. Checks for the Qualcomm `05c6:9008` device on USB.
2. Runs a **non-destructive `printgpt`** — this is the make-or-break test that the Firehose loader passes Sahara auth on this SoC. If it fails, it stops and writes nothing.
3. `edl.py rl <dir> --memory=ufs --loader=<loader> --genxml` dumps **all** partitions and emits `rawprogram*.xml` for later restore.

Writes nothing to the device. Verify `boot_a/boot_b`, `abl`, `vbmeta`, `dtbo`, modem/EFS are present and non-zero before trusting it.

### `root.sh` — TWRP + Magisk
`adb push` Magisk → `adb reboot bootloader` → `fastboot boot recovery_ADB.img` (RAM-only) → write a TWRP OpenRecoveryScript / `twrp install` over ADB. Intentionally stops **before** the final reboot so each step can be confirmed.

### `debloat.sh` — reversible debloat
`pm uninstall -k --user 0 <pkg>` for a curated Tier-1 list (Tier-2 commented out). Removes apps for the main user while keeping them in the system image, so anything is restorable with `cmd package install-existing <pkg>` (or a factory reset). Verifies each package exists before touching it.

### `camera-diag.sh` — reverse-camera latency capture
Dumps camera props/services/HAL, reads the AVM/AUXCamera stored config (root helps), then does a timed `logcat` capture while you **shift into reverse** — to measure trigger→first-frame latency and see which channel/signal the XS9922B decoder selected.

---

## Key technical findings

### MCU `0x2E` serial protocol
The head unit talks to the car via a **HiWorld CAN box** over a serial protocol whose frames use command byte **`0x2E`**. On Toyota, HiWorld profiles are largely interchangeable across cars ("try them all"). The MCU-facing app stack that carries this protocol lives in the `com.szchoiceway.canbus2` / `eventcenter` / `canoriginalcarmedia` apps (see package map). Live frame-level confirmation on the wire is still pending.

### Reverse-camera XS9922B auto-detection (root cause of the lag)
Decompiling **`AUXCamera.apk`** revealed the reverse camera runs through an **XS9922B video decoder with runtime signal auto-detection** — which is the "slow to appear" cause.

- The property `persist.camera.sensorcfg.signal` holds a **4-channel CSV** of the detected signal per camera.
- `getXS9922BSignalState()` selects a field by `mCameraChannel` (ch `"9"`→idx0, `"2"`→idx1, `"10"`→idx3, else idx2) and parses the last character as the signal code.
- `CameraManager.openCamera()` branches on camera type: `iBackCamType == 0` → `Camera.open(1)` on a **fast, fixed 800×480 path**; otherwise `Camera.open(0)` with **signal-based resolution** (the slow detect path). The detect loop retries via a handler with **100 ms delays**.
- On this unit the format is **not pinned** (`persist.camera.sensorcfg.resolution` is empty), so it auto-detects on every reverse.

**Camera signal codes** (`CamerasSignalDetection.java`):

| Code | Signal | Resolution |
|---|---|---|
| 0 | NO SIGNAL | — |
| 1 | NTSC | 720×480 |
| 2 | PAL | 720×576 |
| 3 | AHD 720p @25 | 1280×720 |
| 4 | AHD 1080p @25 | 1920×1080 |

The higher AVM layer (`com.ivicar.avm`, `camera-provider-2-4-ext` external HAL, AIS automotive-camera HAL in `/vendor/etc/camera/`) sits on top. The reverse **trigger** on this build is `ro.screen.reverse` (0/1) plus `Camera360Receiver` `CAR_*` events — **not** `sys.backcar.state`, which is empty here.

### Vendor package map (from the debloat plan)
- **Keep — car-critical:** `com.szchoiceway.canbus2`, `eventcenter`, `canoriginalcarmedia`, `com.lfg.szchoiceway.canupgrade`, `learn.key`, `gps`; cameras `com.szchoiceway.auxcamera`, `com.ivicar.avm`; audio/radio `com.choiceway.dsp`, `radio`, `zxwmedia`, `btsuite`; launcher `com.szchoiceway.customerui`; projection `com.zjinnova.zlink`; plus Google Play/GMS for Play + Android Auto.
- **Remove — telemetry/phone-home:** see Debloat below.

---

## Reverse-camera lag analysis

The #1 owner complaint is a laggy reverse camera. Findings:

1. **3D render is not the cause.** The reverse view is already forced to 2D (`AVM_REVERSE_FORCE_2D_REAR_VIEW=true` in the AVM prefs), so the delay is upstream of rendering.
2. **Signal auto-detection is the cause.** Because the resolution/signal is not pinned, each reverse triggers XS9922B probing with 100 ms handler retries before a frame appears (see above).
3. **Fix candidates** (to validate against a real drive capture before committing):
   - **Pin `persist.camera.sensorcfg.signal`** to the rear camera's real value so the decoder skips probing. The valid values are proprietary to `AUXCamera.apk` / `EventCenter.apk` / `Navigation.apk` — **decompile and confirm, do not guess.**
   - Or trim the detect loop's delay/retry count.
   - **Or take the vendor's Feb-2026 GT6-EAU firmware**, which explicitly *"improves rear camera performance"* (demod lives in the MCU `RLC0.bin`, so this is likely a real fix). Caveat: a 2026 ROM may **lock EDL** — see the warning below.

A drive-time `logcat` capture (`camera-diag.sh` / on-device capture script) is used to measure the actual trigger→first-frame latency and confirm which channel/signal is in use.

---

## Debloat / de-spyware

Reversible method: `pm uninstall -k --user 0 <pkg>` (kept in the system image; restore with `cmd package install-existing <pkg>`). Nothing is deleted from `/system`, and the full EDL backup is a further safety net.

**Tier 1 — telemetry / phone-home (recommended, in `debloat.sh`):**

| Package | What it is | Why remove |
|---|---|---|
| `com.szchoiceway.logcatupload` | Uploads device logcat to vendor | Phone-home (was running live) |
| `com.choiceway.logcapture` | Captures logs for upload | Phone-home companion |
| `com.szchoiceway.update` | OTA updater | Phone-homes; can push unwanted / EDL-locking updates |
| `com.es.file.explorer.manager` | ES File Explorer | Bundled adware / data collection |
| `com.syu.market` | 3rd-party app market | Bloat + ad/telemetry vector |
| `com.google.android.partnersetup` | Google partner setup | Partner telemetry |

**Tier 2 — bloat (optional, commented out):** X-Browser, vendor weather/photo/video/music/instructions apps, AOSP sample leftovers.

**Do not remove:** anything in the car-critical keep list above (CAN/MCU, cameras, audio/radio, launcher, projection, Play/GMS). After debloating, **reboot and verify reverse, steering-wheel controls, radio, Bluetooth, and Android Auto still work.**

Separate root-enabled win: the build fingerprint is spoofed to a Pixel 3 XL for Play/Android-Auto; a Magisk **Play Integrity Fix** module is the cleaner path to reliable Play + AA than the spoof.

---

## Recovery / safety (EDL)

- **The EDL backup is the safety net.** It was verified: 9.4 GB, all 6 LUNs, both A/B slots, all EFS (`persist`, `modemst1/2`, `fsg`, `fsc`), `super`, `boot_a/b`, with `rawprogram0-5.xml` for restore — zero empty files.
- **Recovery if root ever goes wrong:** flash the backed-up `boot_b` image back via fastboot or EDL (the unlocked bootloader allows `fastboot flash boot`).
- The Firehose loader authenticates on this SoC (proven by the `printgpt` step in `backup.sh`), and `adb reboot edl` reliably enters true EDL; the hardware test-point fallback exists for a non-booting unit.

> **🚫 Critical update warning.** A 2025+ Choiceway ROM is reported to **disable the unsigned EDL Firehose programmer** — after which EDL-based root/backup becomes **permanently impossible**. This unit shipped on a pre-lock build. **Do not let it take an OTA** (`com.szchoiceway.update`) unless you have accepted losing the EDL path. Update manually, deliberately, and only with the **correct GT6-EAU** firmware + `RLC0_GT6E` MCU — never another vendor's ROM or MCU image.

---

## Status

Done: hardware identified, Wi-Fi ADB, unlocked-bootloader + permissive-SELinux confirmed, full verified EDL backup, rooted via TWRP + Magisk, EDL recovery proven, vendor apps decompiled, MCU/camera protocols mapped.

Open: pin the reverse-camera signal (pending drive capture), run Tier-1 debloat, Play Integrity module, HVAC-over-CAN investigation, boot-time/perf. See `STATUS.md` for the live list.
