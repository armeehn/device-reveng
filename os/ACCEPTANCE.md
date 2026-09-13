# Riposte OS: the session at the car

One sitting, laptop on the 4PIN USB port (USB-A to USB-A data cable into a USB-A host port,
see `STATUS.md`), unit on ACC. Each step names what it proves and how to undo it. Stop at the
first step that fails; the rest is not worth the seat time.

## Step 0 (optional, at the car): full EDL image over the 4PIN port

The unit's USB port only speaks EDL/fastboot. `adb reboot edl` (screen goes black but backlit),
plug the USB-A-to-A cable into a USB-A port on the laptop, then `backup.sh` with
`RAV4_UPLOAD=<rsync destination on the build host>`. Minutes, versus hours of car-on time for the
over-the-air dump. Afterwards `os/from-edl.sh --edl <dump> --out share/carlauncher/os/base`
produces the base directory below. Exit EDL with `edl reset` or a power cycle.

## Before leaving the desk

- [ ] `share/carlauncher/os/base/` has `system.img product.img boot.img vbmeta.img` with
      `.sha256` sidecars and `BASE-INFO` (`dump-base.sh assemble` reports every partition, or
      `from-edl.sh` ran).
- [ ] `os/build.sh --base … --profile tier2` and `os/check.sh` PASS → `share/carlauncher/os/0.1/`.
- [ ] `os/flash.sh --images share/carlauncher/os/0.1` dry run prints the plan; note the target slot.
- [ ] Laptop has `adb` + `fastboot` ≥ 34 and can `adb connect` the unit.

## Step 1: flash to the inactive slot (RAV4-82)

```
os/flash.sh --images <0.1 dir> --yes
```
Proves: fastbootd accepts the images (logical partition resize), the slot switches.
Expect: reboot within ~2 min, the Riposte boot animation, CarLauncher as HOME with no chooser.
Undo: `adb reboot bootloader; fastboot set_active <old slot>; fastboot reboot`.

## Step 2: stock car functions still live (0.1 keeps eventcenter)

| Check | Do | Expect |
|---|---|---|
| Reverse camera | Shift to R | Camera within 1 s, launcher hides its chrome |
| Wheel keys | Vol+/−, NEXT, PREV, PLAY | Launcher reacts; volume chip moves |
| Radio | Launcher radio screen: seek up | Frequency changes, audio |
| Climate readout | Change fan | Home climate readout follows |
| Day/night | Headlamps on | Night theme |
| Phone | Incoming call | btsuite UI over the launcher |
| Play/AA | Open Play Store | Loads |
| Debloat | `adb shell pm list packages` | No Tier-1 package |
| Version | `adb shell getprop ro.riposte.os.version` | `0.1+<date>.vc<N>` |

Proves: a re-mastered stock system loses nothing. Undo: as step 1.

## Step 3: roll back and forward once

`fastboot set_active <old>` → boots stock → `set_active <new>` → boots 0.1.
Proves: the daily driver is one command away either way. Do not skip this.

## Step 4: first bytes on the port (RAV4-83, still on 0.1)

`McuOwner` must NOT run here (eventcenter owns the port; the gate refuses). Instead:
```
adb shell "getprop ro.riposte.os.car_owner"          # expect 0
adb logcat -d | grep -c McuOwner                      # expect 0
```
Proves: the gate holds on a stock-derived slot.

## Step 5: 0.2 on the same inactive slot (RAV4-84)

Build with `--profile gsi --system …/gsi/system-td-arm64-ab-vanilla.img.xz`, flash as step 1.
Expect: AOSP 14 boots (up to 5 min first time), CarLauncher HOME, `McuOwner` status Running
in Setup Doctor / logcat with `acked=true`.

| Check | Expect | If not |
|---|---|---|
| Boot | Launcher within 5 min | Try the `vndklite` image; else `set_active` back |
| `logcat \| grep McuOwner` | `Running(acked=true, frames>0)` | `acked=false, frames>0`: ACK formula wrong; `frames=0`: wrong port or baud |
| Reverse | SYS_EVENT bit → launcher reacts | Camera UI is ours and may not exist yet: note, not fail |
| Volume key | `79` frames, chip moves | |
| Headlamps | Night theme | |
| Radio seek | Audio changes | The MCU may need the config blocks `startup()` omits |
| Wi-Fi, BT pairing, audio out | Work (vendor HALs) | |

Undo: `set_active` back to stock. Everything here is expected to be partial; the point is the
list of what the MCU does without the vendor's config frames.

## After

- Journal entry with the table filled in, frame counts, and every "if not" that fired.
- `share/carlauncher/os/<version>/RESULT.md` next to the images that were flashed.
