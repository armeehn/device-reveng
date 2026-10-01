# Uplink: automatic road-noise capture and uploads

The launcher records short clips of cabin noise while the car drives. It deletes any clip that
contains a voice, and uploads the rest with the unit's log ring to the owner's server, where the
microphone's noise filter is retrained. Nobody has to press anything.
The link to that server is described in [os/uplink/README.md](../os/uplink/README.md).

```
 CarEvents ──UplinkSignals──▶ UplinkService
                               ├─ capture: NoisePlan.hold() == NONE?
                               │     mic (VOICE_COMMUNICATION, 48 kHz mono) ─▶ 20 s in memory
                               │     SpeechGate: speech ─▶ zeroed, never written, counted
                               │                 noise  ─▶ WAV + sidecar ─▶ UplinkQueue
                               └─ upload: any network; tether bytes against DataBudget
                                     UplinkQueue ─UplinkClient─RawHttp─SOCKS5─▶ tailnet ─▶ ingest
```

## When the microphone opens

Only when all of these hold (`NoisePlan`, checked every second and again every 100 ms while
a clip records; any change throws the clip away):

- the switch in Settings > Improve noise reduction is on (it is on by default);
- ACC is on and the car moves at 5 km/h or more;
- not in reverse (this rule wins over every other);
- no call: car-kit, vendor Bluetooth or CarPlay;
- no CarPlay session at all, which covers Siri;
- no other app records, and Android has not silenced us for one;
- no voice in the last minute (see below);
- less than 100 MB waits on the unit;
- less than 3 minutes kept this drive, at most 1 minute of it in one band;
- the trainer still wants that band (`GET /v1/wants`).

Bands: `city` under 70 km/h, `highway` from 70 km/h. A `-fan` suffix is added when the blower
is at half its range or more. Android shows its microphone dot while a clip records. That is
expected.

## Speech never leaves the car

`SpeechGate` (method `riposte-vad/1`) judges each 20 s clip in memory before anything touches
flash. A clip with a voice is overwritten with zeros, only its length is counted ("Speech
discarded" in Settings), and the microphone stays closed for the next minute, since a
conversation rarely fits in one clip. A clip of pure digital silence (a dead or muted
microphone) is dropped too.

The gate is two detectors, and either one is enough to discard a clip. Each 20 ms frame at
16 kHz gets a voicing score (pitch-lag autocorrelation, 70-400 Hz), a band level against the
clip's own 20th-percentile floor, and a swing (level range within 200 ms).

| detector | voicing on | voicing | level band | above floor | swing | frames |
|---|---|---|---|---|---|---|
| road | 150 Hz high-pass, pre-emphasis | 0.45 | 300-3400 Hz | 4 dB | 12 dB | 3 in a row or 6 |
| fan | 100-1000 Hz band-pass | 0.5 | 200-1000 Hz | 6 dB | 12 dB | 3 in a row or 10 |

It was calibrated on six public-domain LibriVox readers mixed into CC0 car-interior recordings
(a truck at speed, a car on a gravel road) and synthetic noise. Rates are the share of clips
caught; SNR is local to the speech.

| speech | truck | gravel road | white hiss | pink blower | tonal blower |
|---|---|---|---|---|---|
| 1 s at 0 dB | 100 % | 100 % | 100 % | 100 % | 50 % |
| 1 s at -5 dB | 100 % | 100 % | 56 % | 19 % | 3 % |
| 0.5 s at 0 dB | 100 % | 100 % | 97 % | 100 % | 36 % |
| no speech (false alarm) | 0 % | 24 % | 0 % | 0 % | 0 % |

So a passenger who speaks at the level of the road noise is caught every time. A single short
word under a loud blower can slip through, which is why the minute of hold-off matters. The
unit tests (`SpeechGateTest`) replay real speech in real noise; the fixtures and their sources
are listed in [the fixtures notice](carlib/src/test/resources/vad/NOTICE.md).

## Uploads and the data budget

- **When.** Every 30 s while there is a network. Clips go first, then rotated files of the
  log ring (`/data/riposte/log`, copied through the root shell and gzipped). Log files are
  sent once each.
- **Budget.** Bluetooth tethering and metered Wi-Fi (a phone hotspot) count against a monthly
  budget, 200 MB by default, settable in Settings, 0 for Wi-Fi only. Each byte is charged at
  1.06 for protocol overhead. Home Wi-Fi is free. The month is the local calendar month.
- **Never while reversing or in a call.** The upload stops between two 256 KiB chunks.
- **Resumable.** The server keeps partial files by sha256, so an upload cut by ACC off or a
  reboot continues where the server stopped.
- **Verify, then delete.** A clip is deleted from the unit only after the server answers
  "complete" with the same sha256. A file the server refuses for good goes to
  `files/uplink/queue/rejected/` (the newest 20 are kept).

## Settings > Improve noise reduction

The switch, the monthly phone-data budget, and status: now, last upload, waiting to upload,
phone data this month, road noise kept, speech discarded, files uploaded.

## Updates from the server

The same link brings releases back. `OtaUpdater` runs inside `UplinkService` and needs no tap.

```
 ACC on, or a day since the last check ──▶ GET /v1/releases/manifest.json
   ──▶ a newer build of a package already on the unit, not refused before
   ──▶ download, resumable (files/ota/apk/<package>-<code>.part), budget shared with uploads
   ──▶ size + sha256 from the manifest, signer against the pin in the launcher
   ──▶ wait: parked, a minute after ACC on, no call, no CarPlay session, not in reverse
   ──▶ root pm install -r: suite apps, then the car service, the launcher last
```

- **The signer is pinned in the launcher** (`ReleasePin`), one certificate per role. The
  manifest carries a `cert_sha256` too, but the car never reads it. An APK signed by anyone else
  is refused and that version is never tried again. A new signing key needs the USB updater.
- **The car service pin is weak on purpose.** It must be signed with the image's platform key,
  and that is AOSP's public test key. For the car service the sha256 from the server, fetched
  over the authenticated tailnet, is what vouches for the file.
- **Only packages already installed** are updated. A suite app the owner never installed stays
  off the unit. The launcher and the car service update on a higher versionCode. Suite apps
  all sit at versionCode 1, so for them other bytes at the same versionCode count too, as in
  the USB updater.
- **Parked** means gear P when the CAN box reports the gear, or standing still when it does
  not. Downloads run while driving and stop in reverse or in a call, like the uploads.
- **The launcher goes last, through a watch script.** `pm install` ends the running launcher,
  so it writes `OtaWatch`'s script and starts it detached through the root shell. The script
  installs the APK, reopens Home (`am start -c HOME`), and polls the launcher's process for two
  minutes. A launcher that runs 20 s writes `healthy-<code>` and is kept. One that dies twice
  first is rolled back: Android's own rollback (`pm rollback-app`), else the previous APK kept
  in `files/ota/rollback/` with `pm install -r -d`. The next launcher reads the result, and a
  rolled-back version is never offered again.
- **Settings > Updates** shows the state, the last check, what is waiting, and the last result.
  "Check now" asks on the next minute's tick.

The USB updater (`os/car-update`) stays the fallback for anything this refuses.

## Setup on a unit

The unit needs the tailnet link and the server address once, from `os/uplink/enroll.sh`
(see the link README above). Until then Settings says "Not set up". The microphone permission
is granted through the root shell on first start; Setup Doctor checks it too.

## Code and tests

| file | what | test |
|---|---|---|
| `carlib/.../SpeechGate.kt` | the voice gate | `SpeechGateTest`: real speech in real noise, every noise alone |
| `carlib/.../NoisePlan.kt` | when to capture, bands, budgets | `NoisePlanTest`: every refusal, drive and band caps, wants, hold-off |
| `carlib/.../DataBudget.kt` | the monthly tether budget | `DataBudgetTest`: overhead, limit, month roll in local time |
| `carlib/.../UplinkClient.kt`, `UplinkQueue.kt` | resumable upload, verify-then-delete | `UplinkQueueTest`: drops, resume, pause, 422, refusals, order, dedupe |
| `carlib/.../RawHttp.kt` | HTTP over the SOCKS port | `RawHttpTest` |
| `carlib/.../RoadNoiseFile.kt` | WAV, sidecar, silence | `RoadNoiseFileTest` |
| `app/.../service/UplinkService.kt` | the two loops | emulator farm, end to end |
| `carlib/.../ReleaseManifest.kt` | the release manifest, bad rows dropped | `ReleaseManifestTest` |
| `carlib/.../ApkCert.kt`, `ReleasePin.kt` | the signer an APK names; the pins; size, sha256, signer | `ApkCertTest`: a wrong signer is refused although the manifest names the pinned one |
| `carlib/.../OtaPlan.kt` | when to check, what to take, install order, when to install | `OtaPlanTest`: every hold, order, refused versions, daily and ACC-on checks |
| `carlib/.../ApkFetch.kt` | resumable download, verify, one candidate per app | `ApkFetchTest`: resume, pause, corrupt, cleanup |
| `carlib/.../OtaWatch.kt` | the launcher's install and rollback script | `OtaWatchTest`: the script under `sh` with fake `pm`, `am`, `pidof` |
| `app/.../service/OtaUpdater.kt` | the update loop | emulator farm, end to end |
