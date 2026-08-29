# CarLauncher roadmap

_Last verified against `main` on 2026-08-28 at versionCode 61. Every "shipped" claim below was
checked by reading the code, not by the presence of a file with the right name._

Workflow: **one feature = one branch off `main` = one PR**, versionCode claimed at PR time
(check open PRs and sibling worktrees first — several sessions work this repo at once). Only
tagged `main` builds are meant to go on-device as the home launcher. CI builds every PR.

## Versioning — read this before bumping anything

`1.0.0` is reserved for the polished public release, so the display version is deliberately
`0.4.x` and **`versionCode` is the real identity**: it climbs monotonically and is the only
field that distinguishes two builds.

Two known inconsistencies in the record, kept here rather than quietly corrected, because a
reader comparing PR titles to the build file will otherwise assume one of them is lying:

- **PR titles do not track `versionName`.** The most recent merge is titled `v0.4.7.0` but
  ships `versionName = "0.4.3.2"`. Earlier titles used the pre-down-shift `4.x` names. Derive
  a release identity from `app/build.gradle.kts` only, never from a PR title or commit subject.
- **The last two merges share a version.** Both carry versionCode 61 / `0.4.3.2`, so they are
  not separable by version alone. Any release-tagging automation has to treat "version
  unchanged since the last tag" as a clean skip, not an error.

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

## v3.2 → v4.0 — the window, now allocated

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

## Deferred — needs the car, not the desk

Not blocked forever, just not buildable from here. Each needs one session at the vehicle.

- **CAN bulk-frame speed decode**, preferred over GPS. The capture instrument shipped; the
  decoder needs one real capture. This is what makes the safety gate work in a garage and at
  power-on, where GPS cannot.
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
