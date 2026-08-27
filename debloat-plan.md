# GT6-EAU Debloat / De-spyware Plan (review before applying)

**Method (reversible):** `adb shell pm uninstall -k --user 0 <pkg>` — removes the app for the
main user but keeps it in the system image, so it's restorable with
`adb shell cmd package install-existing <pkg>` (or factory reset). Plus we have the full
EDL backup. Nothing here deletes from /system. **Prefer this over deleting APKs.**

---

## TIER 1 — De-spyware / telemetry (recommended remove)
| Package | What it is | Why remove |
|---|---|---|
| `com.szchoiceway.logcatupload` | Uploads device logcat to vendor | **Phone-home.** Was running live. |
| `com.choiceway.logcapture` | Captures logs for upload | **Phone-home** companion. |
| `com.szchoiceway.update` | OTA updater | Phone-homes; can push unwanted/EDL-locking updates. We update manually now. |
| `com.es.file.explorer.manager` | ES File Explorer | **Notorious bundled adware/data collection.** Strongly recommend. |
| `com.syu.market` | 3rd-party app market | Bloat + telemetry/ad vector. |
| `com.google.android.partnersetup` | Google partner setup | Partner telemetry; safe to drop. |

## TIER 2 — Bloat (optional, your call — not car-critical)
| Package | What it is | Note |
|---|---|---|
| `com.mmbox.xbrowser` | X-Browser | Ad-supported mini browser; you likely won't use it. |
| `com.choiceway.weather` | Weather app | Phones home for weather; optional. |
| `com.szchoiceway.photoreader` | Photo viewer | Optional. |
| `com.szchoiceway.videoplayer` | Video player | Optional (you have mpv). |
| `com.szchoiceway.musicplayer` | Music player | Optional (you use Spotify). |
| `com.szchoiceway.instructions` | User manual app | Optional. |
| `com.example.android.systemupdatersample` | AOSP leftover sample | Safe. |
| `com.example.android.locationattribution` | AOSP leftover sample | Safe. |
| `com.szchoiceway.testtools` / `canbusdebug` | Factory/CAN debug | Harmless; I'd KEEP for diagnostics. |
| `com.szchoiceway.apkinstall` | Silent APK installer | Security-relevant (silent install); can disable if unused. |

## TIER 3 — KEEP (car integration critical — do NOT remove)
- **MCU/CAN/car:** `com.szchoiceway.canbus2`, `com.szchoiceway.eventcenter`,
  `com.szchoiceway.canoriginalcarmedia`, `com.lfg.szchoiceway.canupgrade`,
  `com.szchoiceway.learn.key`, `com.szchoiceway.gps`
- **Camera:** `com.szchoiceway.auxcamera`, `com.ivicar.avm`
- **Audio/radio:** `com.choiceway.dsp`, `com.szchoiceway.radio`, `com.szchoiceway.zxwmedia`,
  `com.szchoiceway.btsuite`
- **Illumination/UX:** `com.szchoiceway.ambient.light`, `com.szchoiceway.multicolor.light`,
  `com.szchoiceway.gesture`
- **System/UI/settings:** `com.szchoiceway.customerui` (launcher!),
  `com.szchoiceway.settings`, `com.szchoiceway.providers.settings`,
  `com.szchoiceway.navigation`, `com.szchoiceway.zxw_dashboard`
- **Phone projection:** `com.zjinnova.zlink` (wireless CarPlay/Android Auto — you want this)
- **Google (needed for Play/AA):** `com.android.vending`, `com.google.android.gms`,
  `com.google.android.gsf`, `com.google.android.tts`, maps, googlequicksearchbox

## Separate wins (root-enabled, not debloat)
- **Play/Android Auto reliability:** fingerprint is spoofed to Pixel 3 XL; use Magisk
  (Play Integrity Fix / Universal SafetyNet) for reliable Play + AA instead of the spoof.
- **Reverse camera:** pin `persist.camera.sensorcfg.signal` (pending drive capture).
