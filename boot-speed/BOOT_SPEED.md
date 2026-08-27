# Cold-boot speed — GT6-EAU / QCM6125 head unit

Goal: make the **cold boot** faster. In winter the unit fully powers down between
drives (weak/cold 12 V battery, cars sitting > 24 h, low-voltage cutoff), so the
normal suspend-to-RAM resume never happens and you pay the full cold-boot cost
most mornings.

## What a cold boot is actually made of

From `ro.boottime` (times are seconds since kernel start):

| Window | What runs | ~time | Tunable? |
|---|---|---|---|
| 0–9 s | kernel + init first-stage + fs mounts | 9 s | mostly no |
| 9–14 s | HALs, zygote start, vendor userspace daemons | 5 s | a little |
| **14–27 s** | **Android framework: zygote preload, system_server, PackageManager scan, ActivityManager starts boot apps** | **~13 s** | **yes — biggest block** |
| 27–32 s | `bootcomplete_zxw`, EventCenter `initSystemParam` (pm-disable loop), `zlink5`, modem tail (`fibo_wccd`) | 5 s | partly |

Two facts that shape the fixes:
- **Storage is already lean:** `/data` is **unencrypted** (no FBE/metadata crypto),
  **no forced fsck**, zram 1 GB on. No easy multi-second win in the mount path.
- **The 14–27 s block has zero init-service milestones** — it's pure framework
  work, dominated by PackageManager reading app code off cold UFS. Cold flash =
  slower reads = that's why winter boots drag. Levers: fewer apps to scan, and
  don't let the CPU idle-downclock while doing this one-time heavy work.
- **No MCU pre-boot delay applies to us.** `SYS_POWER_ON_SWITCH_TO_ARM_TIME_KEY`
  (20 s) only fires on ZhongHang-TY decoder panels (`mIsZHTYDecoderPane`); our
  `persist.zxw.sys.zhty.decoder.flag=0` (HiWorld/Raise CANBOX), so it's never sent.

## Ranked interventions (do in this order, measure between each)

### 0. Measure first — `./measure-boot.sh`
Snapshot `ro.boottime` before any change. Re-run after each step. Don't fly blind.

### 1. Pin CPU to performance during boot  ⭐ best value / lowest risk / no reflash
Cold SoC + heavy one-time boot work — force max clocks through boot, restore the
stock governor a few seconds after boot completes. Two Magisk drop-in scripts:
- `post-fs-data.d__10-boot-perf.sh` → `/data/adb/post-fs-data.d/10-boot-perf.sh`
- `service.d__90-restore-gov.sh`    → `/data/adb/service.d/90-restore-gov.sh`

Deploy: **`./deploy.sh`** then `adb reboot`. Fully reversible (delete both files).
Expected: a few seconds off the 14–27 s block, more the colder it is.
Also bumps boot-time readahead (2 MB) for faster sequential dex/app reads.

### 2. Trim boot-time apps (debloat, conservative)
Every app PackageManager doesn't have to scan and every `BOOT_COMPLETED` receiver
that doesn't fire = less cold-read work in the 14–27 s window. Tier-1 already done
(logcatupload, logcapture, update, es.file.explorer, syu.market, partnersetup
removed; ivicar.avm disabled). Tier-2 candidates — disable, don't uninstall, so
it's `pm enable`-reversible, and **only non-car apps**:
```
# check what's installed & enabled first:
adb shell pm list packages -e | sort
# example safe disables (verify each is unused before running):
adb shell pm disable-user --user 0 com.google.android.tts       # if no TTS use
adb shell pm disable-user --user 0 com.android.bookmarkprovider
```
Do NOT disable: EventCenter (`com.szchoiceway.eventcenter`), zlink
(`com.zjinnova.zlink`), camera/AUX apps, or anything in `com.choiceway.*` tied to
HVAC/SWC/reverse. Expected: 1–4 s. Measure — skip if it doesn't move the number.

### 3. Cut idle drain so the battery survives standby (attacks the root cause)
This doesn't speed a cold boot — it makes **fewer** cold boots happen, which in
winter is the bigger prize. The unit has a cellular modem but **no SIM**
(`gsm.sim.state=ABSENT,ABSENT`) and you run over the iPhone hotspot. Two live
RILDs + `fibo_wccd` scan for a network that isn't there, drawing power in standby.
Lowest-risk reversible fix — force airplane mode (kills the RIL scan, keeps WiFi):
```
adb shell su -c 'settings put global airplane_mode_on 1; \
  am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true'
# WiFi still works; revert with state 0 / airplane_mode_on 0
```
(EventCenter already forces airplane mode on ACC-off, so this just makes it stick.)

### 4. Kill the kernel serial console  — real win, needs a boot.img reflash
`ro.boot.console=ttyMSM0`: every kernel `printk` blocks on a 115200 UART during
boot, and it's worse when cold (slower CPU, each blocking write costs more).
Removing `console=ttyMSM0`/`earlycon` from the boot cmdline typically saves 1–3 s.
Cost: repack boot.img cmdline + reflash + re-Magisk. We have the proven flash path
(TWRP `fastboot boot` + Magisk) and a stock boot backup (`backup-*/lun4/boot_b.bin`,
`gt6-fw/out/boot.img`) for rollback. Do this LAST, after 1–3 are validated, because
it's the only step that can require a reflash-from-backup if it goes wrong.
Procedure (draft — confirm before running):
```
# on device (rooted), or unpack gt6-fw/out/boot.img on the laptop:
magiskboot unpack boot.img
#  edit the extracted cmdline: drop 'console=ttyMSM0,115200n8' and 'earlycon...'
magiskboot repack boot.img boot-noconsole.img
#  Magisk-patch boot-noconsole.img in the Magisk app (keeps root), then:
adb reboot bootloader && pkexec fastboot flash boot magisk_patched-*.img
```

### Not worth doing
- **Patching EventCenter's `initSystemParam` pm-disable loop** (each iteration
  busy-waits + `Thread.sleep(1000)`, `EventService.java:8454-8551`): it runs on a
  background thread and doesn't gate time-to-usable; patching the core car-service
  APK risks breaking reverse cam / HVAC / SWC. Skip.
- **dexopt tuning**: already sane (`pm.dexopt.post-boot=extract`,
  `first-boot=verify`); no boot dexopt on a stable build.
- **Extending `SYS_SLEEP_TIME` (2→3 = 24 h→48 h)** to avoid the >24 h-sit cold
  boots: tempting, but longer RAM-retention = more parasitic draw on an already
  cold-weak battery = risk of a dead battery. Only consider if the car is driven
  often enough that the battery clearly tolerates it. Not recommended blind.

## Perceived-speed bonus (optional, zero real-boot cost)
Replace the vendor `bootanimation.zip` (`/system/media/` or `/product/media/`) with
a short looping animation — it plays 12→27 s today; a clip that ends the moment
boot completes makes the wait *feel* far shorter even though wall-clock is the same.

## Rollback (everything above is reversible)
- Scripts (step 1): `rm /data/adb/post-fs-data.d/10-boot-perf.sh /data/adb/service.d/90-restore-gov.sh` → reboot.
- Debloat (step 2): `pm enable <pkg>` or `cmd package install-existing <pkg>`.
- Airplane (step 3): set state false / `airplane_mode_on 0`.
- boot.img (step 4): reflash `backup-*/lun4/boot_b.bin` (pre-root stock) or the
  Magisk-patched stock boot via `fastboot flash boot`.
