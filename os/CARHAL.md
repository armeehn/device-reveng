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
| 3 | **Reverse camera** | AIS automotive camera HAL in `/vendor/etc/camera/`, opened by the vendor app with `Camera.open(1)`; signal format pinned by `persist.camera.sensorcfg.signal`. Reverse trigger = SYS_EVENT `71` byte 1 bit 1. | A camera exposed through the standard Camera2/Camera HAL as id 1, and the reverse line either on the MCU SYS_EVENT bit or as a GPIO the owner reads. Overlay UI is ours. |
| 4 | **Power / ACC** | ACC from sysprop `sys.gotoSleep.state` (kernel/native writer, polled 1 s) and SYS_EVENT bit 0. Power key → RTC stamp `13` + `01 65` ×5, then sleep. Wake reopens the port and re-sends startup. | An ACC signal the OS can read without polling (input event or a sysprop set by init), and an MCU that holds rails until it sees `01 65`. Document the wake path. |
| 5 | **Illumination / day-night** | SYS_EVENT `71` byte 1 bit 3 (headlamps). Backlight `2E day night 80 200`; panel brightness `settings put system screen_brightness` (root today). | Headlamp line on the SYS_EVENT bit; backlight PWM behind the same `2E` frame or the standard backlight HAL. |
| 6 | **Audio** | Amp behind the MCU: main volume `05 05 v`, reports `79` (bit 7 silent), mute `0A`. Routing in `/vendor` audio HAL. Suite apps hold focus via `MediaCitizen`. | An amp the MCU drives with the same three frames, or a standard Android volume path (then `McuOwnerProtocol.mainVolume` is unused, not replaced). |
| 7 | **Wheel / panel keys** | Panel keys `72`, wheel `74`; hold/double decoded by the launcher from raw CAN frame `0x11` (`WheelGestures`). | Keys on the MCU link with the `72`/`74` codes in `EventUtils` (see `McuOwnerProtocol.Key`), and raw CAN for gestures. |
| 8 | **Radio** | Si479x tuner behind the MCU: keys `02 k` (16/17 seek, 30 FM, 31 AM), freq `0C fH fL band`, events `73` (band, freq, RDS PS/PTY). No broadcastradio HAL. | Either the same tuner-behind-MCU frames or a real `broadcastradio` HAL. The suite radio is written to the MCU frames today. |
| 9 | **Climate** | Display-only from the box relay `0x3D`; commands `02 3D key action` over `ACTION_MCU_CMD_EVENT` (unverified on the car; see `command-bytes.md`). | Climate state on the relay; actuation only if the vehicle honours it. Never a hard requirement. |
| 10 | **Phone / BT** | Vendor `btsuite` (HFP, call UI, driven by intent); pairing state via `VendorBt`. | Stock Android Bluetooth (HFP client + A2DP sink) with a plain `TelecomManager` InCallService of ours. Nothing in the launcher needs btsuite itself. |
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
- What the emulator cannot show (rows 1-11) is verified at the car with the acceptance list in
  Plane RAV4-82, and on a Riposte board with the same list.

Plane RAV4-85.
