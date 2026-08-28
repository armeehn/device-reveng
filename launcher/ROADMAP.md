# CarLauncher roadmap — v2.4.1 → v3.0

_Planned 2026-08-28. Current main: **v2.4.1 / versionCode 33** — the superset build (3-column
home, drawer + rofi search, favorites, quick controls, full reskinned settings suite v2.0 with
455-key SysVar browser, rice + Riposte themes + theme editor, SWC focus ring on Home, CarPlay
tile, onboarding)._

Workflow unchanged: **one feature = one branch off `main` = one PR**, versionCode claimed at PR
time (≥ 34; check open PRs and sibling worktrees first, per `~/CLAUDE.md`). Only `main` builds
go on-device as the home launcher. CI (PR #14) builds every PR's release + debug APKs.

## Ground rules that shape this plan

- **Reverse video is permanently vendor-owned.** The vendor composites its own reverse window
  above all apps; we cannot host or draw over the feed (see `ReverseOverlay.kt` v0.9
  rationale). Anything "reverse camera" beyond coexistence + settings is out of scope forever.
- **The platform signature tier is unreachable** — the Choiceway platform private key is
  confirmed unobtainable. The design doc's "v2 system app" tier is therefore replaced by a
  **root-native tier** (Magisk root + permissive SELinux gets us most of CAR_API §6.4).
- **HVAC writes are unproven** — RAV4 climate may be display-only on CAN (STATUS goal #3).
  Climate control ships only if the live-test proves writability; otherwise stays read-only.
- **Radar byte layout is guessed** — needs on-device confirmation before radar features grow.

---

## v2.5 — Motion awareness (safety foundation)

The design doc's §1.4 rules ("no text entry while moving") are currently unenforceable because
`CarEvents.speedKmh` is a stubbed `-1`.

- **Real speed source.** First pass: GPS speed via `LocationManager` (works as a normal app),
  smoothed, exposed through the existing `speedKmh` flow. Stretch: decode the CAN bulk frame
  and prefer it when present.
- **Parked-only gating, enforced:** drawer search / keyboard, theme editor, advanced SysVar
  browser, and destructive settings actions show a large "Available when parked" state while
  moving.
- **Eyes-free confirmation:** `IEventService.beep()` + haptics on successful touch, per §1.4.
- **Motion-budget audit:** no looping animation while driving; transitions ≤ 400 ms.

## v2.6 — Media & Radio full screens

The cards exist; the §3.3/§3.4 full screens don't.

- **MediaScreen:** large art, 40 sp title, source picker (Bluetooth / USB / car player /
  third-party MediaSessions), display-only progress while moving, 96 dp transport targets.
- **RadioScreen:** band selector, 48 sp frequency, seek/scan, RDS text, 6-preset row with
  save / recall / delete over the confirmed AIDL ordinals; presets sync with
  `RadioPresetsStore` and `Rdo_MyFavorite0..5`.
- Cards deep-link into their screens; SWC MEDIA/RADIO keys jump directly.

## v2.7 — Content & comfort

- **Jellyfin (jellybelly) integration:** surface the tailnet Jellyfin session in the media
  stack (it's just another MediaSession), a quick-launch preset, and a parked-only
  "continue watching" shelf.
- **Notification shelf (parked-only):** we already hold notification-listener access for
  media/nav — add a car-friendly, glanceable notification list with per-app filtering.
- **In-app Compose keyboard everywhere:** the vendor IME ignores night mode and can't be
  themed; extend the SearchOverlay keyboard to every text field in the app (settings, theme
  editor, SysVar browser).
- **Theme quality-of-life:** import/export custom themes as JSON, plus an optional
  clock-based day/night fallback for when the illumination signal is absent.

## v2.8 — SWC completeness & radar truth

- **Roving focus ring on every screen** — settings suite, media, radio, themes, drawer —
  with wrap-around, held-repeat tuning, long-press (> 600 ms) secondary actions, and focus
  restore after a reverse interruption (§4).
- **Radar, verified:** confirm the `MCU_CAR_CAN_RADAR_INFO` byte layout on-device, then a
  low-speed maneuvering side-strip (proximity arcs, green→amber→red) that coexists with the
  vendor reverse window instead of fighting it.
- **Reachability mirror (LHD/RHD)** wired to `Sys_CarType` with a manual override (§2.5).

## v2.9 — Root-native tier ("system-lite")

Everything the design doc reserved for the unreachable signature tier that root can deliver:

- **Protected-broadcast capture** via a root helper (system-uid `app_process` shim or logcat
  stream): reliable `STEER_WHEEL_INFOR` SWC keys and day/night backlight events instead of
  the unprotected fallbacks.
- **Persistent root write daemon:** one long-lived `su` channel for SysVar writes, killing
  the per-write shell-spawn latency in the settings suite.
- **Own the chrome:** hide/replace the vendor status + nav bar via
  `Sys_Statusbar_Icon_Config_Key` / `SYS_SHOW_TOOL_NAVI_BAR_WND`.
- **Sole-HOME mode (optional, reversible):** `pm disable-user com.szchoiceway.customerui`
  behind a big warning, with the recovery path documented in-app and in the README.
- **Startup speed:** baseline profile for the launcher, cold-start budget measured in CI
  (feeds the wider boot-speed effort).

## v3.0 — The cockpit release

- **Vehicle dashboard screen:** everything the car tells us in one glanceable surface —
  speed, outside temp, steering angle, radar history, ACC state, trip timer — from the CAN
  flows carlib already carries.
- **Per-driver profiles:** named bundles of theme + favorites + quick-launch layout +
  reachability, switchable from Home in two taps.
- **Gateway UIMODE handshake** (`ACTION_LAUNCHER_TO_EVENTCENTER_UIMODE_EVENT`) so the vendor
  stack treats us as *the* launcher (root tier).
- **HVAC write controls — only if proven:** gated on the STATUS goal #3 live test showing
  RAV4 climate is CAN-writable; otherwise the control stays greyed with its one-line reason.
- **Stability bar for 3.0:** a week of daily driving with zero crashes, screenshot suite
  green, README + design docs updated to describe what shipped rather than what was planned.

---

## Engineering track (parallel, any release)

- **CI growth** (on the PR #14 base): unit tests for the pure stores/repos
  (`ThemeStore`, `FavoritesStore`, `AppOrderStore`, preset parsing), then emulator screenshot
  tests on the `rav4_headunit` AVD profile (1920×720 @ 240 dpi, API 33), APK-size budget.
- **Release traceability:** tag `vX.Y.Z` on merge; the CI artifact of the tagged main build
  is the only APK that goes on-device, noted in the PR body per `~/CLAUDE.md`.

## Explicitly out of scope

- Hosting or drawing over the reverse camera video (vendor-owned, see above).
- Platform-signature / `priv-app` install (key unobtainable; root tier replaces it).
- Theming the vendor IME (impossible; replaced by in-app keyboards in v2.7).
