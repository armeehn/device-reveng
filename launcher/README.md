# Car Launcher (`com.ripostelabs.carlauncher`)

A custom **companion HOME launcher** for the Choiceway GT6-EAU head unit
(Android 13 / API 33, 1920x720 landscape @240dpi, **rooted**). It integrates with the
vendor gateway `com.szchoiceway.eventcenter` per [`../CAR_API.md`](../CAR_API.md).

> **Companion, not a replacement.** This app registers as an *alternative* HOME
> (`MAIN + HOME + DEFAULT + LAUNCHER`). The user chooses whether to make it the default
> home from the system chooser; the stock launcher (`com.szchoiceway.customerui`) stays
> installed. Privileged car actions (writing SysVar, reverse/SWC/day-night broadcasts) go
> through **root** — the privileged/system-app route is ruled out, since the vendor platform key
> is confirmed unobtainable. See *Root-native tier (v2.9)* below.

## Module structure

```
launcher/
├── settings.gradle.kts          # includes :app and :carlib; google/mavenCentral/JitPack repos
├── build.gradle.kts             # AGP 8.5.2, Kotlin 2.0.20 (+ compose compiler plugin), apply false
├── gradle.properties            # AndroidX, non-transitive R, parallel/caching
├── gradle/wrapper/…             # Gradle 8.9 wrapper
│
├── carlib/                      # Android library — the car integration layer
│   ├── build.gradle.kts         # namespace com.ripostelabs.carlauncher.carlib, aidl=true, libsu dep
│   ├── consumer-rules.pro       # keep vendor AIDL/Parcelable names
│   └── src/main/
│       ├── AndroidManifest.xml  # <uses-permission com.szchoiceway.permission.broadcast>
│       ├── aidl/com/szchoiceway/eventcenter/
│       │   ├── ICommunication.aidl   # gateway → app callback (notifyMessage/checkIsActive)
│       │   ├── ICallbackfn.aidl      # radio/EQ/CAN setter callback (notifyEvt / checkIsActive)
│       │   └── IEventService.aidl    # bound control service (SUBSET; ordinals = TODO)
│       └── java/com/ripostelabs/carlauncher/carlib/
│           ├── CarEvents.kt     # BroadcastReceiver → Flows/callbacks (reverse, ACC, SWC, day/night)
│           ├── SysVar.kt        # ContentResolver read + root `content` write of SysVarProvider
│           ├── RootShell.kt     # `su -c` via libsu (reflective) or ProcessBuilder fallback
│           └── CarService.kt    # binds vendor EventService via IEventService AIDL
│
└── app/                         # Android application — the launcher UI
    ├── build.gradle.kts         # applicationId com.ripostelabs.carlauncher, compose, targetSdk 33
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml  # HOME activity, singleTask, landscape, QUERY_ALL_PACKAGES
        ├── res/…                # dark car theme, strings, adaptive launcher icon
        └── java/com/ripostelabs/carlauncher/
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

Requires **JDK 17** and an **Android SDK** with platform 34 and build-tools. Point
`JAVA_HOME` and `ANDROID_HOME` at them (Android Studio's bundled copies work), then:

```bash
cd launcher
./gradlew :app:assembleDebug
```

The Gradle wrapper pins Gradle 8.9, so no system Gradle is needed. The version is
derived from git history, so build from a **full clone** — a shallow clone fails the
build with an explicit message rather than producing a wrong versionCode.

Output APK: `app/build/outputs/apk/debug/app-debug.apk`.

> `carlib` pulls `com.github.topjohnwu.libsu:core` from JitPack. If the network blocks
> JitPack, `RootShell` still works via its pure-`ProcessBuilder` `su -c` fallback — you can
> drop the libsu line from `carlib/build.gradle.kts` in that case.

## Deploy

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# then press HOME and pick "Car Launcher" (set as default if you want it to be the home)
```

There is no privileged/system-app install to graduate to. It would need the vendor's platform
key (`sharedUserId=android.uid.system` must match the running framework's signature), and that key
is **confirmed unobtainable** — `../CUSTOM_ANDROID.md` §2b, `../LAUNCHER_DESIGN.md` §6.4. The
`signature`-level vendor broadcasts and the SysVar writes come from **root** instead; see
*Root-native tier (v2.9)*.

### Which APK is in the car

Every green build of `main` is tagged `v<versionName>+<versionCode>.g<short-sha>` (e.g.
`v0.7+146.g91dd836`) and the release APK from that same CI run is published as a
GitHub Release against the tag, with the APK's SHA-256 in the release body.

Both version fields are derived from git at build time (`app/build.gradle.kts`):
`versionCode` is the commit count at the merge-base with `origin/main`, so every merge
to `main` raises it by exactly one, and `versionName` is the bare base (`0.7`). Nobody
bumps a version by hand, and no PR touches a version line. Because every build on a
line shares its versionName, a build is always named with the code beside it —
`0.7 (146)` in the app, `+146` in the tag — and the commit SHA stays in the tag as the
belt-and-braces identity. Historical note: before versionCode 71 the fields
were hand-claimed per PR; they repeat in that range (two merges both shipped `61`), so
only the SHA separates those builds.

Side-load only that asset. To identify what is already installed, pull the APK off the
head unit and match its `sha256sum` against the release body.

## Capability notes (see CAR_API.md §6.4)

| Capability | As normal app | Notes |
|---|---|---|
| Be HOME, app drawer, launch apps | ✅ | standard |
| Unprotected events (ACC, media, radio) | ✅ | |
| Protected events (reverse `ACTION_BACKCAR_*`, SWC `STEER_WHEEL_INFOR`, day/night) | ⚠️ only if granted `com.szchoiceway.permission.broadcast` | likely `signature`, and the platform key is unobtainable → captured at uid 0 by the root tier below; without root `CarEvents` falls back to unprotected `MCU_MSG_BACKCAR_*` |
| Read SysVar provider | ✅ | `SysVar.getString/readAll` |
| Write SysVar provider | ❌ direct | `SysVar.putString` routes through **root** (`content` shell) |
| Bind `EventService`, read-only AIDL | ✅ | exported service |
| AIDL control side-effects | ⚠️ | not recovered by root either; see *Root-native tier* |

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
adb shell pm grant com.ripostelabs.carlauncher android.permission.ACCESS_FINE_LOCATION
```

### Motion budget

Nothing animates on a loop, and no transition exceeds **400 ms** (today's longest is the 320 ms
onboarding slide). A moving car is when an animation is most distracting and least noticed, so
new work stays inside that budget: no `rememberInfiniteTransition`, no `basicMarquee`, and no
`tween` above 400 ms on a surface that can be on screen while driving.

`ui/CarFeedback.kt` gives eyes-free confirmation on accepted input — haptics plus the car's own
`beep()` — wired to steering-wheel presses, keyboard keys and dialog buttons. The beep follows
the vendor's `Set_TouchBeep` preference instead of adding a competing one.

## Media & Radio screens (v2.6)

The Home cards were always glance surfaces in a 30 %-wide column. `ui/MediaScreen.kt` and
`ui/RadioScreen.kt` are the full screens you land on to actually operate playback and the
tuner (LAUNCHER_DESIGN §3.3 / §3.4). Reached three ways: tapping a card body, CENTER on a
focused card, or the steering wheel's MEDIA / RADIO keys.

**MediaScreen** — large art, 40 sp title, 96 dp transport targets, and a source picker over
every live `MediaSession`. Scrubbing is parked-only (v2.5 gate): dragging to a target position
is a sustained, eyes-on gesture, so while moving the same progress renders as a read-only bar.
Transport stays available while moving — skip and play/pause are single forgiving presses that
exist on the wheel anyway, and withholding them would push the driver to their phone.

**RadioScreen** — 48 sp frequency, an AM / FM band toggle, a tune slider across the dial
(parked-only, like media scrubbing; it snaps to the 100 kHz / 10 kHz step on release), seek,
six preset slots with save / recall / delete, and the tuner's status flags. Opening the screen
claims the tuner as the cabin's audio source the way the vendor radio app does
(`CarService.claimRadio`); AM / FM send the MCU's direct band keys (30 / 31) and re-poll until
the tuner reports the class. The `sendRadioKey` value table is in CAR_API §3.2.

### What the firmware does not have

Two things §3.3/§3.4 planned turned out not to exist, so they are not built:

- **No NET.** The vendor radio app's AM / FM / NET row had a third tab for its internet-radio
  source. `getRadioBand()` never reports it and no `IEventService` method selects it, so the
  band toggle is AM / FM only.
- **Station name yes, radio text no.** `getRadioPTYName` (ordinal 19) is misnamed: the gateway
  stores the MCU's RDS PS frame in `mRadioPSName` and returns it there, so the screen shows the
  station name. The genre is `getRadioPTYNum` (index into a 32-entry PTY table). There is no RT
  getter. `ZXW_RADIO_INFO_EVT` is sent by the gateway on band/frequency change with int extras
  `RadioBndNum`, `RadioTuneNum`, `RadioCurFreq` — nothing the getters do not already say.
- **Scan is a key.** `getRadioAMSState` / `getRadioAPSState` only report; `sendRadioKey(13)`
  starts a preset scan and 18 an auto-store. Neither is wired to a control yet.

Preset sync with the vendor's `Rdo_MyFavorite0..5` is **read-only** for the same reason: the
encoding of those SysVar values is documented nowhere, and writing a guessed format would
corrupt the vendor radio's presets irreversibly. RadioScreen displays the raw strings, which is
exactly the capture needed to work the format out on-device — after which two-way sync is safe.

Vendor *source* switching (Bluetooth / USB / built-in) is likewise read-only: `sendMode(int,
boolean)` is ordinal 1 and confirmed, but its value table is not in the decompile, so
MediaScreen names the current source via `getValidModeTitleInfor()` and does not offer to
change it.

Both screens poll rather than subscribe. `setRadioCallback` exists, but the `ICallbackfn`
signature was never recovered, so registering it would be a guess. Blocking AIDL reads stay off
the composition body — doing them inline once spun a main-thread IPC recomposition loop while
seeking.

## SWC completeness & radar truth (v2.8)

### The whole app is drivable from the wheel

v0.8 gave Home a roving focus ring, and `MainActivity.routeNav` handed directional keys to it only
while Home was on screen — every other screen ignored the wheel. Two pieces close that:

- **`input/KeyPump.kt`** — one press-timing model for both input sources. The vendor
  `STEER_WHEEL_INFOR` broadcast sends a down and an up and nothing between, so a held key moved the
  ring once; real `KeyEvent`s auto-repeat at the platform's keyboard rate, which machine-guns a
  settings list. Both now feed the pump: directional keys fire, then repeat after 400 ms every
  150 ms; CENTER and BACK defer their action to the release and fire a **secondary action at
  600 ms** instead. A key cannot do both — a repeat has already fired by the time a long-press
  would.
- **`input/KeyBridge.kt`** — off Home, the ring is Compose's own focus system. Every `clickable` is
  already a focus target with real geometry, so a hand-written focus model per screen would only
  drift out of sync with the layout above it. The one thing missing was a way in: SWC keys arrive
  as a *broadcast*, never as a `KeyEvent`, so they never reached the composition. The bridge
  synthesises the KeyEvent through `Window.superDispatchKeyEvent` (which bypasses our own
  dispatcher, so a synthetic key cannot loop). **Wrap-around** has no framework API, so it is
  composed from the one that does: a refused move means an edge, and walking as far as possible in
  the opposite direction lands on the far end.

`input/FocusRing.kt` draws where focus landed — Material's focus ripple is invisible at arm's
length in daylight. It is applied to the shared settings kit (`SettingRow`, `ActionRow`,
`SettingsCategoryCard`, `SettingsIconTile`, `DialogTextButton`), which covers the whole settings
suite, plus the Media, Radio and Themes screens.

Long-press actions: **BACK** is Home from any depth, including four screens into settings.
**CENTER** on a Home grid tile toggles the favourite — the same thing a touch long-press does, so
the wheel gains no gesture the screen lacks. On the media card it is play/pause.

**Reverse restores focus.** Reverse is an interruption, not navigation: the vendor takes the screen
and gives it back. `LauncherFocus.saveForInterruption()` / `restoreAfterInterruption()` put the ring
back where the driver left it.

### Radar: the layout is still UNCONFIRMED

**Nothing about the radar decode was verified on a car.** There is no head unit attached to the
machine this was built on. `RadarState.fromRadarData` still uses the same guessed offsets it has
used since v0.7, and this release does not claim otherwise — it ships the instrument that settles
them.

**Settings ▸ Parking radar ▸ Raw frame capture** (`ui/settings/RadarCaptureScreen.kt`) shows the
`CAR_CAN_DATA` payload byte by byte in hex, with per-offset `min`/`max`/`change-count` accumulated
since the last reset, next to what our decode currently makes of the same frame. `CarEvents.radarRaw`
publishes the payload *before* interpretation, including frames our guess rejects — the guess is
what the screen exists to disprove.

**The capture to perform:**

1. Park. Engage reverse so the MCU starts broadcasting, and open the capture screen.
2. Press **Reset baseline** with nothing near the car.
3. Walk an obstacle slowly toward **one** corner. Watch which offset sweeps: a distance byte climbs
   or falls smoothly, a status flag jumps once and stops. The eye cannot follow eight hex values at
   10 Hz, which is what `min`/`max`/`changes` are for — a byte that never moved stays dimmed.
4. Repeat per corner. The offsets that moved, in the order they moved, *are* the layout.
5. If the number **falls** as the obstacle nears, the MCU sends distance rather than a bar count and
   `RadarState.proximity` needs inverting.
6. **Write table to logcat** dumps the result under tag `RadarCapture` for `adb logcat -s RadarCapture`.

Then set **Layout confirmed on this car**. Until that is set, the new low-speed maneuvering
side-strips (`ui/RadarSideStrip.kt`) stay hidden: an arc reading "clear" off a guessed offset is a
safety claim the code has not earned. The existing bars in Settings are *not* gated — they report
what arrived, not what it means — and every screen that shows a decode says UNCONFIRMED in words.

The strips themselves are two 44 dp rails hard against the screen edges, green → amber → red, three
arcs per corner, and completely static (motion budget). They are **hidden whenever reverse is
engaged**: `ReverseOverlay`'s v0.9 rule is that the vendor composites its own reverse window above
all apps and we never contend for those pixels. The strips cover the case the vendor window does
not — creeping forward in a car park, easing out of a garage — at or below 15 km/h. An unknown speed
passes the gate on purpose, since that underground car park is where GPS has no fix.

### Reachability mirror (LHD/RHD)

Settings ▸ Launcher ▸ **Reachability mirror** swaps the glance column (media + climate) and the
thumb column (quick launch + radio) so the interactive set stays under the driver's hand
(LAUNCHER_DESIGN §2.5). The focus ring mirrors with it, so a LEFT press still walks toward whatever
is now on the left. The centre column is symmetric and never moves.

**Auto cannot infer this on this platform, and says so.** `Sys_CarType` is the model index
within `Sys_Vehicle_deries` (RAV4 = 2 under Toyota = 1, CAR_API §2.3), the same on every market,
and the steering side is a CAN-box car setting that canbus2 keeps in an in-process map for its
console page — no SysVar, no broadcast. So `data/Reachability.kt` ships an **empty** RHD table
that must stay empty, Auto always resolves to LHD, and the settings screen prints the raw
`Sys_CarType` next to the override so a user can confirm the vehicle profile.

## The cockpit release (v3.0)

**Vehicle dashboard** (`ui/DashboardScreen.kt`, status bar ▸ speedometer icon). The launcher had
accumulated vehicle signals across five surfaces and nowhere to see them together. Speed, outside
temperature, steering, ignition, parking radar and a session timer, on one screen.

Every tile carries a provenance line, because these signals are *not* equally trustworthy and a
dashboard that renders a guess identically to a confirmed reading is worse than no dashboard:

| Tile | Source | Status |
|---|---|---|
| Speed | `CarEvents.speedKmh` (GPS, v2.5) | derived, not from the car |
| Outside temp | `CAN_CAR_OUT_SIDE_TEMP_EVT` | action **confirmed**; extra key names GUESSED |
| Steering | `ZXW_CAN_WHEEL_TRACK_EVT` | action **confirmed**; units and sign GUESSED |
| Ignition | `ACTION_ACC_OPEN_CLOSE_EVT` | **confirmed** |
| Radar | `MCU_CAR_CAN_RADAR_INFO` | byte layout GUESSED (v2.8) |
| Session timer | measured here | not the car's trip computer |

The steering tile shows **no number**. The extra's units and sign are undocumented, so printing
"42°" would invent precision we do not have; a bar that leans the way the wheel leans is exactly
as much as the signal supports. `CAN_CAR_TIRP_INFO` exists in the constant table but no extras
were recovered for it, so the real trip computer is not readable — hence "this session".

**Driver profiles** (`data/DriverProfilesStore.kt`, status bar ▸ account icon — two taps from
Home, as §3.0 asks). A profile bundles theme, favourites, quick-launch order and reachability.
Applying one writes *through* to the stores that already own each setting rather than becoming a
second source of truth, so a profile can never disagree with the live value. Saving is
parked-only (it needs the keyboard); applying is not — being unable to restore your own layout
while moving would be worse than the single tap.

**Gateway UIMODE handshake** (`carlib/GatewayHandshake.kt`). Announces our UI mode on
`ACTION_LAUNCHER_TO_EVENTCENTER_UIMODE_EVENT` (CAR_API §6.2), which is what makes the vendor
stack treat us as *the* launcher. It deliberately does **not** drive our theming:
`CUSTOMERUI_NOTES.md` records that the vendor launcher themes from `setUiModeNight/Day` plus a
local broadcast, *not* from this pair, so reading it as a day/night source would be reading a
channel the vendor does not actually theme from.

### HVAC write controls — sender only, no UI

The roadmap gated these on STATUS goal #3 proving RAV4 climate is CAN-writable. That test has not
been run, and STATUS still reads *"Investigating whether RAV4 climate is CAN-controllable or
display-only (likely display-only)."* carlib now carries `ClimateControl`, which sends the CAN
app's own `CAR_AIR_KEY_KEY` button broadcast (`CanUtils.CAR_AIR_KEY_*` values, decompile
`CarAirClickWithVoice.java:432,462`), but nothing in the UI calls it and it is unverified on the
car. The Climate settings screen continues to write only vendor *configuration* SysVars (panel
type, bus baud, seat heat, units) — never a live temperature or fan command.

### Stability bar — not claimable

§3.0 asks for a week of daily driving with zero crashes before the release is called done. None of
this has run on the head unit at all. Treat v3.0 as feature-complete and **unvalidated**.

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

### Vendor nav bar

Settings ▸ Root tier ▸ **Hide vendor nav bar** sets the SysVar `Sys_Customer_NaviBar_Height_Key`
to 0 (the factory page's own "No bottom bar" option) — the *persistent* config the gateway reads
for itself at boot and re-applies live on `changeSetup`, so it survives a reboot. The gateway
honours the key only with `Sys_Landscape` = 1, and the toggle says so when that is not the case.

The two earlier keys were wrong: `SYS_SHOW_TOOL_NAVI_BAR_WND` is a system property the gateway
writes as a mirror for skins 108/126/127 and nobody reads, and `Sys_Statusbar_Icon_Config_Key`
has no reader in any vendor package. **No SysVar hides the status bar**; the gateway forces it on
in every branch. The safeties stay: the toggle only writes a key that **already exists** in the
live table, records the original height before the first hide, and writes it back verbatim off.

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

`.gitea/workflows/launcher-ci.yml` gains a `cold-start` job that installs the debug APK on
an API 33 emulator, applies the profile with `pm compile -m speed-profile`, and fails if the
median of five `am start -W` runs exceeds a budget. **That number is an emulator figure and
must never be quoted as a head-unit one** — it is a regression tripwire, set loose until real
runs exist to calibrate it.

## Content & comfort (v2.7)

### Jellyfin ("jellybelly")

The Jellyfin Android client is just another `MediaSession`, so the media stack already surfaced it
with no work — the MediaCard source chip names it and the transport controls drive it. v2.7 adds
the two things that were missing: a **quick-launch preset** (`media/JellyfinApp.pinFirst` moves the
tile to the front of the driver's thumb column when a client is installed — ordering only, nothing
new is added to the drawer) and a **parked-only "continue watching" shelf**.

**What the shelf is, precisely.** It lists what *this head unit* has played, recovered from the
Jellyfin app's MediaSession metadata and persisted in `data/WatchHistoryStore`. It is **not** the
server's Continue Watching row. That row is `GET /Users/{id}/Items/Resume` behind authentication,
and reaching it would mean this APK carrying a tailnet URL and a credential that belong to the
owner, not to a launcher. **No API client was written and none should be.** The screen says so in
its own subtitle, because a shelf that implied it had talked to the server would make the first
missing episode read as a bug rather than a boundary.

Consequences, stated once so nobody re-discovers them:

- Anything watched on a phone or a TV never touched this device's media stack, so it is not here.
- Tapping a row opens Jellyfin at its home screen. Resuming a specific item needs the server item
  GUID, which needs credentials — there is no deep link to build.
- The position/duration pair is whatever the session last published. A player that stops updating
  on pause leaves the row slightly stale. It is a hint, not a bookmark. Rows whose session
  published no duration say so rather than drawing a bar at zero.
- Only Jellyfin sessions are recorded. The media stack sees every player on the unit, and a
  "continue watching" shelf full of radio adverts would be worse than an empty one.

The candidate packages (`org.jellyfin.mobile`, `org.jellyfin.androidtv`) are upstream application
IDs; which one — if either — is installed on this unit is **GUESSED** and resolved at runtime.

### Notification shelf

`notif/ShelfListenerService` is a third `NotificationListenerService` (the media one is empty by
design, the nav one reads only Maps). It keeps at most 40 recent notifications **in memory only**,
never on disk, and drops what does not belong on a shelf: our own, ongoing ones (media transport
and Maps' turn-by-turn already have cards; sync bars would pin themselves to the top forever),
group summaries, and anything with neither title nor text.

Per-app filtering is a **deny** list (`data/NotificationFilterStore`) applied at render time, so
un-muting an app brings its already-captured notifications straight back. An allow list would make
a newly installed app silently invisible, which is the failure mode where nobody notices the
feature is dropping things.

No notification *actions*, no reply. Those are PendingIntents that can do anything, and firing
arbitrary app intents off a launcher shelf is not something to do with someone sitting in a car.
A row does two things: open the source app, or dismiss.

Parked-only, and the listener needs the same grant as the other two:

```sh
adb shell cmd notification allow_listener com.ripostelabs.carlauncher/com.ripostelabs.carlauncher.notif.ShelfListenerService
```

### The keyboard, everywhere

`ui/keyboard/CarKeyboard.kt` is the v2.3 search QWERTY extracted and given a shift latch
(off → one-shot → locked) and a symbol layer. `ui/keyboard/CarTextField.kt` wraps it: the field on
the page is a display-only tile, and tapping it opens a full-screen editor that owns the keyboard.

Nothing in the app is a focusable text field any more, so **the vendor IME has no way to appear** —
which was the point. It ignores night mode, cannot be themed, and eats half of a 720px screen.
Every text field now themes with `CarTheme`: app search, the theme name and hex fields, the SysVar
browser's search box and its raw value editor.

What that costs, honestly: no caret placement, no selection, no clipboard, no autocomplete. Editing
is append-and-backspace. The symbol layer is curated (`!@#$%^&*()-_=+[]{}|\,.:;'"?/`), so angle
brackets, tilde and backtick have to be pushed in over adb.

Motion gating comes free: `CarTextField` is inert while the parked-only lock holds and an open
editor abandons the edit if the car pulls away. Every text field in the app is gated by
construction rather than by remembering to wrap each one.

### Theme quality of life

**Import / export** (`data/ThemeTransfer`) writes a theme as JSON — the same codec `ThemeStore`
persists with, so a file and a stored theme cannot drift — into the app's external files directory:

```sh
adb pull /sdcard/Android/data/com.ripostelabs.carlauncher/files/themes/
adb push mytheme.json /sdcard/Android/data/com.ripostelabs.carlauncher/files/themes/
```

That path needs no runtime permission on API 33. `Downloads` was rejected (scoped storage makes a
plain `File` write there need MediaStore or `MANAGE_EXTERNAL_STORAGE` — a lot of permission surface
for a colour file) and so was `ACTION_CREATE_DOCUMENT` (another un-theme-able system screen, which
is the problem this release exists to reduce). Built-ins export too: pull a preset, edit the hex on
a real keyboard, push it back. An import always lands as a **new** user theme with a fresh id, so a
hand-edited file cannot shadow a preset or overwrite something already on the unit.

**Clock day/night.** `CarEvents.illuminationSeen` (new) latches true the first time an
`ACTION_DAY/NIGHT_BACKLIGHT_CHANGED` broadcast actually arrives. `dayNight` alone could never tell
"the car said day" from "the car said nothing" — and on a normal install it says nothing, because
those broadcasts ride `com.szchoiceway.permission.broadcast` (very likely `signature`, CAR_API
§1.1). So the unit sits in day colours at midnight.

Settings ▸ Launcher ▸ **Clock day / night** offers two hours (default 19:00 → 07:00) used by a new
`DayNightMode.CLOCK`, plus an opt-in fallback that applies those hours *in Auto* only while
`illuminationSeen` is false. **Off by default on purpose:** switching it on for every existing
install would start dimming screens at dusk without being asked. The section states on screen
whether the car has actually reported illumination this session, for the same
diagnosis-over-guesswork reason as the v2.5 motion status row.

No solar calculation. Real civil twilight needs a date and a position, and this launcher's position
comes from the same GPS the motion gate has to fail open around — no fix in a garage, none at
power-on, none at all without the location grant. A window the driver sets is honest about being an
approximation; a sunset calculation would be wrong exactly when it matters.

### Not built, and why

- **A real Jellyfin "continue watching" feed.** Needs the server URL and a credential. Out of
  scope by instruction and by judgement; see above.
- **Per-item resume / deep links into Jellyfin.** Same reason — item GUIDs come from the API.
- **Notification actions and inline reply.** Deliberate, see above.
- **Anything requiring the head unit.** None of v2.7 could be run on the physical device from
  here. What is genuinely unverified: whether a Jellyfin client is installed at all and under
  which package; whether `cmd notification allow_listener` grants the third listener as reliably
  as the first two; how much of the shelf survives the vendor's own notification handling; and
  whether the extracted keyboard's key sizes still read well on the real 240dpi panel.

## Known TODOs

- **`IEventService.aidl`** declares only a subset of methods and its transaction
  **ordinals almost certainly do not match** the real service — regenerate from the
  decompiled `IEventService.java` preserving method order before relying on any call.
- **`ICallbackfn.aidl`** signature is a placeholder; verify against the device.
- **No volume event.** The status-bar volume chip polls because nothing pushes: none of the seven
  `ICallbackfn` registrars is audio-scoped, the `EventUtils` broadcast table has no volume action,
  and `SYSTEM_VOLUME` is a LocalSocket text-protocol marker. Capturing that socket, or the real
  `ICallbackfn` signature, is what would replace the poll. See `ui/StatusIndicators.kt`.
- **Numeric speed** now comes from GPS (v2.5, above). The gateway still broadcasts none, so
  decoding the CAN bulk frame (`CAN_BASIC_EVT` / `MCU_CAR_CAN_INFO`) remains the upgrade worth
  making: it is available at power-on and indoors, where GPS is not.
- **Reverse camera feed** — `ReverseOverlay` is a black placeholder; embed a `SurfaceView`
  bound to the reverse video input, or host `com.szchoiceway.view.BackCarActivity`.
- **Climate widget** — reads the mirrored `CarAirState` parcel; unverified on the car.
- **`Rdo_MyFavorite0..5` write-back** — the encoding is known (`freq | (am ? 0x10000 : 0)`,
  `RadioTuning.encodeVendorFavorite`) and the slots are recallable from RadioScreen; writing our
  presets into them is a product decision, not a research item any more.
- **Radar byte layout** — still **UNCONFIRMED** (v2.8 above). Run the capture, then either fix the
  offsets in `RadarState` or set the layout-confirmed flag. Until then the maneuvering side-strips
  never draw.
- **Steering side** — not recoverable from SysVar (`Sys_CarType` is a model index). The
  reachability mirror's Auto mode stays LHD; only a CAN-box console read could ever feed it.
- **Vendor `sendMode` value table** — needed before MediaScreen can switch the car between
  Bluetooth / USB / the built-in player.
- **`CAN_CAR_OUT_SIDE_TEMP_EVT` / `ZXW_CAN_WHEEL_TRACK_EVT` extra keys** — the actions are
  confirmed but the extra *names* were never quoted in the decompile, so v3.0 tries a short list
  of candidates. Dump the real extras on-device and pin them.
- **Steering angle units** — turn lock to lock with the dashboard open and read the extremes off
  the raw value; the indicator currently assumes ±540 and saturates.
- **`CAN_CAR_TIRP_INFO` extras** — would replace the dashboard's own session timer with the car's
  real trip computer.
- **Profile renaming** — captured profiles get a generated name until v2.7's in-app keyboard
  (`CarTextField`) is on this branch.
```
