# CarHal — what a head unit must provide to run Riposte OS

The contract between the OS and the vehicle side, written from what the launcher, the suite and
`McuOwner` consume today on the GT6-EAU. A Riposte-designed board is built to this table, not to
Choiceway's software. "Today" cites where the current implementation gets it; "Board" says what
our own hardware must offer so nothing above the line changes.

    ┌───────────────────────────── Riposte OS ──────────────────────────────┐
    │ CarLauncher · suite · McuOwner · HiworldCanDecoder · CanableSource     │
    ├──────────────────────────── CarHal (this) ────────────────────────────┤
    │ 1 MCU link  2 CAN  3 camera  4 power  5 light  6 audio  7 keys        │
    │ 8 radio  9 climate  10 phone  11 projection  12 panel  13 clock      │
    ├─────────────────────────────── vendor ────────────────────────────────┤
    │ SoC HALs (GPU, display, audio routing, Wi-Fi/BT, camera decode)       │
    └───────────────────────────────────────────────────────────────────────┘

| # | Surface | Today (GT6-EAU) | Board must provide |
|---|---|---|---|
| 1 | **MCU link** | `/dev/ttyHS1` 115200 8N1, world-RW. Frames `0D 0A LEN body CK 00`, CK = `~(LEN+Σbody)`. Opcodes in `McuOpcode` (RX) and `McuOwnerProtocol` (TX). Startup: `01 64`, `01 50`, setup, backlight, `01 63` + MODE_ACK. No heartbeat. | A UART or USB-CDC node the `system` uid can open, same framing, same opcode set for the subset in `McuOwnerProtocol`. Anything else is a new opcode, never a changed one. |
| 2 | **CAN** | Body-bus decoded by the HiWorld box, relayed by the MCU as `A5 5A A5 len cmd … ck` (`McuFrame`); cmds `0x32` vehicle info, `0x3D` climate, radar, TPMS, speed, gear (`HiworldCanDecoder`). Raw bus via a CANable on USB host, slcan (`CanableSource`), verified against the car 2026-09-09. | Either the same relay cmd set over the MCU link, or a raw CAN interface (SocketCAN or slcan) plus the decoders already in `RawCanDecoder`. Both is best: relay for the box-only signals (climate, indicators), raw for speed/gear/wheel keys. |
| 3 | **Reverse camera** | AIS automotive camera HAL in `/vendor/etc/camera/`, opened by eventcenter through Qualcomm AIS, not the Android camera HAL: `CameraUtils.openCamera(1)` (`BackcarEvent.java:1309`) → `libcamera_utils` → `libais_camera` (`public.libraries.txt`) → qcarcam → `ais_server` (`/system/bin`, `init.target.rc:520-532`). `Camera.open(1)` (`CameraManager.java:100`) is a dormant path: the HAL's `legacy/0` is declared (`vintf/manifest.xml:53-56`) but no service serves it, only `external/0` has an rc; signal format = `v<0..8>` into the PR2000 decoder node after `sys.pr2000.writable=1` (`persist.camera.sensorcfg.*` only on XS9922B boards, not this unit). Reverse trigger = SYS_EVENT `71` byte 1 bit 1. | A camera exposed through the standard Camera2/Camera HAL as id 1, and the reverse line either on the MCU SYS_EVENT bit or as a GPIO the owner reads. Overlay UI is ours. On 0.2 the launcher owns the reverse UI: its `ReverseCameraScreen` opens id 1 through camera2 when `ReverseTrigger` says so (camera2 never lists the PR2000, see the stock column: the feed needs the AIS client path, `build.sh` step 3d lifts `ais_server` and `riposte.rc` starts it) (the `71` line, eventcenter's rising-edge GPS speed gate `Sys_Backcar_speed_threshold`, down on the line dropping), with the `0x41` radar bands from `RadarState.fromParkingRadar` over the feed and `Sys_Backcar_Camera_Mirroring` as a texture flip. Reverse sound (on / attenuate / off) is MCU-side, bits 0x20/0x40 of the `0F` factory bit-field (`sendFactoryMcuSet`, EventService.java:9984) that 0.2 never sends, so the MCU keeps whatever the vendor last wrote. Decoder signal format (owner decision 2026-09-23, root for the launcher is a feature): the launcher sets `persist.riposte.camera.mode=<0..8>` through its root shell (a priv-app cannot set an unlabelled `persist.` prop, `default_prop`) and init's `on property:` trigger runs `riposte-camera-mode.sh`, which unlocks with `sys.pr2000.writable=1` and writes `v<n>` to the PR2000 decoder nodes, the way stock does (`BackcarEvent.java:1371, 1392-1408`; `CamerasSignalDetection.java:33, 202-219`). Values are the vendor picker's rows (`BackcarSignalTypeSet.java:61-93`): 0 auto, 1 CVBS NTSC, 2 CVBS PAL, 3 AHD 720p25, 4 AHD 1080p25, 5 AHD 720p60, 6 AHD 1080p30, 7 AHD 720p30, 8 CVBS PAL60. `rn6752_mode` (`CamerasSignalDetection.java:34`) is declared and never written on stock; `persist.camera.sensorcfg.signal` is read only (`:575`). The launcher's own `ReverseCameraDecoder` root write stays as the fallback. |
| 4 | **Power / ACC** | ACC from sysprop `sys.gotoSleep.state` (kernel/native writer, polled 1 s) and SYS_EVENT bit 0. Power key → RTC stamp `13` + `01 65` ×5, then sleep. Wake reopens the port and re-sends startup. | An ACC signal the OS can read without polling (input event or a sysprop set by init), and an MCU that holds rails until it sees `01 65`. Document the wake path. |
| 5 | **Illumination / day-night** | SYS_EVENT `71` byte 1 bit 3 (headlamps). Backlight `2E day night 80 200`; panel brightness `settings put system screen_brightness` (root today). | Headlamp line on the SYS_EVENT bit; backlight PWM behind the same `2E` frame or the standard backlight HAL. |
| 6 | **Audio** | Amp behind the MCU: main volume `05 05 v`, reports `79` (bit 7 silent), mute `0A`. EQ `09`, tone `22`, balance/fader `2F`, subwoofer `15`, beep `06`; reports `76` tone, `77` EQ, `7A` balance/fader, `7B` loudness. Loudness has no setter, only the `08 0D` toggle key. Routing in `/vendor` audio HAL. Android volume keys: STREAM_MUSIC pinned one below max, each move becomes an amp step (`AmpVolumeKeys`). Suite apps hold focus via `MediaCitizen`. | An amp the MCU drives with the same three frames, or a standard Android volume path (then `McuOwnerProtocol.mainVolume` is unused, not replaced). |
| 7 | **Wheel / panel keys** | Panel keys `72`, wheel `74`; hold/double decoded by the launcher from raw CAN frame `0x11` (`WheelGestures`). | Keys on the MCU link with the `72`/`74` codes in `EventUtils` (see `McuOwnerProtocol.Key`), and raw CAN for gestures. |
| 8 | **Radio** | Si479x tuner behind the MCU: keys `02 k` (16/17 seek, 30 FM, 31 AM), freq `0C fH fL band`, events `73` (band, freq, RDS PS/PTY). No broadcastradio HAL. | Either the same tuner-behind-MCU frames or a real `broadcastradio` HAL. The suite radio is written to the MCU frames today. |
| 9 | **Climate** | State from the box relay `0x31` (+ `0x37` right/rear zone), `HiworldCanDecoder`; keys `02 3D code action` (press, 100 ms, release) sent by canbus2 over `ACTION_MCU_CMD_EVENT`; on 0.2 `ClimateKeys` builds the same frames for `McuOwner` and `CarService.pressClimate` sends them (unverified on the car). | Climate state on the relay; actuation only if the vehicle honours it. Never a hard requirement. |
| 10 | **Phone / BT** | 0.1: vendor `btsuite` (HFP, call UI, driven by intent); pairing state via `VendorBt`. 0.2: stock stack in the car-kit roles (`bluetooth.profile.{a2dp.sink,hfp.hf,avrcp.controller,pbap.client,map.client}.enabled=true`, phone-side roles off, `ro.riposte.os.bt_carkit=1`); the launcher's `BtCarKit` reads `BluetoothHeadsetClient` / `BluetoothA2dpSink` / `BluetoothAvrcpController` (`getProfileProxy`, ids 16 / 11 / 12) and answers / hangs up through the HF client. MCU side (`BtCallMcu`): `0B n` on every HFP state change (eventcenter `sendBTState`, held during a CarPlay call), `4C 0A` before answer and `4C 14` + 300 ms before hang up (btsuite `onSendMuteToMcu`). Start-up reconnect to the last phone, four tries (`BtAutoConnect`). | Stock Android Bluetooth with the same props; the radio behind the vendor HAL. Nothing in the launcher needs btsuite itself, and no InCallService: the HF client is the call surface. |
| 11 | **Projection** | Zlink (proprietary) for wireless CarPlay / Android Auto; driven by status broadcasts and feature codes, never replaced. | Wireless Android Auto via the open receiver path, CarPlay only with a licensed MFi module. The launcher's projected row degrades to "not available". |
| 12 | **Panel** | 1920×720 @ 240 dpi landscape, capacitive touch; emulated on the farm at the same geometry. | Same aspect and density class, or a new emulator profile + a layout pass. Nothing else in the UI assumes pixels. |
| 13 | **Clock** | RTC in the MCU: set with `13`, read on `83`. | An RTC readable by the kernel (`/dev/rtc0`) is enough; the `13` frame becomes optional. |

## Rules that follow

- The OS talks to the vehicle through **one owner process** (`McuOwner`) and **one decoder set**
  (`HiworldCanDecoder`, `RawCanDecoder`). A board that needs a new frame adds an opcode and a
  decoder; it never changes the meaning of an existing byte.
- Everything vendor-specific above the HALs is **replaceable by construction**: the launcher and
  suite consume `CanSignal`, `McuOwner.Listener` and the launcher's own broadcasts, nothing from
  `com.szchoiceway.*` on the 0.2 slot.
- The suite is **untouched**: on 0.2 the launcher re-emits the vendor's action strings
  (`VendorBroadcastReemitter`) from `McuOwner` events, so apps that still register for
  `com.choiceway.eventcenter.*` keep working without a rebuild.
- What the emulator cannot show (rows 1-11) is verified at the car with `ACCEPTANCE.md`,
  and on a Riposte board with the same list.

## The car service (Riposte OS 0.3)

0.2 removed the vendor's `eventcenter`, and with it the `IEventService` binder (144 calls) that
12 OEM apps and our launcher's `CarService` (30 calls) used. `McuOwner` took over the MCU link,
but it lives in the launcher process: a launcher crash takes the MCU link and the reverse camera
with it, and every vendor call without an owner path became a silent no-op. 0.3 moves the owner
into its own always-on service and gives the OS one car API.

    launcher · suite apps · diagnostics
            │  ICarService (binder, com.ripostelabs.car)   ▲ ICarListener (oneway callbacks)
            ▼                                              │
    ┌──────────────── com.ripostelabs.car  (android.uid.system, persistent) ────────────────┐
    │ McuOwner ─ /dev/ttyHS1      decoders (Hiworld, raw CAN)      power (PowerManager)     │
    │ reverse state + PR2000 decoder (/sys/pr2000, sys.acc.state)  settings store (SysVar)  │
    └────────────────────────────────────────────────────────────────────────────────────────┘

- **Identity.** The service is signed with the AOSP platform test key and runs as
  `android.uid.system`: the GSI's platform cert *is* that public key, proven on the unit. It holds
  `REBOOT`, `MASTER_CLEAR`, `DEVICE_POWER`. The key is public, so this is a property of owner
  builds, not a security boundary.
- **The tty still rides the bridge.** On the 0.2 GSI `/dev/ttyHS1` is labelled `device`
  (`ls -lZ`, 2026-09-27), which no app domain may open, `system_app` included. The service opens
  the same `riposte.mcu.link` the launcher did (`tcp:127.0.0.1:5588` from
  `riposte-mcubridge.sh`, one connection at a time); a vendor label for the node would retire it.
- **One owner.** The service is the only process that opens the MCU tty. The launcher's
  `CarService` becomes a client of `ICarService`; its vendor (`IEventService`) path stays for
  stock firmware only.
- **Survives the UI.** `android:persistent="true"`, started by the system at boot; the
  launcher binds to it and rebinds after its own crash. Reverse state keeps flowing while no
  client is bound.
- **Drawing stays in the app.** AIS draws into a `Surface` the app owns, so the camera session
  (`AisCamera`, `AisCameraWorker`) stays in the launcher. The service owns everything around it:
  the reverse trigger, the decoder mode and lock (`DecoderSignal`), `sys.acc.state`.

### Permissions

| Permission | Level | Held by | Gates |
|---|---|---|---|
| `com.ripostelabs.car.permission.READ` | `normal` | any app | status, listener, settings reads |
| `com.ripostelabs.car.permission.CONTROL` | `signature\|privileged` | launcher (priv-app), system | everything that changes the car, power, raw frames |

### Interface

```aidl
package com.ripostelabs.car;

interface ICarService {
    int apiVersion();                                   // 1 for 0.3; additions bump it
    CarStatus status();                                 // link, ACC, reverse, lamps, versions, car profile
    void registerListener(ICarListener listener);
    void unregisterListener(ICarListener listener);

    // Source (McuOwnerProtocol.Mode)
    boolean setSource(int mode);
    int currentSource();                                // -1 when the MCU has not reported one
    void exitSource(int mode);

    // Audio (amp behind the MCU)
    void setVolume(int level);                          // 0..40
    void setMute(boolean on);
    int getEqMode();          void setEqMode(int mode);
    int[] getBalanceFader();  void setBalanceFader(int balance, int fader);
    boolean getLoudness();    void setLoudness(boolean on);  // only a toggle frame exists
    int getSubVolume();       void setSubVolume(int level);
    void beep();

    // Radio (Si479x behind the MCU)
    void radioKey(int key);
    void tune(int freq, boolean fm);
    void presetSelect(int slot);
    void presetStore(int slot);

    // Keys, display
    void injectWheelKey(int key);
    void setBacklight(int day, int night);

    // Reverse camera
    ReverseState reverseState();                        // trigger, decoder mode, lock status
    void setDecoderMode(int mode);                      // 0 auto .. 8 (ReverseCameraDecoder rows)

    // Power
    void reboot();
    void factoryReset(int scope);                       // scopes: an owner decision, still open

    // Vendor settings rows (Sys_* keys, SysVarLocalStore)
    String getSetting(String key, String fallback);
    boolean setSetting(String key, String value);

    // Diagnostics
    void sendMcuFrame(in byte[] frame);                 // CONTROL only; logged
}

oneway interface ICarListener {
    void onStatus(in CarStatus status);
    void onKey(int origin, int code, int action);       // panel, wheel; press/hold/double decoded
    void onVolume(int level, boolean muted);
    void onRadio(in RadioEvent event);
    void onCanSignal(in CanSignalParcel signal, long atMs);
    void onReverse(in ReverseState state);
}
```

### Built so far (apiVersion 2)

`McuOwner` runs in the service from `onCreate`, behind the same gate as before (`ro.riposte.os.car_owner=1`,
no `eventcenter`). The launcher reads the service's `com.ripostelabs.car.API` meta-data: 2 or more
makes it a client (`RemoteMcuOwner`), anything else keeps the owner in the launcher. Never both.

```aidl
// ICarService, after reboot(): all CONTROL except currentSource (READ)
void openLink();                     // McuOwner.start: boot, ACC wake
void closeLink();                    // McuOwner.stop: ACC sleep
void setStartup(in byte[] frames);   // handshake frames, FramePack; a change re-runs the handshake
boolean setSource(int mode);         // McuOwnerProtocol.Mode code, waits for MODE_ACK
int currentSource();                 // -1 before the first setSource
void selectCar(String carId);        // CarProfile.id, kept across boots
void sendMcuFrame(in byte[] frame);

// ICarListener
void onMcuEvent(in McuEvent event);  // COMMAND: raw inbound frame; CAN_BOX_CAR: CarProfile.id
```

`onMcuEvent` carries the raw command and the client decodes it with `McuDecoder`, the code the
owner runs, so one parcel serves every `McuOwner.Listener` call. `onStatus` carries the owner
state; frame counters go out at most once a second. The service keeps the last startup frames
and car, so the boot handshake already has the launcher's volume and setup table.

### The launcher's 30 vendor calls, mapped

`owner today` says whether 0.2 already serves the call without `eventcenter`; the rows marked
**no** are silent no-ops on 0.2 until the service arrives.

| `CarService` call (vendor `IEventService`) | owner today | 0.3 `ICarService` |
|---|---|---|
| `getValidMode` | yes | `currentSource` |
| `IsBackCarConneted` | **no** | `reverseState().trigger` |
| `getMCUVer` (×2) | **no** (listener only) | `status().mcuVersion` |
| `getValidModeTitleInfor` | yes | `currentSource` + title table in the client |
| `getCanVer` | **no** | `status().canVersion` |
| `sendSoftWareReboot` | yes (root shell, #301) | `reboot` |
| `sendFactorySet` | **no** | `factoryReset(scope)` |
| `sendMode` (×2) | yes | `setSource` |
| `sendWheelKey` | **no** | `injectWheelKey` |
| `sendMuteState` | yes | `setMute` |
| `IsMuteOn` | partly | `status().muted` |
| `sendVolState` | yes | `setVolume` |
| `sendRadioKey` | yes | `radioKey` |
| `sendUserFreq` | yes | `tune` |
| `setCurModeCallback` / `setRadioCallback` | yes (listener) | `registerListener` → `onRadio` |
| `exitCurMode` | yes | `exitSource` |
| `getEQMode` / `sendEQMode` | **no** | `getEqMode` / `setEqMode` |
| `getBALFADValue` / `sendBalFadValue` | **no** | `getBalanceFader` / `setBalanceFader` |
| `getLoudness` | **no** | `getLoudness` (+ `setLoudness`) |
| `getSndSWVol` / `sendSndSWVol` | **no** | `getSubVolume` / `setSubVolume` |
| `beep` | **no** | `beep` |
| `sendBacklight` | yes | `setBacklight` |
| `changeSetup` / `getSettingString` | local store (0.2) | `setSetting` / `getSetting` |

The EQ, balance, loudness, sub-volume and beep rows need their MCU frames confirmed on the unit
first. `McuOwnerProtocol` has no builder for them yet. Their frames still have to be read out of
the vendor's `EventService` (`sendEQMode` & co.).
