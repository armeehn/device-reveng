# Car Launcher (`com.reveng.carlauncher`)

A custom **companion HOME launcher** for the Choiceway GT6-EAU head unit
(Android 13 / API 33, 1920x720 landscape @240dpi, **rooted**). It integrates with the
vendor gateway `com.szchoiceway.eventcenter` per [`../CAR_API.md`](../CAR_API.md).

> **Companion, not a replacement.** This app registers as an *alternative* HOME
> (`MAIN + HOME + DEFAULT + LAUNCHER`). The user chooses whether to make it the default
> home from the system chooser; the stock launcher (`com.szchoiceway.customerui`) stays
> installed. Privileged car actions (writing SysVar, reverse/SWC/day-night broadcasts) go
> through **root** or require installing as a privileged/system app — see below.

## Module structure

```
launcher/
├── settings.gradle.kts          # includes :app and :carlib; google/mavenCentral/JitPack repos
├── build.gradle.kts             # AGP 8.5.2, Kotlin 2.0.20 (+ compose compiler plugin), apply false
├── gradle.properties            # AndroidX, non-transitive R, parallel/caching
├── gradle/wrapper/…             # Gradle 8.9 wrapper
│
├── carlib/                      # Android library — the car integration layer
│   ├── build.gradle.kts         # namespace com.reveng.carlauncher.carlib, aidl=true, libsu dep
│   ├── consumer-rules.pro       # keep vendor AIDL/Parcelable names
│   └── src/main/
│       ├── AndroidManifest.xml  # <uses-permission com.szchoiceway.permission.broadcast>
│       ├── aidl/com/szchoiceway/eventcenter/
│       │   ├── ICommunication.aidl   # gateway → app callback (notifyMessage/checkIsActive)
│       │   ├── ICallbackfn.aidl      # radio/EQ/CAN setter callback (signature = TODO)
│       │   └── IEventService.aidl    # bound control service (SUBSET; ordinals = TODO)
│       └── java/com/reveng/carlauncher/carlib/
│           ├── CarEvents.kt     # BroadcastReceiver → Flows/callbacks (reverse, ACC, SWC, day/night)
│           ├── SysVar.kt        # ContentResolver read + root `content` write of SysVarProvider
│           ├── RootShell.kt     # `su -c` via libsu (reflective) or ProcessBuilder fallback
│           └── CarService.kt    # binds vendor EventService via IEventService AIDL
│
└── app/                         # Android application — the launcher UI
    ├── build.gradle.kts         # applicationId com.reveng.carlauncher, compose, targetSdk 33
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml  # HOME activity, singleTask, landscape, QUERY_ALL_PACKAGES
        ├── res/…                # dark car theme, strings, adaptive launcher icon
        └── java/com/reveng/carlauncher/
            ├── MainActivity.kt      # ComponentActivity + setContent; wires CarEvents/CarService
            ├── AppRepository.kt     # queryIntentActivities(MAIN/LAUNCHER) + launch
            └── ui/
                ├── theme/           # Color.kt / Type.kt / Theme.kt (Material3 dark)
                ├── HomeScreen.kt    # app drawer + status bar + widgets + reverse overlay
                ├── StatusBar.kt     # clock + ACC/day-night chips
                ├── AppDrawer.kt     # LazyVerticalGrid of launchable apps, big tap targets
                ├── MediaCard.kt     # placeholder now-playing card
                ├── ReverseOverlay.kt# full-screen black overlay on reverse (camera TODO)
                └── ComposeUtil.kt   # lifecycle-aware Flow → State helper
```

## Build

The Android SDK / JDK live under `/home/sasha/android-tools/`. Source the env first, then
assemble the debug APK:

```bash
source /home/sasha/android-tools/env.sh   # sets ANDROID_HOME / JAVA_HOME / PATH
cd /home/sasha/projects/device-reveng/launcher
./gradlew :app:assembleDebug
```

Output APK: `app/build/outputs/apk/debug/app-debug.apk`.

> `carlib` pulls `com.github.topjohnwu.libsu:core` from JitPack. If the network blocks
> JitPack, `RootShell` still works via its pure-`ProcessBuilder` `su -c` fallback — you can
> drop the libsu line from `carlib/build.gradle.kts` in that case.

## Deploy

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# then press HOME and pick "Car Launcher" (set as default if you want it to be the home)
```

To go from *normal app* to *privileged/system app* (needed for `signature`-level vendor
broadcasts and direct SysVar writes without a root shell — CAR_API §6.4), on this rooted
device push to `/system/priv-app` and platform-sign, then reboot:

```bash
adb root && adb remount
adb push app-debug.apk /system/priv-app/CarLauncher/CarLauncher.apk
adb reboot
```

## Capability notes (see CAR_API.md §6.4)

| Capability | As normal app | Notes |
|---|---|---|
| Be HOME, app drawer, launch apps | ✅ | standard |
| Unprotected events (ACC, media, radio) | ✅ | |
| Protected events (reverse `ACTION_BACKCAR_*`, SWC `STEER_WHEEL_INFOR`, day/night) | ⚠️ only if granted `com.szchoiceway.permission.broadcast` | likely `signature` → needs system app; `CarEvents` falls back to unprotected `MCU_MSG_BACKCAR_*` |
| Read SysVar provider | ✅ | `SysVar.getString/readAll` |
| Write SysVar provider | ❌ direct | `SysVar.putString` routes through **root** (`content` shell) |
| Bind `EventService`, read-only AIDL | ✅ | exported service |
| AIDL control side-effects | ⚠️ | best as system app |

## Settings suite (v1.1 → v2.0)

A full, reskinned mirror of the vendor GT6 settings — see
[`SETTINGS_ROADMAP.md`](SETTINGS_ROADMAP.md). Every category the vendor exposes is
rebuilt from our `CarTheme` palette and reachable from **Settings** on Home:

- **Launcher, Display & Illumination, Reverse camera, Parking radar, Audio & EQ, Climate,
  Radio, Steering wheel, Power & sleep, System & about** — curated screens with friendly
  toggles / sliders / pickers over the vendor SysVar store and the `IEventService` AIDL.
- **All settings (advanced)** — a raw browser over the *live* SysVar table, so all **455**
  vendor keys are reachable even where we haven't catalogued a friendly control.

Backing pieces: `data/CarSettingsController` (live snapshot + optimistic root-fallback
writes), `data/SettingKeys` (curated key catalog), `ui/settings/*` (the reskinned kit,
`SettingsHost` back-stack, and `SettingsHub` menu). Writes need root / a privileged install
(CAR_API §2.2); the hub warns when root is absent. Enum option sets are inferred from
firmware naming (the vendor settings APK that holds the value tables isn't in the decompile),
so each guessed mapping is annotated in-code and the Advanced browser shows the true strings.

## Motion awareness (v2.5)

`CarEvents.speedKmh` is real. The gateway broadcasts no numeric speed
(`SHOW_CAR_SPEED_EVENT` is a show/hide toggle, CAR_API §1.3), so `carlib/GpsSpeedSource`
reads it from the GNSS receiver — the one source a normal app can use — smooths the jitter,
and reverts to unknown after 5 s without a fix rather than reporting a stationary car.

`CarEvents.motion` turns that into the `PARKED` / `MOVING` / `UNKNOWN` verdict behind the
LAUNCHER_DESIGN §1.4 rules, with an 8 km/h ↑ / 3 km/h ↓ hysteresis band so a car creeping in
traffic can't flap the gate.

**Unknown fails open.** Blocking on `UNKNOWN` would lock the driver out of their own launcher
in a garage, an underground car park, or on a unit where the location permission was never
granted — permanently and with no signal as to why. We only ever fail open with *no* reading;
any live fix above 3 km/h resolves to `MOVING`.

Parked-only, via the `LocalParkedOnlyLock` composition local (`ui/ParkedOnly.kt`):

- app search and its keyboard (open search closes itself if the car pulls away),
- the theme editor,
- the advanced SysVar browser,
- destructive confirmations (reboot, factory reset).

Settings ▸ Launcher ▸ **Motion gating** toggles enforcement and shows the live speed and
verdict — needed to tell "gate open because parked" from "gate open because GPS never fixed",
which look identical from the outside.

Grant the permission with:

```sh
adb shell pm grant com.reveng.carlauncher android.permission.ACCESS_FINE_LOCATION
```

### Motion budget

Nothing animates on a loop, and no transition exceeds **400 ms** (today's longest is the 320 ms
onboarding slide). A moving car is when an animation is most distracting and least noticed, so
new work stays inside that budget: no `rememberInfiniteTransition`, no `basicMarquee`, and no
`tween` above 400 ms on a surface that can be on screen while driving.

`ui/CarFeedback.kt` gives eyes-free confirmation on accepted input — haptics plus the car's own
`beep()` — wired to steering-wheel presses, keyboard keys and dialog buttons. The beep follows
the vendor's `Set_TouchBeep` preference instead of adding a competing one.

## Root-native tier (v2.9)

The vendor's platform signing key is **confirmed unobtainable**, so the launcher can never
be a platform-signed system app and the old "install to `/system/priv-app`" tier is closed
for good. Magisk root plus permissive SELinux buys back most of what CAR_API §6.4 reserved
for signature-level access. This is that tier.

Everything below **degrades silently without root** — no dialog, no error, the v2.5
behaviour stands. Settings ▸ **Root tier** shows what is actually working.

### Protected broadcasts, captured as root

`STEER_WHEEL_INFOR` and the day/night backlight events are sent with
`com.szchoiceway.permission.broadcast` (CAR_API §1.1), almost certainly `signature`. AMS's
`checkComponentPermission` short-circuits to GRANTED for uid 0 before it consults granted
permissions, so a **root process** receives them without holding anything.

`carlib/RootBroadcastHelper` is that process: our own APK's dex run under
`su` as a bare `app_process`, registering a receiver on the system context and printing one
line per event. `carlib/RootBroadcastBridge` reads those lines back and feeds them into the
existing `CarEvents` flows, so **no consumer changed** — `swcKeys`, `reverse` and `dayNight`
simply become reliable. `CarEvents.rootCapture` goes true on the first captured event.

Parsing `logcat` was the other option and was rejected: AOSP's event log records broadcast
*discards* and receiver *finishes*, never a dispatch carrying extras — and an SWC press is
nothing but its extras. Guessing at undocumented vendor debug lines to recover a key index
would mis-decode steering-wheel keys, which is worse than not having them.

### Persistent root write channel

`SysVar.putString` forked a `su` per write, which the settings suite made audible on every
slider tick. `carlib/RootSession` holds one `su` open and serialises commands over its
stdin, behind the existing `RootShell.exec` API (libsu → RootSession → `su -c`).

**The shell-injection protections are unchanged.** `RootSession` only changes the transport
of an already-built command; callers still single-quote every interpolated value (now via
the one shared `RootShell.quote`) and still SQL-escape what goes inside a `--where`. A
command containing a newline would split into two root commands on a stdin channel, so such
a command is refused and falls back to `su -c`, where a newline is safely inside one argv
entry.

### Vendor status & nav bar

Settings ▸ Root tier ▸ **Hide vendor bars** writes the SysVar keys
`Sys_Statusbar_Icon_Config_Key` and `SYS_SHOW_TOOL_NAVI_BAR_WND` (CAR_API §6.3) — the
*persistent* config the vendor stack reads for itself, so it survives a reboot.

Both are **GUESSED** and the toggle is opt-in for that reason: the first key is an icon
*list*, not a flag, and the second is quoted in CAR_API in the vendor's constant-name form,
whose stored keyname usually differs. Three things keep that safe — the toggle only writes a
key that **already exists** in the live table (a wrong guess changes nothing rather than
inserting junk into the vehicle's config store), it records each key's original value before
the first hide, and turning it off writes those originals back verbatim.

### Sole-HOME mode, and how to get back

`pm disable-user com.szchoiceway.customerui`, behind a destructive confirm (which the v2.5
parked-only lock withholds while the car is moving).

The vendor launcher is **not** an Android HOME app — no activity of it declares
`category.HOME` (`../CUSTOMERUI_NOTES.md` §0) — so disabling it cannot orphan the HOME role.
The real risk is that the gateway inflates its status bar and side-window layouts *out of*
that package via `createPackageContext` (CUSTOMERUI_NOTES §3g), and how it copes with the
package being gone is untested.

**Recovery, in order of how little it needs to work:**

1. **Automatic, no action needed.** Disabling arms a rollback *before* it disables, in a
   detached root shell that init reparents:

   ```sh
   sleep 180; [ -f /data/local/tmp/carlauncher_keep_sole_home ] \
     || pm enable com.szchoiceway.customerui; \
     rm -f /data/local/tmp/carlauncher_keep_sole_home
   ```

   The launcher can be killed, ANR'd or uninstalled and the vendor launcher still comes back
   after 180 s. Tapping **Keep it disabled** within that window writes the keep-file and
   stands the rollback down.

   **The one gap:** a reboot or ACC power-off inside the 180 s kills that shell and the
   rollback never fires. Use step 2 or 3.

2. **Over adb**, with the unit powered and USB connected:

   ```sh
   adb shell pm enable com.szchoiceway.customerui
   ```

   This needs no working launcher UI and no Magisk prompt — `pm enable` runs as the shell
   user. This is the instruction the in-app dialog quotes, verbatim.

3. **On the unit, with no adb.** Any terminal app with root:

   ```sh
   su -c 'pm enable com.szchoiceway.customerui'
   ```

   If our launcher itself is what is broken, press HOME and pick another home from the
   chooser first — our HOME registration is one of several, not exclusive.

4. **Last resort.** The disable is per-user state, so a factory reset restores it; the APK
   was never removed (`disable-user`, not `uninstall`), so there is nothing to reinstall.

### Startup

`app/src/main/baseline-prof.txt` + `androidx.profileinstaller` give the cold-start path an
ART profile — the side-loaded install has no Play install step to deliver one. The profile is
hand-written rather than generated from a macrobenchmark run, because we have one head unit
and it is a car; the header of that file records the trade-off and what to do instead once a
device run is possible.

`.github/workflows/launcher-ci.yml` gains a `cold-start` job that installs the debug APK on
an API 33 emulator, applies the profile with `pm compile -m speed-profile`, and fails if the
median of five `am start -W` runs exceeds a budget. **That number is an emulator figure and
must never be quoted as a head-unit one** — it is a regression tripwire, set loose until real
runs exist to calibrate it.

## Known TODOs

- **`IEventService.aidl`** declares only a subset of methods and its transaction
  **ordinals almost certainly do not match** the real service — regenerate from the
  decompiled `IEventService.java` preserving method order before relying on any call.
- **`ICallbackfn.aidl`** signature is a placeholder; verify against the device.
- **Numeric speed** now comes from GPS (v2.5, above). The gateway still broadcasts none, so
  decoding the CAN bulk frame (`CAN_BASIC_EVT` / `MCU_CAR_CAN_INFO`) remains the upgrade worth
  making: it is available at power-on and indoors, where GPS is not.
- **Reverse camera feed** — `ReverseOverlay` is a black placeholder; embed a `SurfaceView`
  bound to the reverse video input, or host `com.szchoiceway.view.BackCarActivity`.
- **Climate / radio widgets** — placeholders only; wire `CarAirState` / `ZXW_RADIO_INFO_EVT`.
```
