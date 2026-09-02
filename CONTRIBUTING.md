# Contributing

Thanks for looking. This started as personal research on one head unit and grew a
launcher; outside help is welcome, especially from people with the same hardware.

## Before you open a PR

**Say which unit you have.** Almost every bug here is variant-specific. Include the
output of:

```bash
adb shell getprop | grep -E "ro.product.(model|device)|ro.build.fingerprint|persist.sys.mcu"
```

This project is developed against a **GT6-EAU** (MCU `RLC0_GT6E`, Qualcomm QCM6125,
Android 13). A GT6-SE (`AT01_GT6SE`) is a different unit — findings may not carry over.

## Ground rules

- **One feature per pull request.** Small, reviewable diffs merge; large ones stall.
- **Never commit vendor material.** No firmware, ROM, MCU images (`RLC0.bin`),
  boot images, decompiled vendor APK sources, or EDL backups. `.gitignore` blocks
  the common paths, but the responsibility is yours.
- **Never commit device secrets.** No IPs, `ip:port` pairs, serials, pairing codes,
  VINs, or keystore material. Use placeholders like `<ip:port>` and `<pkg>`.
- **Do not hand-edit version numbers.** `versionCode` is derived from git history
  (see `launcher/app/build.gradle.kts`); hand-claimed versions made every
  squash-merge conflict with every sibling branch.

## Building and testing

```bash
cd launcher
./gradlew :app:test            # JVM unit tests — no device or emulator needed
./gradlew :app:assembleDebug   # debug APK
./gradlew :app:lintRelease     # lint; errors fail, warnings do not
```

Requires JDK 17 and an Android SDK with platform 34. Build from a **full clone** —
the version is derived from git history and a shallow clone fails the build with an
explicit message.

`lint-baseline.xml` exists to carry pre-existing findings. **Shrink it, never grow
it** — do not add a new entry to dodge a fresh lint error.

CI runs unit tests, lint and a release assemble on every PR.

## Code style

- Kotlin, Jetpack Compose, Material 3.
- The car integration layer lives in `:carlib`. UI code talks to `carlib`, never to
  the vendor AIDL or a root shell directly.
- Comment *why*, not *what*. The vendor behaviour this app works around is rarely
  self-evident from the code.

## Safety

Changes that touch root, EDL, partition writes, or the MCU get extra scrutiny. If a
change can brick a unit, say so in the PR description and explain the recovery path.
Debloat additions must stay reversible (`pm uninstall -k --user 0`).

## Reporting a security issue

See [SECURITY.md](SECURITY.md).
