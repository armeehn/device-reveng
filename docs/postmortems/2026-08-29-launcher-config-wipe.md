# Post-mortem — Head-unit launcher config wipe & the missing draft PRs (2026-08-29)

## Summary

While shipping the "latest `main`" launcher to the RAV4 head unit, two independent problems
combined into a visible "the launcher regressed a ton" outcome:

1. **Config wipe (operational miss).** Adopting the real release keystore required a package
   *uninstall* (a release-signed APK cannot install over a debug-signed one). The uninstall was
   done without first backing up `/data/data`, so the launcher's local DataStore — favorites,
   app order, driver profiles, radio presets, notification filters, settings — was erased and
   reset to defaults. **Unrecoverable** (no prior backup existed).

2. **Wrong baseline (process gap).** The build was cut from `main`, but the head unit had been
   running **PR #51** (`0.4.6.0 / vc73`, "icon-only quick-launch + grid gutter/spacing") — a
   *draft PR never merged to `main`*. Plain `main` therefore lacked those home-screen changes,
   which read as a UI regression on top of the wiped config.

Neither was a regression in `main`'s code: `main` was intact (clean linear history, all of the
day's PRs present) and the on-car APK vs the `main` build were near-identical in size with the
same feature packages.

## Impact

- Head unit launcher lost all user customization (favorites/layout/profiles/presets/filters).
- Home-screen behavior reverted to `main`'s (labelled quick-launch column) vs the icon-only
  preset the owner expected.
- System-side state was unaffected (top-bar suppression, vendor SysVar toggles survive an
  uninstall; radio *vendor* favorites `Rdo_MyFavorite*` were never the launcher's copy).
- No data leaked; no other device or shared system was touched.

## Timeline (2026-08-29, PT)

- Built `main@31257e1` → `0.4.114/vc114`, release build on server x (JDK17).
- `install -r` failed: `INSTALL_FAILED_UPDATE_INCOMPATIBLE` — x's per-machine debug key ≠ the
  laptop debug key that signed the on-car build.
- Owner directive: adopt a real release keystore going forward.
- Generated `carlauncher-release.jks` (RSA-4096, `CN=carlauncher`, SHA-256 `76e003ea…`),
  distributed to laptop + x, wired the env-var signing scheme (per `SIGNING.md`).
- **Uninstalled** `com.ripostelabs.carlauncher` (required to switch keys) **→ config wiped** →
  reinstalled `0.4.114` release-signed, re-granted root + 3 listeners.
- Owner reported the regression. Investigation: `main` intact; on-car build was PR #51.
- Restored PR #51 by re-signing its APK with the release key and `install -r` (uid preserved,
  **no further data loss**) → `0.4.6.0-restore / vc115`.
- Integrated the remaining draft PRs into `main` (see Resolution).

## Root cause

- **Config loss:** an uninstall-requiring signing-key change was performed without a data
  backup. The key change itself was correct and necessary long-term; the missing step was the
  pre-uninstall backup.
- **Baseline confusion:** "the latest lives on `main`" was true for `main`, but the device's
  actual state came from an unmerged draft branch (#51). Draft PRs held work the running device
  depended on, so `main` was not a superset of the device.

## What went wrong / right

- **Wrong:** no backup before a destructive uninstall; assumed device == `main`.
- **Right:** the mismatch was caught before further damage; the release-key switch is a real
  improvement (unifies signing so future updates are plain `install -r`, no uninstall, no data
  loss); recovery preserved uid/grants; no code was actually lost.

## Resolution

- **Signing unified.** Real release keystore live on laptop + x; head unit switched onto it.
  Future updates: `adb install -r` (uid + data + grants preserved). CI secrets added by owner.
- **Draft PRs integrated** into `main` (PR #63 GitHub / #5 Gitea), clean rebased history:
  - **#54** (2×3 quick-launch grid, rewrite icons, hide launcher/Claude) — final quick-launch design.
  - **#50** (HiWorld CANBOX decoder wired in).
  - **#51** closed as superseded by #54; **#61** closed as already-in-`main` (SHA-pin present;
    KVM handled better by `runs-on: kvm`).
  - Conflicts resolved: kept §1.1 label typography inside #54's grid; kept CI-derived versioning.

## Action items

1. **Never uninstall the launcher without a data backup first.** Pull
   `/data/data/com.ripostelabs.carlauncher/files/datastore/` (or run in-app `LauncherBackup` and
   `adb pull` it off-device) before any uninstall / signing-key switch. *(Saved to memory.)*
2. **Treat draft PRs as device state, not just proposals.** Before building "`main`" for the
   device, check what the unit is actually running (`dumpsys package … versionName`) and which
   PR produced it.
3. **Keep signing unified** so key mismatches never force an uninstall again.
4. **`LauncherBackup` auto-export** — consider writing a backup to external storage on app
   start/update so a wipe is always recoverable (external dir does not survive uninstall on its
   own; a scheduled `adb pull` or a system-dir target would).
5. **Align remotes.** GitHub `main` fast-forwards onto Gitea `main`; keep them in lockstep so
   "latest on main" is unambiguous across both.
