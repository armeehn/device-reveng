# CarLauncher roadmap

_Last verified against `main` on 2026-08-28 at versionCode 61. Every "shipped" claim below was
checked by reading the code, not by the presence of a file with the right name._

Workflow: **one feature = one branch off `main` = one PR**. Versions are derived from git
(see below) — do not claim, bump, or mention a versionCode in a PR. Only tagged `main`
builds are meant to go on-device as the home launcher. CI builds every PR.

## Versioning — nothing to bump

Both fields are derived from git in `app/build.gradle.kts` at build time: `versionCode` is
the commit count at the merge-base with `origin/main` (each squash-merge raises it by one,
so it is monotonic by construction), `versionName` is the bare base (`0.7`, shown as
`0.7 (<versionCode>)` wherever a build is identified). `1.0.0` is
reserved for the polished public release; only a deliberate milestone commit changes the
base. **No PR touches a version line** — hand-claimed versionCodes made every squash-merge
conflict every open sibling PR, and duplicates got claimed anyway.

Two known inconsistencies in the pre-derivation record (versionCode ≤ 71), kept here rather
than quietly corrected, because a reader comparing PR titles to old tags will otherwise
assume one of them is lying:

- **PR titles do not track `versionName`.** One merge is titled `v0.4.7.0` but shipped
  `versionName = "0.4.3.2"`. Earlier titles used the pre-down-shift `4.x` names. Derive a
  release identity from a build's tag or APK metadata, never from a PR title or commit
  subject.
- **Two merges share versionCode 61 / `0.4.3.2`**, so they are not separable by version
  alone — only by the short SHA in the tag. Derivation makes a repeat impossible going
  forward.

## Ground rules that shape this plan

These are settled. Re-proposing them costs a session each time, so the reasoning is recorded.

- **Reverse video is permanently vendor-owned.** The vendor composites its own reverse window
  above all apps; we cannot host or draw over the feed. Anything "reverse camera" beyond
  coexistence and settings is out of scope forever.
- **The platform signature tier is unreachable** — the platform private key is confirmed
  unobtainable. The design doc's "system app" tier was replaced by a **root tier** (Magisk root
  + permissive SELinux), which is what shipped.
- **HVAC writes are unproven.** Climate may be display-only on this vehicle. The control ships
  only if a live test proves writability; until then it stays read-only.
- **The radar byte layout is guessed.** The code says so and degrades to a placeholder rather
  than fabricating a reading. Radar features do not grow until a capture confirms the layout.
- **Never write a guessed encoding to the vehicle.** The `Rdo_MyFavorite0..5` preset format and
  the `sendMode` source table are both absent from the decompile; both paths stay read-only and
  display raw values, which is the capture needed to reverse them. A wrong write corrupts
  vendor state irreversibly — and a prior release had to fix SWC "learn" writes that
  crash-looped the vendor gateway and bricked the top bar on boot.

---

## Landed on `main` (v2.5 → v3.1)

Verified present and wired by reading the code, not merely present as files. **"Landed" means
built and merged, not validated in the car** — the recent series has not run on the head unit,
so nothing below is confirmed working against real hardware. That is what the stability bar in
"Deferred" exists to establish, and it is the single largest piece of unfinished business.

- **v2.5 Motion awareness** — real GPS speed with EMA smoothing and a staleness timeout that
  reports unknown rather than faking zero; parked-only gating enforced on drawer search, the
  keyboard, the theme editor, the SysVar browser and destructive actions; hysteresis unit-tested.
- **v2.6 Media & Radio full screens** — 40 sp title and 96 dp transports, a scrub control that
  degrades to read-only while moving, band toggle, 48 sp frequency, seek, and a 6-slot preset
  row backed by our own store.
- **v2.7 Content & comfort** — the media stack surfaces an external Jellyfin session, a
  parked-only continue-watching shelf, a notification shelf with per-app filtering, and an
  in-app Compose keyboard that has fully replaced the vendor IME (no raw text field call sites
  remain).
- **v2.8 SWC & radar** — roving focus ring on every screen with wrap-around, held-repeat tuning,
  long-press secondary actions and focus restore after a reverse interruption; a radar
  side-strip that hides while reverse is engaged rather than fighting the vendor window.
- **v2.9 Root-native tier** — a root-uid broadcast shim for protected SWC and backlight events,
  a persistent `su` channel, vendor chrome suppression with value backup/restore, and a
  reversible sole-HOME mode behind an auto-rollback window.
- **v3.0 Cockpit** — vehicle dashboard, per-driver profiles, gateway UIMODE handshake, and a
  baseline profile with a cold-start budget in CI.
- **v3.1 Status you can see** — Wi-Fi and Bluetooth chips in the top bar, push-driven, display
  only, opening the quick-controls pull-down on tap.

## One version scale from here on

This file grew two of them. Feature milestones were named `vN.N` (v2.5 … v4.0) while the APK
shipped `versionName = 0.4.<versionCode>`, and nothing wrote down how the two related — which is
how a PR titled `v0.4.7.0` came to ship `0.4.3.2`. The `vN.N` ladder is retired: a milestone is
now named by the base it ships, and the section below is the last one carrying an old name.

## v3.2 → v4.0 — the window, allocated and now closed

**Every item in this section has landed on `main`, verified by reading the code at `7cc818d`,
not by the presence of a branch with the right name** (squash-merges leave branches behind, so
`git branch --merged` says nothing here). Kept in full rather than deleted: each one records a
decision, and two of them record a rule — an indicator with no source disappears, and a guessed
decode is never written to the vehicle — that the next feature has to keep.

The previous revision left this deliberately empty pending a week of daily driving. That week
has not happened, but a full audit of the shipped code has, and it found enough real work to
fill the window without guessing. **These are corrections to what shipped, not new features** —
which is the right shape for a run-up to a public release.

### Stability invariants that shipped broken

- **An indicator with no source must disappear, not lie.** Wi-Fi, Bluetooth and volume honour
  this; the brightness chip does not — it renders unconditionally and defaults an unreadable
  read to a fabricated midpoint. "A chip that lies is worse than no chip" is the rule; make the
  read nullable and drop the chip.
- **Eyes-free confirmation is missing where it matters.** The beep-and-haptic helper is applied
  to eleven files but not to Home, the app drawer, quick controls or settings rows — the four
  surfaces a driver touches most.
- **The volume chip still polls.** It samples on a timer instead of riding the vendor volume
  event, so a change made at the wheel is stale on the bar for seconds. Ships only if the AIDL
  genuinely exposes a callback; if it does not, record which methods were checked and keep the
  poll.

### Crash and ANR correctness

The launcher is HOME. A crash is a black screen in a moving car, and the unit's wireless-ADB
port rotates on every reboot, so a crash on the road currently leaves no evidence at all.

- **Guard the vendor broadcast paths.** Unparcelling a vendor Bundle whose Parcelable classes
  are deliberately not bundled into this APK throws, and an exception escaping `onReceive` is
  fatal. These frames arrive continuously with the engine running, so it is a crash loop rather
  than a one-off. The same applies to the notification-listener callbacks.
- **Blocking vendor IPC must leave the UI thread.** The vendor AIDL is not `oneway`. Several
  screens still call it from click handlers and composable bodies, including one slider that
  fires two binder round-trips per drag frame. The fix pattern already exists in the codebase;
  the remaining sites simply never adopted it.
- **A crash handler with an on-disk ring buffer**, surfaced in the diagnostics screen and
  exportable without ADB, so a fault in the car is recoverable evidence.

### Safety gating

- **The raw SysVar browser can re-brick the gateway.** It exposes every live key to free-text
  editing, including the one whose malformed value crash-loops the vendor service on boot. That
  write was removed from the feature screen and remains fully reachable here. It needs a
  refuse-list rendering dangerous keys read-only, with the reason shown.
- **"Reboot head unit" is not marked destructive**, so the parked-only gate never withholds it.
  Confirming it at speed takes down the reverse camera, SWC, radio and launcher mid-drive.

### Traceability and proof

- **The repo has zero tags** after thirty-plus merges, so nothing identifies which build is in
  the car. Tag on merge from the build file, publish the APK and its hash against the tag, and
  make an unchanged version a clean skip.
- **Release is signed with the debug key.** Debug keystores are per-machine and per-runner, so
  two builds of one commit can carry different signatures and an update can fail to install
  over its predecessor. Needs an owner-supplied keystore in CI secrets; do not commit a key.
- **No screenshot suite and no instrumented test of any kind.** The status indicators were
  declared a stability invariant through v4.0, and nothing enforces it. Note that on an
  emulator there is no root, no vendor service and no car, so the brightness and volume chips
  are *legitimately* absent there — a test asserting all four are always visible would be
  wrong, and "fixing" it by making the chips always render would destroy the property the
  invariant exists to protect.
- **CI renders at the wrong geometry.** The emulator boots a portrait phone profile, so nothing
  in CI has ever been drawn at the head unit's landscape resolution and density.

### Documentation truth

The design doc still promises the signature/system-app tier that was proven impossible, and the
project status file does not mention that a custom launcher exists at all. Both mislead the next
reader into re-deriving settled decisions.

## 0.5 — the suite lands

The 0.4 window was corrections. 0.5 is the release that makes the *rest* of the unit ours: the
twenty-six standalone rewrites in `armeehn/rav4-apps` (`com.ripostelabs.clock`, `…browser`,
`…weather`, …) stop being a separate project that happens to be installed, and become apps the
launcher knows about and styles.

Shipped in this milestone:

- **The launcher publishes its active palette.** `ThemeProvider` serves the resolved day/night
  variant as a one-row cursor on `content://com.ripostelabs.carlauncher.theme/active`. Pull-based so
  a cold-started app is themed before its first frame rather than flashing a fallback;
  no runtime grant, so a freshly installed app is themed without a trip to the car; observable,
  so an app on screen re-paints on a theme switch or a night crossing. Read-only — a suite app
  that could write the palette could restyle the home screen of a moving car.
- **`RiposteSuite`, the registry of what the suite is.** Nothing on the device marks the
  twenty-six as one family, and a `com.ripostelabs.` prefix match would swallow the launcher itself
  and its `.debug` sibling. Membership is an explicit list, so an app that failed to install is
  reported missing instead of silently leaving the family.
- **Setup Doctor counts the suite.** `Rewritten app suite (n/26)`, naming what is absent. It
  never blocks: the launcher is complete without any of them, so a partial suite is reported,
  not treated as a fault to repair.
- **The provider authority is applicationId-scoped**, so a `.debug` launcher installs alongside
  the release one instead of failing on a conflicting authority. Clients pin the release
  authority: a bench build must not restyle the suite the car is running.

Open on the suite side of the boundary (tracked in `armeehn/rav4-apps`, not here): each app
reads the provider and paints it, falling back to its built-in palette when the launcher is
absent or older.

## 0.6 and 0.7 — delivered in the app suite

Neither milestone changed the launcher, and the base jumps 0.5 → 0.7 with no 0.6 build in
between. That is deliberate, and recorded here because a reader comparing tags will otherwise
assume a release went missing.

**The base names the project's milestone, not this module's changes.** From 0.5 the launcher and
the standalone `com.ripostelabs.*` suite (`armeehn/rav4-apps`) are one product: the launcher publishes
the palette and the session registry, the suite consumes them, and a milestone is only real when
both sides of the boundary work. 0.6 and 0.7 were entirely on the suite's side of it:

- **0.6 — the suite paints the launcher's palette.** 0.5 shipped the provider; the suite's Java
  call sites followed, but colours written in *XML* resolve at inflate time and stayed on the
  built-in palette. On a light theme that produced a half-themed screen — light ground, dark
  cards. The suite now re-colours its finished view tree, and re-paints live when the palette
  changes.
- **0.7 — the suite became a citizen of the car's audio.** Its apps never requested audio focus,
  so they played over the radio and never ducked for navigation; and none published a
  `MediaSession`, which is what **this launcher's own now-playing card reads**. Our Music app was
  invisible to our own home screen, and the steering-wheel media keys reached nothing. Both are
  fixed on the suite side, against the interfaces this module already exposed.

Verified on the emulated head unit: the launcher's now-playing card shows the suite's Music app
with live position, and pausing from that card pauses the app through the session — the same path
the wheel's buttons use.

## Deferred — needs the car, not the desk

Not blocked forever, just not buildable from here. Each needs one session at the vehicle.

- **CAN bulk-frame speed decode**, preferred over GPS. This is what makes the safety gate work
  in a garage and at power-on, where GPS cannot. _Progress (vc72, 2026-08-29): `HiworldCanDecoder`
  is now wired into the CAN capture screen, decoding the live CANBOX digest — RPM, hybrid battery,
  SWC, doors, steering and range are confirmed and shown. **Speed and gear are NOT finished:** a real
  drive (0→54.8 km/h vs head-unit GPS) proved road speed is not the `0x32` field the decoder assumed
  (that correlation was an interpolation artifact across a 5.7-min GPS dropout). The true speed is
  `0x17 p[0:1]` (accurate, ~0.1 km/h/LSB — raw 540 = 54.0 km/h — but low-rate and calibrated on only
  2 points) and `0x13 p[0:1]` (live ~10 Hz, scale unconfirmed, R²≈0.66). Both are decoded and shown as
  **candidates**, kept out of the motion gate. Gear: only **reverse** is in the digest (`0x71` bit
  `0x02`); P/N/D are not transmitted (the `0x1A` byte is a shift transient — D reads the same as N
  while driving). Remaining work: a **steady-cruise capture** (hold 20/40/60/80 km/h ~10 s each, with
  continuous 5–10 Hz GPS, no dropout) to calibrate `SPEED_017_SCALE_KMH` / the `0x13` scale; P/N/D
  likely must be inferred (reverse from `0x71`, "in-gear & moving" from speed)._
- **Radar byte layout confirmation**, which unblocks radar history on the dashboard.
- **The LHD/RHD auto table.** The manual override works; the automatic side detection is inert
  because the vendor car-type values are unknown. One device read, then one line.
- **HVAC writability**, per the ground rules above.
- **The v3.0 stability bar** — a week of daily driving with zero crashes.

## Explicitly out of scope

- Hosting or drawing over the reverse camera video (vendor-owned).
- Platform-signature / privileged install (key unobtainable; the root tier replaced it).
- Theming the vendor IME (impossible; replaced by the in-app keyboard).
- Radio scan and RDS station text — no method to start a scan and no station-name or radio-text
  getter exists in the vendor AIDL. Seek and a boolean RDS indicator are the whole surface.
