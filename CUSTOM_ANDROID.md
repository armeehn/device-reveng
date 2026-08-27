# Custom Android on the Choiceway GT6-EAU — Realistic Feasibility

_Unit: Choiceway/AiNavi **GT6-EAU**, Qualcomm **QCM6125 / trinket** (Snapdragon 665, sm6125),
Android 13, MCU **RLC0_GT6E**, HiWorld/Raise CANBOX. In a 2019 Toyota RAV4 (XA50)._
_Assessment date: 2026-08-27. Companion docs: `FINDINGS.md`, `STATUS.md`, `CAR_API.md`._

**Legend:** `[confirmed]` = from on-device recon / decompiled code / firmware. `[inferred]` =
reasoned from Treble/GSI mechanics + this device's props (high confidence unless noted).

---

## TL;DR verdict

A GSI **will almost certainly boot** on this unit — it's a textbook Treble device (arm64-v8a,
A/B + Virtual A/B, dynamic `super`, `ro.treble.enabled=true`, first_api_level 30, permissive
SELinux, unlocked bootloader). Core Android and most **vendor HALs survive**. But the entire
**Choiceway car layer breaks and cannot be cleanly restored on an AOSP-signed GSI**, because the
settings backbone (`SysVarProvider`) runs as `sharedUserId=android.uid.system` and is
platform-signed — a signature that will never match an AOSP GSI's platform key (which we don't
hold the private counterpart to). Result: no reverse-cam UI, no SWC, no radio, no climate display,
no ambient lighting, broken launcher. **Recommendation: stay stock + Magisk.** A GSI is worth
doing **only as a reversible weekend experiment** (A/B + full EDL backup make it safe), not as a
daily driver.

---

## 1. Treble / GSI compatibility — the device is a clean GSI target `[confirmed]`

From `recon/getprop.txt` + `recon/partitions_byname.txt`:

| Property | Value | Meaning for GSI |
|---|---|---|
| `ro.treble.enabled` | **true** | Treble split present → GSI-eligible |
| `ro.product.cpu.abi` | **arm64-v8a** | Use an **arm64** GSI (64-bit) |
| `ro.boot.dynamic_partitions` | **true** | `system`/`vendor`/`product` are **logical** inside `super` |
| `ro.build.ab_update` / `ro.boot.slot_suffix` | **true** / **`_b`** | **A/B** device, currently on slot **b** |
| `ro.virtual_ab.enabled` | **true** | Virtual A/B (snapshot) — use an **`-ab`** (not `-a/b`) GSI |
| `ro.build.system_root_image` | **false** | Modern layout, no legacy system-as-root quirks |
| `ro.product.first_api_level` | **30** | Vendor launched at Android 11 → vendor freeze (GRF) at 30 |
| `ro.vndk.version` (vendor) | **30** | Vendor built against **VNDK 30** |
| `ro.product.vndk.version` | 33 | (system/product side; current OS is 13) |
| `ro.board.platform` / `ro.vendor.qti.soc_id` | **trinket** / **467** | QCM6125 |

**Partition layout `[confirmed]`** (`partitions_byname.txt`): `super` (sda6), `metadata`,
`userdata`, and per-slot `boot_a/b`, `dtbo_a/b`, `vbmeta_a/b`, `vbmeta_system_a/b`. **No**
`system_ext`, `odm`, or `vendor_dlkm` partitions → those are folders merged into system/vendor
(fine for GSIs, which carry `/system/system_ext` internally).

**VINTF / VNDK outlook `[inferred, high]`:** Vendor is frozen at API/VNDK **30 (Android 11)**.
Under Treble GRF, a launch-30 vendor is guaranteed to run its own-version GSI and is designed to
carry forward. The current OS is already 13 (T). **A 14 (U) arm64 `-ab` GSI is the safe pick**
(one step past current system, well within the vendor-30 → system-34 support window). **15 (V)**
is plausible but riskier (V began deprecating VNDK); treat it as experimental. **Bottom line: the
device boots a GSI. The hard part is not booting Android — it's keeping the *car* working.**

---

## 2. What survives vs. breaks on a GSI (replaces `/system` only, keeps `/vendor` + `/product`)

**Mechanics `[confirmed via GSI practice]`:** `fastboot flash system system.img` in fastbootd
rewrites **only** the `system` logical partition. `vendor` and `product` are **untouched** and
still mount (the fstab that mounts them lives in stock `/vendor`). So the Choiceway `/product`
apps are **physically still on disk** after a GSI flash — but "present on disk" ≠ "works."

### 2a. Where the car software actually lives `[confirmed]` (`recon/packages*.txt`)
**Almost the entire Choiceway stack is in `/product/app/`** — it does *not* get wiped by a
system-only GSI flash:
- `com.szchoiceway.canbus2` (CANBUS), `canbusdebug`, `CanUpgrade`, `canoriginalcarmedia`
- `com.szchoiceway.eventcenter` (**EventCenter** — the MCU serial + event hub; owns reverse-cam)
- `com.szchoiceway.auxcamera` (AUX/reverse cam UI), `com.szchoiceway.radio` (Si479x radio)
- `com.szchoiceway.customerui` (**launcher/main UI**), `LearnKey` (SWC learn), `com.choiceway.dsp`
- `zxw_dashboard`, `XAmbientLight`, `XMulticolorLight`, `XGesturePlayer`, `Navigation`, `Gps`, etc.

**The one critical exception is in `/system`, which a GSI DOES replace:**
- `com.szchoiceway.providers.settings` → **`/system/priv-app/SysVarProvider/SysVarProvider.apk`**.
  This hosts `content://com.szchoiceway.eventcenter.SysVarProvider/SysVar` — the key/value store
  for **every** car setting (car type, `Sys_backcar_Video_Type`, radio favorites, illumination…).
  EventCenter and all car apps read/write through it (`CAR_API.md` lines 33, 156–194, 447).

### 2b. The fatal blocker: signatures, not file placement `[confirmed from CAR_API.md]`
`SysVarProvider` runs as **`sharedUserId=android.uid.system`** and is **platform-signed**
(`CAR_API.md` lines 41, 428, 433: "requires install to `/system/priv-app` + platform key").
An app declaring `android.uid.system` **must be signed with the same platform key as the running
framework.** On a stock ROM that's Choiceway's platform key. On an **AOSP GSI the framework is
signed with public AOSP test-keys** — a different key. Therefore:
- You **cannot** just copy `SysVarProvider.apk` back onto a GSI — it will refuse to run
  (signature ≠ platform / uid.system mismatch). Without it, EventCenter has no settings store →
  the car layer collapses.
- We **do not possess Choiceway's private platform key**, so we cannot re-sign the GSI framework
  to match the car apps either.

### 2c. Survives vs. breaks — summary `[inferred, high]`

| Subsystem | Lives in | On a bare GSI |
|---|---|---|
| GPU/Adreno, display panel (td4330/zxw), audio routing, Wi-Fi/BT, RIL, sensors | `/vendor` HALs | ✅ **Survive** (VNDK-30 vendor, GSI is back-compatible) |
| Camera decode (PR2000/XS9922B, `ais_server`) HAL | `/vendor` | ✅ HAL survives, but… |
| **Reverse-cam UI/logic** (EventCenter `BackcarEvent`, AUXCamera) | `/product` | ❌ **Breaks** — depends on SysVarProvider + likely Choiceway framework glue |
| **CANBUS / SWC** (canbus2, EventCenter, LearnKey) over `/dev/ttyHS1` | `/product` | ⚠️ ttyHS1 is world-RW so the *port* is reachable, but the apps FC without SysVarProvider/framework → **effectively broken** |
| **Radio** (Si479x UI) | `/product` | ❌ **Breaks** |
| **Climate display**, ambient/multicolor light, DSP UI, dashboard | `/product` | ❌ **Break** |
| **Launcher** (CustomerUI) | `/product` | ❌ Breaks → you land on the AOSP launcher |
| Car settings store (`SysVarProvider`) | **`/system/priv-app`** | ❌ **Deleted by the GSI flash** + can't be re-added (signature) |

**Can the Choiceway apps be side-loaded onto a GSI as user/priv apps?** `[inferred, high]`
Partially and painfully. Plain user apps (media/video/weather/navi) can be installed and will run.
But anything that (a) uses `android.uid.system`, (b) needs privapp-permissions whitelisting, or
(c) calls Choiceway's private framework APIs / a custom system service — i.e. the *actual car
functions* — will not work without re-signing **both** a custom-built GSI and every car app to a
**new shared platform key you generate**, then re-adding privapp-permission XMLs and any framework
glue. That's a from-scratch integration project, not a copy-paste, and even then framework-level
hooks (if Choiceway modified `framework.jar`/SystemServer) may be unrecoverable. **Not worth it.**

---

## 3. Ranked realistic paths

### (a) Stay stock + Magisk — **RECOMMENDED** ✅
- **Works:** everything (reverse cam, SWC, radio, climate display, launcher, DSP). Root already
  gives you the 90% that a "custom ROM" is usually chased for: debloat, de-spyware, governor/zram
  tuning, Play Integrity fix, service trimming, prop tweaks, the camera-signal pin fix.
- **Breaks:** nothing. **Effort:** none (done). **Risk:** none.
- This is the honest answer for a daily-driven car. See `FINDINGS.md` / `debloat-plan.md`.

### (b) GSI (Android 14) + keep stock vendor/product + try to re-add car apps — **experiment only** ⚠️
- **Works:** boots to AOSP 14; GPU/audio/Wi-Fi/BT/display via stock vendor; Play/Android-Auto
  cleaner; plain media apps re-installable.
- **Breaks:** the car layer (§2). Re-adding it is blocked by signatures/framework (§2b).
- **Effort:** ~1 evening to flash & see it boot; **weeks-to-never** to claw back car functions.
- **Risk:** low *to the hardware* thanks to A/B + EDL restore (§4); high that you end up with a
  tablet-in-a-dash that can't show the reverse camera. **Do this to learn, not to drive.**

### (c) Full AOSP / LineageOS device port — **not realistic** ❌
- sm6125 has LineageOS support **for phones** (ginkgo/willoww etc.), and a TWRP device tree exists
  (`twrpdtgen/android_device_qualcomm_trinket`), but **nothing for the GT6-CAR board**. You'd have
  to author a device tree, adapt/blob every HAL (panel td4330/zxw, XS9922B/PR2000 camera decoder,
  amp-over-serial audio, MCU), write sepolicy + kernel dtb — **and then reimplement the entire
  Choiceway car stack from scratch** (CANBUS/HiWorld protocol, reverse cam, SWC, radio) with **no
  vendor source**. **Effort:** many months, expert-level. **Verdict: do not attempt.**

### (d) Newer-Android GSI (15/V) as a pure reversible experiment — **curiosity only** 🧪
- Same breakage as (b), plus a higher chance of *not* booting (VNDK deprecation vs. vendor-30).
- Only interesting to answer "how new can this vendor go?" Flash to the inactive slot, look, revert.

---

## 4. Concrete try-it steps (for path b/d) + how to revert

**Why this is safe here `[confirmed]`:** (1) It's **A/B** — slot `_a` holds a full, independent
stock system; you can experiment on the active slot and `set_active` back. (2) You have a **full
EDL backup** (`backup-20260827-092204/`, all 6 LUNs, both slots, `rawprogram0-5.xml`) and a
**working firehose loader** (`run/prog_firehose_Qcm6125_ddr.elf`). Nothing here is one-way.

### Pick the GSI
- **Type:** `arm64` + **`-ab`** (A/B / Virtual-A/B), Android **14** (U). 64-bit, A/B — matches
  `arm64-v8a` + `ro.virtual_ab.enabled=true`.
- **Recommended image:** a **phh AOSP** GSI (`system-arm64-ab-vanilla` / `...-gapps`) or Google's
  official AOSP 14 GSI. Because vendor is VNDK 30 + SELinux is already **permissive**, the phh
  AOSP build is the most forgiving first try. If the standard image bootloops on the merged
  layout, try the **`-vndklite`** variant.

### Flash while preserving vendor + product
```bash
# 0. Re-establish adb (port rotates each reboot) and confirm slot
adb connect <ip>:<port>
adb -s ... shell getprop ro.boot.slot_suffix        # expect _b

# 1. Enter userspace fastboot (fastbootd) — required for logical partitions
adb -s ... reboot fastboot                          # note: 'fastboot', not 'bootloader'
fastboot devices

# 2. (Recommended) neutralize verity/verification so the AOSP-signed system boots
fastboot --disable-verity --disable-verification flash vbmeta       gt6-fw/out/vbmeta.img
# (flashes to the ACTIVE slot's vbmeta; stock vbmeta on the other slot stays intact)

# 3. Flash ONLY system — vendor + product logical partitions are untouched
fastboot flash system system-arm64-ab-<ver>.img
#   If it errors "not enough space", free room WITHOUT touching vendor:
#   fastboot delete-logical-partition product_<slot>   # sacrifices Choiceway /product apps only
#   (system auto-grows). Vendor stays. This is the expected trade for a bare GSI test anyway.

# 4. Data must be wiped when crossing ROMs
fastboot -w
fastboot reboot
```
Notes: keep the **stock kernel** — GSIs reuse `boot` (kernel 4.14.190), so you do **not** flash
boot. Current `boot_b` is Magisk-patched; GSI+Magisk coexists, or restore the clean
`backup.../lun4/boot_b.bin` first if you want a vanilla base.

### Revert — three independent nets (any one suffices)
1. **A/B rollback (fastest):** boot to bootloader → `fastboot set_active a`. Slot `_a` still holds
   full stock 13 → reboots as the untouched factory head unit. (Then later re-sync `_b` from `_a`.)
2. **Restore stock system to the test slot:** in fastbootd, `fastboot flash system` the stock
   system image extracted from `gt6-fw/update13.zip` (vendor/system/product imgs are in there),
   and `fastboot --disable-verity flash vbmeta` the stock `vbmeta`.
3. **Full EDL restore (ultimate):** boot to EDL (4PIN USB port, USB-A↔A data cable to a host
   USB-A port — see `STATUS.md`), authenticate `run/prog_firehose_Qcm6125_ddr.elf`, and replay
   `backup-20260827-092204/rawprogram*.xml` to rewrite `super` + boot + vbmeta to factory. This
   recovers even a fully bricked/wiped `super`.

---

## Honest closing verdict (1 paragraph)

The GT6-EAU is a clean Treble target (arm64, A/B + Virtual-A/B, dynamic `super`, first_api_level
30, permissive SELinux, unlocked bootloader), so an **Android 14 arm64 `-ab` GSI will almost
certainly boot and keep most stock-vendor hardware working** — but that's a trap, because the
entire Choiceway car experience (reverse camera, steering-wheel controls, radio, climate display,
ambient lighting, DSP, and the launcher itself) **dies on a GSI and cannot be cleanly restored**:
the settings backbone `SysVarProvider` runs as `android.uid.system` under Choiceway's platform
key, which no AOSP-signed GSI can satisfy, and we don't hold that private key. A full AOSP/Lineage
port is months of expert work with no vendor source and is not realistic. The rational path is
**stay on stock + Magisk** — root already delivers the debloat/tuning/Play-Integrity/camera-fix
wins people chase custom ROMs for — and treat a GSI strictly as a **reversible curiosity**: thanks
to the A/B layout and the full EDL backup, you can flash a 14 GSI to a slot, watch it boot into a
car-less tablet, and roll back in minutes with `fastboot set_active` or an EDL restore.
