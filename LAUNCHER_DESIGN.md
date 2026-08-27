# CarLauncher — UI/UX Design Spec

A custom Kotlin + Jetpack Compose home launcher for the Choiceway GT6-EAU head unit.
Design document only (no implementation code). Pairs with `CAR_API.md`, which is the
authoritative source for every event, service, and setting referenced here (cited as §N).

- **Panel:** 1920 × 720 px landscape, ~240 dpi → **~1280 × 480 dp** working canvas.
- **Input:** finger touch (gloved, moving vehicle) + steering-wheel keys (SWC).
- **OS:** Android 13, rooted.
- **Ship model:** COMPANION home first (coexists with vendor `com.szchoiceway.customerui`;
  user chooses the default via the Android home-resolver), with a documented path to FULL
  REPLACEMENT as a privileged/system app.

Throughout, **all sizes are in dp** on the ~1280 × 480 dp canvas unless a px value is stated.

---

## 1. In-car design principles

These are hard constraints, not aspirations. Every screen below is checked against them.

### 1.1 Glanceability (the 2-second rule)
- Any primary action must be readable and hittable in a **single glance ≤ 2 s**. Design each
  screen so the driver can locate a target with peripheral vision and confirm by shape/position,
  not by reading.
- **Type scale (minimums while driving):** primary values (speed, temp, clock, now-playing
  title) ≥ **40 sp**; tile labels ≥ **22 sp**; secondary/metadata ≥ **18 sp**. Never below 16 sp
  on the home surface. Weight ≥ Medium; avoid thin weights (poor contrast at a glance).
- **One concept per region.** The screen is divided into stable zones (see §2) that never
  reflow. Muscle memory beats reading — a control lives in the same pixel every drive.
- **Contrast:** target ≥ 7:1 for primary text against its card in both themes. No text over
  photographic album art without a scrim.

### 1.2 Thumb zones on a wide screen
A 1280 dp-wide panel is far wider than any reach arc. Treat reach as **two thumb fans**:
- **Driver-side reach zone (LHD car): right ~40% of the panel** (≈ 760–1280 dp) is the closest,
  most comfortable arc for the driver's right hand. **Put the most-used interactive controls
  here:** the quick-launch grid, climate quick-toggles, media transport.
- **Center (≈ 430–760 dp):** secondary reach — good for the nav/map tile and the "glance"
  content (large now-playing, map preview) that is *looked at* more than *touched*.
- **Far-left (≈ 0–430 dp):** hardest to reach while belted and driving. Reserve for
  **display-only / glance content** (clock, media art, status) and rarely-pressed affordances.
- Because the car may be RHD, the driver/passenger split is a **`Sys_CarType`-aware mirror
  option** (see §6, SysVar): a settings flag flips the media/quick-launch columns so the
  quick-launch grid always lands under the driver's thumb.
- **Touch target minimum: 76 × 76 dp** for any driving-relevant control (well above the 48 dp
  Material minimum — finger + vibration + glove). **Spacing ≥ 16 dp** between adjacent targets
  to prevent mis-taps. Corner-anchored controls get a larger 96 dp hit slop.

### 1.3 Day/night theme from the illumination broadcast
- Theme is driven by the car's headlight/illumination state, **not** by a clock or by ambient
  light sensor. Source of truth: the protected broadcasts
  `com.szchoiceway.ACTION_DAY_BACKLIGHT_CHAGNED` (→ day) and
  `com.szchoiceway.ACTION_NIGHT_BACKLIGHT_CHAGNED` (→ night), backed by SysVar
  `Sys_Day_Night_Mode` for the initial/authoritative value (CAR_API §1.3, §2.3, §6.3).
- **DayTheme:** high-luminance surfaces, deep-contrast text, saturated accent — readable under
  direct sun.
- **NightTheme:** near-black surfaces (`#0A0C10`-class), dimmed text luminance, **desaturated
  low-blue accents**; no large bright fills (a white card at night blinds the driver). Album art
  and map tiles get an automatic night dim scrim (~35% black).
- **Transition:** cross-fade over ~400 ms so a tunnel/underpass flicker doesn't strobe.
- Because the day/night broadcast is *protected* (needs `com.szchoiceway.permission.broadcast`),
  the companion build **must** have a fallback: poll/observe SysVar `Sys_Day_Night_Mode` via
  `ContentObserver` (reads are open) and offer a manual Day/Night/Auto toggle in Settings.

### 1.4 Low distraction
- **Motion budget:** no looping/attention-grabbing animation while `ACC on` and not parked.
  Transitions ≤ 400 ms, ease-out, and only on user action. No autoplay video, no carousels.
- **Modality is reserved for safety.** The only screen allowed to seize the foreground
  unprompted is the **Reverse overlay** (§3.6). Everything else is user-initiated.
- **No text entry while moving.** Search in the App Drawer and any keyboard field is disabled
  above a speed threshold (speed read per CAR_API §1.3 note: CAN bulk frame / GPS / AIDL — there
  is no clean speed extra). When locked out, show a large "Available when parked" state.
- **Confirmations are non-blocking.** Use brief inline toasts/snackbars anchored bottom-center,
  auto-dismissing, never a modal that blocks the road-facing content.
- **Haptics + optional MCU beep** (`IEventService.beep()`, CAR_API §3.2) on every successful
  touch, so the driver gets eyes-free confirmation.

---

## 2. Home layout — 1920 × 720 px (~1280 × 480 dp)

A fixed **three-column** layout that never reflows. Global 16 dp outer margin; 16 dp gutters.
Column widths sum to 1280 − (2×16 margin) − (2×16 gutter) = **1216 dp** of content.

```
┌───────────── 1280 dp ─────────────────────────────────────────────────────┐
│ [ Status strip — 40 dp tall, full width: clock · outside temp · ACC/day·night · signal ] │
├──────────────────┬───────────────────────────┬────────────────────────────┤
│  LEFT  (392 dp)  │      CENTER  (432 dp)      │       RIGHT  (392 dp)      │
│                  │                            │                            │
│  MediaCard       │     NavTile (map/nav)      │  QuickLaunchGrid (2×3)     │
│  now-playing     │     large glance surface   │  6 tiles, 76–120 dp each   │
│  art + title +   │     + "Navigation" CTA     │                            │
│  transport row   │                            │  ClimateReadout (below)    │
│                  │                            │                            │
│  (≈ 380 dp tall) │     (≈ 380 dp tall)        │  (grid ≈ 250 + climate 130)│
└──────────────────┴───────────────────────────┴────────────────────────────┘
   glance zone           glance + secondary          driver thumb zone
```

**Vertical:** Status strip 40 dp + 8 dp gap + content band **~392 dp** + 16 dp bottom margin
≈ 456 dp, leaving slack for the 480 dp canvas.

### 2.1 Status strip (top, full width, 40 dp tall)
Display-only. Left→right: **Clock** (40 sp, HH:MM), **outside temp** (from
`CAN_CAR_OUT_SIDE_TEMP_EVT`, CAR_API §1.3), center spacer, then right-aligned status glyphs:
day/night indicator, ACC state, BT/signal. Height deliberately small — it is glance data, not
touch.

### 2.2 LEFT column — MediaCard (392 dp wide, far-left = glance zone)
- **Album/source art** 120 × 120 dp, top-left, with night scrim.
- **Title** (≥ 28 sp, 2-line ellipsize) + **artist/subtitle** (20 sp) to the right of art.
- **Transport row** at the bottom: Prev · Play/Pause · Next, each a **88 × 88 dp** target
  (this row is interactive so it gets full targets despite being in the far column — kept large
  and bottom-anchored so it's the reachable part of an otherwise glance card).
- Tapping the art/title opens the full **Media** screen (§3.3).
- Data: `MediaSession`/`MediaController` for third-party apps **or** the car's
  `ZXW_MUSIC_PLAY_*_EVT` broadcasts / AIDL `getValidMode*Infor()` when the active source is the
  car's own player/radio (CAR_API §1.3, §3.2, §6.3).

### 2.3 CENTER column — NavTile (432 dp wide, widest, center glance)
- A large map/nav preview surface (embedded map if the nav app exposes one; otherwise a static
  branded tile with last-destination text).
- Bottom-anchored **"Navigation"** CTA button, 432 × 88 dp, launches the configured nav package
  (SysVar `Set_NavPackageName` / `Set_NavClassName`, CAR_API §6.3).
- Widest column because the map is the most-glanced surface and benefits from horizontal extent.

### 2.4 RIGHT column — QuickLaunch + Climate (392 dp wide, driver thumb zone)
This is the closest-reach column for a LHD driver, so it holds the densest **interactive** set.
- **QuickLaunchGrid:** 2 rows × 3 cols of app/function tiles. Each tile **120 × 116 dp**
  (icon 56 dp + label 22 sp), 16 dp gutters. Default tiles map to the car's own
  `DEFAULT_ICON_CONFIG` set (CAR_API §6.3): **Radio, Media, Climate, Phone, App Drawer,
  Settings**. User-reorderable; backed by SysVar `Sys_Function_Icon_Config_Key`.
- **ClimateReadout** (below the grid, 392 × 130 dp): **read-only** driver/passenger set-temp
  (40 sp), fan level, and mode glyphs. Tapping opens the Climate DISPLAY screen (§3.4). Data:
  `CarAirState` Parcelable from `com.szchoiceway.canbus.carairstruct` (CAR_API §1.3, §5).

### 2.5 Reachability mirror
When SysVar indicates RHD (or user preference), swap the LEFT (MediaCard) and RIGHT
(QuickLaunch+Climate) columns so interactive controls stay under the driver's hand. CENTER is
symmetric and never moves.

---

## 3. Screens & components

### 3.1 Home
As laid out in §2. Root destination; SWC HOME key always returns here (§4).

### 3.2 App Drawer
- Grid of installed launchable apps. Tiles **140 × 140 dp**, icon 72 dp + label 22 sp, 6 columns
  × scroll. Large vertical **snap-scroll** (page-at-a-time), not free fling, to reduce
  fine-motor demand while driving.
- **Search** field at top — **disabled while moving** (§1.4); shows "Search available when
  parked".
- Honors hidden-apps list SysVar `SYS_LAUNCHER_APP_HIDE_KEY` and customized-app slots
  `SET_CustomizedPackageName_KEY0..6` (CAR_API §2.3, §6.3).
- Long-press a tile → add to QuickLaunchGrid / hide.

### 3.3 Media (MediaSession)
- Full-screen now-playing: large art (240 dp), title 40 sp, artist 24 sp, scrubber (disabled
  while moving; display-only progress instead), transport row with 96 dp targets, source picker
  (BT / USB / car player).
- Primary integration is Android **`MediaSession` / `MediaController`** for third-party apps
  (Spotify, etc.). For the car's built-in player, mirror `ZXW_MUSIC_PLAY_*_EVT` and drive it via
  AIDL syskey (`ZXW_SYS_KEY_EVT` 6 = play/pause) (CAR_API §1.3, §1.4, §3.2).

### 3.4 Radio (EventService)
- Band selector (FM/AM), large frequency display (48 sp), preset row of 6 favorites, seek ◄◄ / ►►
  and scan.
- **All state and control via AIDL `IEventService`** (CAR_API §3.2): `getRadioFreq()`,
  `getRadioBand()`, `getRadioFreqList()`, `getRadioNum()`, RDS getters; control via
  `sendRadioKey(int)` and `sendUserFreq(int,boolean)`. Live updates via `ZXW_RADIO_INFO_EVT` /
  `com.szchoiceway.radio.frequency` broadcasts. Presets persist in SysVar `Rdo_MyFavorite0..5`.
- Register `ICommunication` (`addMessageListener`) for push updates.

### 3.5 Climate DISPLAY (read-only)
- **Read-only** mirror of the car's HVAC — the physical A/C panel remains the control. Shows:
  driver/passenger set temps (40 sp each), fan strength bars, mode glyphs (face/feet/defrost),
  A/C on, AUTO, dual, recirc, seat heat levels, rear-air.
- Data: `CarAirState` Parcelable (`bAcOn, bAutoOn2, bDualOn, byAirMode, byFunStrength,
  bLeftSeatHotLevel, byLeftColdLevel, bRearAirOn`, …) from `com.szchoiceway.canbus.carairstruct`,
  or AIDL `getAirData(int, byte[])` (CAR_API §1.3, §3.2, §5).
- Explicitly **no write path in v1** (writing needs raw A/C key opcodes / MCU passthrough that
  best belong to a system build — CAR_API §5). A "Controls" affordance is greyed with a tooltip
  until the system build enables it.

### 3.6 Reverse overlay (full-screen)
- Triggered by protected broadcast **`ACTION_BACKCAR_START`**; dismissed on `ACTION_BACKCAR_END`
  (CAR_API §1.3, §5). Highest-priority surface: takes foreground over any screen, over any app.
- Full-screen camera region (the actual video is the vendor decoder / `BackCarActivity` surface;
  our overlay draws **guide lines + radar** on top, or defers wholly to the vendor activity in
  companion mode — see §6).
- **Radar visualization:** arc/zone bars per sensor from `MCU_CAR_CAN_RADAR_INFO` (byte[],
  CAR_API §1.3), colored green→amber→red by proximity, with the beep already handled by MCU.
- **Dynamic trajectory** from steering angle `ZXW_CAN_WHEEL_TRACK_EVT` (CAR_API §1.4).
- Auto-exit at the configured speed threshold (SysVar `Sys_Backcar_speed_threshold`).
- Companion-mode caveat: `ACTION_BACKCAR_START` is *protected*; if we can't hold the permission,
  the vendor `BackCarActivity` still handles reverse and our overlay simply doesn't appear — no
  regression. Full radar overlay is a **system-build** feature.

### 3.7 Settings
- Large-tile settings: Theme (Day/Night/Auto), Reachability mirror (LHD/RHD), QuickLaunch
  editor, default-home helper (deep-link to Android home resolver), display brightness passthrough.
- Advanced (parked-only): SysVar inspector/editor (writes require root/system — §6), radio
  presets, reverse options (`Sys_Reverse_Assist_Line_Key`, `Sys_TrackLineType`,
  `Sys_BackCar_Display_Radar_Key`). Reads open; writes gated behind the privileged build or a
  root `content update` shim.

---

## 4. SWC key → focus/select navigation mapping

The whole UI is operable **without touch** via a single roving focus ring. A `FocusManager`
maintains an ordered focus graph per screen; SWC keys move and activate it.

**Sources (listen to all three, CAR_API §4):** primarily `STEER_WHEEL_INFOR`
(`LPARAM`=key index, `WPARAM` 3=down/4=up) — *protected*; plus `ACTION_HOST_MCU_BUTTON_KEY`
and `MCU_KEY_INFOR`; plus ordinary `onKeyDown` for injected media keys.

**Keycode map (`CAR_KEY_*`, CAR_API §4):**

| SWC key | Const | Home / general action |
|---|---|---|
| HOME (2) | `CAR_KEY_HOME` | Return to Home; second press → App Drawer |
| MENU (6) | `CAR_KEY_MENU` | Open contextual menu / Settings on Home |
| BACK (10) | `CAR_KEY_BACK` | Pop screen / dismiss overlay |
| PREV (4) | `CAR_KEY_PREV` | **Media:** previous track · **elsewhere:** focus ◄ |
| NEXT (5) | `CAR_KEY_NEXT` | **Media:** next track · **elsewhere:** focus ► |
| L_TUNE_L/R (11/12) | `CAR_KEY_L_TUNE_*` | Move focus ring ◄ / ► (rotary) |
| R_TUNE_L/R (13/14) | `CAR_KEY_R_TUNE_*` | Adjust focused value (volume, seek) / focus ▲▼ |
| MEDIA (8) | `CAR_KEY_MEDIA` | Jump to Media screen |
| RADIO (9) | `CAR_KEY_RADIO` | Jump to Radio screen |
| PHONE (7) | `CAR_KEY_PHONE` | Launch phone/BT |
| FAV (3) | `CAR_KEY_FAV` | Radio next preset / favorite toggle |
| POWER (1) | `CAR_KEY_POWER` | Handled by system (mute/screen) — not intercepted |

**Focus model:**
- **Focus order** is column-major matching the visual layout: Media transport → NavTile CTA →
  QuickLaunch tiles (row-major) → ClimateReadout → Status strip is skipped (display-only).
- Focused element gets a **6 dp accent ring + 8% fill + 1.03× scale**, high-contrast in both
  themes, and an eyes-free **beep** on focus change.
- **Debounce:** act on `WPARAM`=4 (release) for select; treat rapid repeats as held-repeat for
  tuning. Long-press (>600 ms) on select = secondary action.
- **Wrap-around** focus (last→first) so a driver can cycle without hunting for an edge.
- Focus state survives day/night theme swaps and reverse-overlay interruptions (restored on
  `ACTION_BACKCAR_END`).

---

## 5. Composable component inventory (`:app` module)

Naming: screens are `*Screen`, reusable pieces are nouns. Each lists responsibility and its
CAR_API-backed data source. State is exposed via `StateFlow` from a matching ViewModel/repository;
Composables are stateless and hoist events up.

### 5.1 App shell & theming
| Composable | Responsibility | Data source |
|---|---|---|
| `CarLauncherApp` | Root; hosts nav graph, applies theme, mounts global `ReverseHost` | — |
| `CarTheme` | Provides `CarColors`/`CarType` for Day/Night; animates cross-fade | `ACTION_DAY/NIGHT_BACKLIGHT_CHAGNED` + SysVar `Sys_Day_Night_Mode` (§1.3, CAR_API §1.3/§6.3) |
| `CarScaffold` | Fixed 3-column + status-strip skeleton; owns outer margins/gutters | layout |
| `FocusRing` | Draws focus decoration around the currently-focused node | `FocusManager` (SWC, §4) |
| `ReverseHost` | Top-level overlay slot that shows `ReverseScreen` when reverse engaged | `ACTION_BACKCAR_START/END` (CAR_API §1.3) |

### 5.2 Home & status
| Composable | Responsibility | Data source |
|---|---|---|
| `HomeScreen` | Composes the three columns + status strip | aggregate |
| `StatusStrip` | Clock, outside temp, ACC/day-night/signal glyphs | system time; `CAN_CAR_OUT_SIDE_TEMP_EVT`; `ACTION_ACC_OPEN_CLOSE_EVT` (CAR_API §1.3) |
| `ClockView` | HH:MM large glance clock | system time |
| `OutsideTempView` | Ambient temp readout | `CAN_CAR_OUT_SIDE_TEMP_EVT` (`..._EVT_EXTRA_STR`) |
| `MediaCard` | Left-column now-playing + transport | `MediaController` or `ZXW_MUSIC_PLAY_*_EVT` / AIDL `getValidMode*Infor` |
| `NavTile` | Center map/last-destination + Navigation CTA | SysVar `Set_NavPackageName/ClassName` (CAR_API §6.3) |
| `QuickLaunchGrid` | 2×3 reorderable function/app tiles | SysVar `Sys_Function_Icon_Config_Key`; `DEFAULT_ICON_CONFIG` |
| `QuickLaunchTile` | Single icon+label tile, 120×116 dp, focusable | PackageManager / function id |
| `ClimateReadout` | Compact read-only HVAC summary | `CarAirState` / AIDL `getAirData` (CAR_API §1.3/§3.2) |

### 5.3 Feature screens
| Composable | Responsibility | Data source |
|---|---|---|
| `AppDrawerScreen` | Paged app grid + parked-only search | PackageManager; SysVar `SYS_LAUNCHER_APP_HIDE_KEY` |
| `MediaScreen` | Full now-playing + source picker | `MediaController`; car AIDL/broadcasts |
| `TransportControls` | Prev/Play/Next/scrub (88–96 dp targets) | `MediaController.transportControls` / `ZXW_SYS_KEY_EVT` |
| `RadioScreen` | Band, freq, 6 presets, seek/scan | AIDL `getRadioFreq/Band/List/Num`, `sendRadioKey`, `sendUserFreq` (CAR_API §3.2) |
| `RadioPresetRow` | 6 favorite chips | SysVar `Rdo_MyFavorite0..5` |
| `ClimateScreen` | Full read-only HVAC display | `CarAirState` Parcelable (CAR_API §5) |
| `ReverseScreen` | Full-screen reverse: video slot + radar + trajectory | `MCU_CAR_CAN_RADAR_INFO`, `ZXW_CAN_WHEEL_TRACK_EVT`, `Sys_Backcar_*` |
| `RadarOverlay` | Per-sensor proximity arcs colored by distance | `MCU_CAR_CAN_RADAR_INFO` (byte[]) |
| `TrajectoryLines` | Dynamic steering trajectory + static guides | `ZXW_CAN_WHEEL_TRACK_EVT`; `Sys_TrackLineType` |
| `SettingsScreen` | Theme/mirror/quicklaunch/home-helper + advanced | SysVar reads; writes via root/system shim |
| `SysVarInspector` | Parked-only key/value editor | `SysVarProvider` query/update (CAR_API §2) |

### 5.4 Data/plumbing (not Composables, but the sources the above bind to)
| Class | Responsibility | CAR_API |
|---|---|---|
| `CarEventRepository` | Registers all `BroadcastReceiver`s, exposes `StateFlow`s | §1.3 |
| `EventServiceClient` | Binds AIDL `IEventService`, wraps radio/audio/climate/media getters + `ICommunication` listener | §3 |
| `SysVarRepository` | `ContentResolver` read + `ContentObserver`; write via root `content` shell or system uid | §2 |
| `SwcFocusManager` | Maps SWC broadcasts/keycodes → focus-graph moves & selects | §4 |
| `IlluminationController` | Day/night source of truth from backlight broadcasts + SysVar fallback | §1.3, §6.3 |
| `MediaSessionClient` | `MediaSessionManager`/`MediaController` for third-party apps | Android std |

---

## 6. Companion → full-replacement roadmap

The capability split follows CAR_API §6.4. Three tiers:

### 6.1 Works as a NORMAL app (companion build, no root)
- Register as HOME (`category.HOME/DEFAULT/LAUNCHER`) and coexist; user picks default in Android
  home resolver. No collision with `com.szchoiceway.customerui`.
- Show Home, App Drawer, QuickLaunch, Clock; launch apps and the configured nav package.
- Receive **unprotected** events: ACC (`ACTION_ACC_OPEN_CLOSE_EVT`), music now-playing
  (`ZXW_MUSIC_PLAY_*_EVT`), radio broadcasts, outside temp, `CarAirState` climate, radar bytes
  (these are sent unprotected per CAR_API §1.3).
- **Read** SysVar (open reads): theme mode, nav pkg, favorites, hidden apps, car type.
- **Bind `EventService`** and call read-only AIDL: radio freq/band, air data, media metadata.
- `MediaSession` transport for third-party media apps.

### 6.2 Needs the CHOICEWAY PERMISSION (or root/system if it's `signature`)
`com.szchoiceway.permission.broadcast` gates the *protected* broadcasts. Its protection level is
**[inferred signature]** (CAR_API §1.1) — a normal app may silently miss these:
- **Reverse overlay trigger** `ACTION_BACKCAR_START/END`.
- **SWC keys** via `STEER_WHEEL_INFOR` (fallback: unprotected `ACTION_HOST_MCU_BUTTON_KEY` /
  `MCU_KEY_INFOR` + injected `onKeyDown` still catch many keys — §4).
- **Day/night backlight** broadcasts (fallback: observe SysVar `Sys_Day_Night_Mode`).
- Mitigation in companion build: request the permission (harmless if granted); otherwise rely on
  the documented unprotected fallbacks so nothing hard-breaks.

### 6.3 Needs ROOT (rooted device — practical here)
- **Write SysVar** (QuickLaunch persistence, reverse options, radio presets, theme override):
  root `content update --uri content://…SysVarProvider/SysVar` shim (CAR_API §2.2).
- Suppress/replace the vendor status & nav bar (SysVar `Sys_Statusbar_Icon_Config_Key`,
  `SYS_SHOW_TOOL_NAVI_BAR_WND`).

### 6.4 Needs SYSTEM / SIGNATURE (full-replacement build)
Install to `/system/priv-app` + platform signature, `sharedUserId=android.uid.system` (CAR_API
§6.4). Unlocks:
- Reliably hold the `signature` broadcast permission → **native reverse overlay with our radar +
  trajectory**, reliable SWC + day/night.
- AIDL **control side-effects**: mode switch (`sendMode`), MCU passthrough, secure-settings, EQ,
  and thus a **write-enabled Climate** control screen (A/C key opcodes, CAR_API §5).
- Become the sole HOME (disable/replace `com.szchoiceway.customerui`), own the launcher↔gateway
  UIMODE handshake (`ACTION_LAUNCHER_TO_EVENTCENTER_UIMODE_EVENT`, CAR_API §6.2).

### 6.5 Migration property
The same `:app` codebase runs in all builds. A `PrivilegeLevel` capability object
(`NORMAL | ROOTED | SYSTEM`), resolved at startup, feature-flags the gated paths. UI degrades
gracefully: a feature that needs a higher tier shows a greyed control with a one-line reason,
never a crash or a dead button.

---

## 7. Phased plan (MVP → v1 → v2)

### MVP — companion, normal app, "it's a better home"
- `CarThe­me` day/night (via SysVar fallback), `CarScaffold` 3-column Home.
- `HomeScreen`: StatusStrip (clock + outside temp + ACC), `MediaCard` (MediaSession),
  `QuickLaunchGrid` (in-memory config), `NavTile` launching configured nav pkg.
- `AppDrawerScreen` (paged, parked-only search).
- `ClimateReadout` + `ClimateScreen` read-only from `CarAirState`.
- Basic SWC focus (`SwcFocusManager`) using unprotected key broadcasts + `onKeyDown`.
- Coexist as selectable HOME. **No root required.**

### v1 — the full companion experience
- `RadioScreen` via AIDL `EventService` (freq/band/presets/seek), `ICommunication` live updates.
- `MediaScreen` full player + source picker.
- Day/night from the real backlight broadcasts (request the Choiceway permission).
- Reverse: subscribe to `ACTION_BACKCAR_START/END`; show our `ReverseScreen` when permitted,
  else defer to vendor `BackCarActivity`. Radar/trajectory overlay best-effort.
- **Root shim** for SysVar writes → persistent QuickLaunch, presets, theme override,
  reachability mirror. `SettingsScreen` + basic `SysVarInspector`.
- Reachability mirror (LHD/RHD) wired to `Sys_CarType`.

### v2 — full replacement, system app
- Platform-sign + `/system/priv-app` install; hold the signature permission reliably.
- Native full-screen `ReverseScreen` with our `RadarOverlay` + `TrajectoryLines`, guide-line
  settings.
- **Write-enabled Climate** and audio/EQ control via AIDL side-effects.
- Own the launcher↔gateway UIMODE handshake; optionally become sole HOME and manage the
  status/nav bar.
- Polish: ambient-aware brightness passthrough, refined night palette, per-driver profiles.

---

## Summary

CarLauncher is a Kotlin + Jetpack Compose home for the 1920×720 (~1280×480 dp) Choiceway head
unit, built around glanceability (≥40 sp primary type, 2-second targets), a fixed non-reflowing
three-column Home (far-left MediaCard glance zone, wide center NavTile, driver-thumb-zone
QuickLaunch 2×3 grid + read-only ClimateReadout, plus a 40 dp status strip), and a full-screen
Reverse overlay that seizes the foreground on `ACTION_BACKCAR_START`; it themes day/night from the
illumination backlight broadcasts (with a SysVar fallback), drives an eyes-free 76 dp-target UI by
SWC keys through a `SwcFocusManager` roving focus ring, sources every widget from the documented
CAR_API (MediaSession/`ZXW_MUSIC_*` media, AIDL `EventService` radio, `CarAirState` climate,
`SysVarProvider` settings, `MCU_CAR_CAN_RADAR_INFO` radar), and ships first as a coexisting normal
companion app that degrades gracefully around the protected `com.szchoiceway.permission.broadcast`
and root-only SysVar writes, following an MVP→v1→v2 path to a platform-signed `/system/priv-app`
full replacement that unlocks native radar overlays and write-enabled climate/audio control.
