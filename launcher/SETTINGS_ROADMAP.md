# Car Launcher — Settings Suite roadmap (v1.1 → v2.0)

Goal: **every vendor GT6 setting reachable from our UI**, presented as a *reskinned mirror* of
the Choiceway/Toyota settings — same structure and options, drawn entirely from our
[`CarTheme`](app/src/main/java/com/ripostelabs/carlauncher/ui/theme/CarTheme.kt) palette instead of
the vendor's fixed blue-on-grey look. Built on top of the v1.0 launcher.

## Architecture

- **`carlib`** is the integration layer (unchanged contract, extended):
  - [`SysVar`](carlib/.../SysVar.kt) — ContentResolver read + root `content` write of the vendor
    provider `content://com.szchoiceway.eventcenter.SysVarProvider/SysVar` (CAR_API §2).
  - [`CarService`](carlib/.../CarService.kt) — bound `IEventService` AIDL; extended with EQ,
    radio RDS/TA, and system (versions / reboot / factory-reset) wrappers. Ordinals confirmed
    against `AIDL_ORDINALS.md`.
- **`app/data`**:
  - [`CarSettingsController`](app/.../data/CarSettingsController.kt) — a live `StateFlow` snapshot
    of the whole SysVar table, optimistic off-main-thread writes (root fallback), change
    observation, root-availability probe.
  - [`SettingKeys`](app/.../data/SettingKeys.kt) — the vendor keyname catalog the category screens
    reference.
- **`app/ui/settings`**:
  - [`SettingsComponents`](app/.../ui/settings/SettingsComponents.kt) — the reskinned kit
    (scaffold, section card, category card, toggle / slider / picker / info / action rows).
  - [`SettingsHost`](app/.../ui/settings/SettingsHost.kt) + `SettingsHub` — the settings app's own
    back-stack and categorized menu; hosted by `MainActivity`'s `Screen.Settings`.

## Version milestones

| Version | Milestone | Highlights |
|--------:|-----------|-----------|
| **1.1** | Settings shell + component kit | Reskinned kit, `SettingsHost` back-stack, categorized `SettingsHub`, `CarSettingsController`, launcher prefs migrated into the hub |
| **1.2** | Display & Illumination | Backlight / day / night brightness, day-night source, panel & ambient / key lighting |
| **1.3** | Reverse camera | Video input type, TW6752 input, mirroring, fullscreen, window layout, radar overlay, guide/track lines, auto-exit speed |
| **1.4** | Parking radar | Sensor enable, warning tone + type, **live per-sensor distance bars** (`CarEvents.radar`) |
| **1.5** | Audio & EQ | EQ preset, balance / fader, subwoofer, loudness, test beep, speed unit + speed overlay (AIDL) |
| **1.6** | Climate | A/C panel protocol + baud, rear air, show A/C bar, **live HVAC readout** (`CarEvents.climate`) |
| **1.7** | Radio | Live band / frequency / RDS readout, band toggle + seek, **preset save / recall / delete** |
| **1.8** | Steering wheel | **Live key monitor** (index + ADC voltage), learn-mode arm/commit, current mapping table |
| **1.9** | Power & sleep | ACC on/off + power-off delays, sleep enable + timeout, live ACC status |
| **2.0** | System & about + Advanced | Firmware versions, vehicle profile, panel geometry, bus baud, reboot / factory-reset (confirmed), **raw SysVar browser** exposing every key, release build |

## Capability notes (unchanged from v1.0 — see CAR_API §6.4)

- **Reads** of SysVar and read-only AIDL work as a normal app.
- **Writes** to SysVar need root (routed through `content` shell) or a privileged/system install;
  `CarSettingsController` probes root and the hub warns when it's absent, so the UI is honest
  about what will actually persist.
- **Protected** broadcasts (steering-wheel `STEER_WHEEL_INFOR`, day/night backlight) only reach a
  privileged/system install; the live monitors stay quiet as a normal app rather than faking data.

## Verify-on-device (values inferred from firmware naming)

Several enum option sets and slider ranges are inferred from key naming (CAR_API marks these
`[inferred]`): illumination scales, reverse video-type indices, EQ preset indices, balance/fader
range, A/C panel/baud enums, power/sleep units, CAN/MCU baud enums. Confirmed values: reverse
auto-exit speed (0/1/2 → off/30/50 km/h) and speed unit (0/1 → km/h/mph). The **Advanced** browser
shows the true stored strings for cross-checking, and every guessed mapping is annotated in-code.
