# CANable 2.0 → RAV4 launcher: raw Toyota CAN integration

A **second, independent** CAN data path for the launcher, alongside the existing HiWorld CANBOX
digest. Where HiWorld gives a pre-chewed vendor serial protocol, this reads the **real vehicle bus
1:1** off a CANable 2.0 and decodes it against opendbc `toyota_nodsu` (hybrid).

**The transport is the Android USB host API, not a serial node.** See "No CDC-ACM on this kernel"
below; everything in this file that once described `/dev/ttyACM0` was written before the hardware
was available and is corrected here.

```
CANable 2.0 ──USB bulk endpoints──▶ CanableUsbLink   (claims the device, no kernel driver)
                                          │
                                          ▼
                                    SlcanCodec ──▶ SlcanFrame
                                          │
                                          ▼
                              CanableSource ──┬──▶ CanableRecorder (candump log)
                                              └──▶ CanableStats    (rate, ids, banner)
```

Shipped in `launcher/carlib/`: `SlcanCodec.kt`, `CdcAcm.kt`, `CanableUsbLink.kt`,
`CanableSource.kt`, `CanableStats.kt`, `CanableRecorder.kt`, `CaptureRotation.kt`.

## The three pieces

| File | Layer | Depends on |
|------|-------|-----------|
| `RawCanDecoder.kt` | **Pure Kotlin**, no Android. `decode(id, data): RawCanSignal?` + `selfTest()`. | nothing |
| `CanableReaderService.kt` | Android glue: `class CanableReader` + pure `data class VehicleState`. | `RawCanDecoder`, `RootShell`, coroutines |
| `canable-probe.sh` | Hardware bring-up / tap discovery, laptop **or** adb-shell. | POSIX sh only |

> **Naming note:** the decoder's result type is `RawCanSignal`, **not** `CanSignal` — the HiWorld
> decoder already owns `CanSignal` in `com.ripostelabs.carlauncher.carlib`, and two top-level `CanSignal`
> would not compile. Fields: Speed, WheelSpeeds, Gear, SteeringAngle,
> GasPedal, Brake, Cruise, Cruise2, Doors, Blinkers, Unknown).

## No CDC-ACM on this kernel — verified 2026-09-08, car on

The original gate here read: *"No app work is worth doing until a `/dev/ttyACM*` node exists."*
**That was wrong, and it would have stopped the work that succeeded.**

What the head unit actually does:

- The CANable enumerates correctly on **both** USB ports (`16d0:117e`, behind the `1a40:0101` hub).
- There is **no `/dev/ttyACM*`**, no `/sys/bus/usb-serial` at all, and no ACM module in
  `/vendor/lib/modules` (audio and camera only). The kernel has no CDC-ACM driver, so no port and
  no cable will ever produce a serial node here.

CDC-ACM is only a bulk endpoint pair plus two control requests, so the launcher claims the device
through Android's USB host API and speaks the protocol itself. **No kernel module, no root, no
vendor cooperation.** Descriptors as read from the device:

```
1-1.1:1.0  class=02 sub=02  ep 0x82 interrupt      (communications)
1-1.1:1.1  class=0a         ep 0x01 bulk OUT, 0x81 bulk IN   (data)
```

`SET_LINE_CODING` **and** `SET_CONTROL_LINE_STATE` (DTR) are both required before the adapter
sends anything.

## The failure mode that costs a day: grounding

**A badly grounded CANable enumerates normally, prints its connect banner, and then ignores every
slcan command.** Ports, drivers, stalled endpoints and inverted H/L were all checked and cleared
before grounding turned out to be the answer.

The tell is asymmetric. Bytes come *out* of the adapter while `C`/`S6`/`O`/`V` are all ignored,
and reads then time out cleanly at the full timeout rather than failing fast. A slcan adapter
answers a command it dislikes with BELL, so **frames=0 with no BELL means the commands never
landed**. If the adapter talks but never answers, check GND before anything else.

## What the adapter volunteers

On connect it prints a 51-character build banner, CRLF-terminated, and **never answers `V`**:

```
16e7497-dirty github.com/normaldotcom/canable2.git
```

Note the USB product string says `b158aa7` — a *different commit* from the banner. Do not trust
either alone as the firmware identity.

## slcan init sequence (exact)

ASCII commands, each terminated by CR (`\r`):

```
C\r     close channel (harmless if already closed)
S6\r    set bitrate 500 kbps  ← Toyota bus
O\r     OPEN, ACTIVE
```

Bitrate codes: `S4`=125k, `S5`=250k, `S6`=500k, `S8`=1M.

## Do NOT use listen-only on this firmware

The earlier revision of this file preferred `L` (listen-only) and claimed a fallback would notice
within 1.5 s. **Both halves are wrong on firmware `b158aa7`.**

`L` is *accepted and silently ignored*: the channel never opens, and the host sees a healthy
interface with **zero frames and zero errors**. There is no BELL to detect and nothing to time
out on, so a "no frames yet" fallback cannot distinguish it from a quiet bus. That cost about an
hour in the car.

`SlcanBitrate`/`SlcanCodec` therefore expose **no listen-only option at all** — the mistake is
unavailable from code rather than warned about in a comment.

## Root / device-node access: not needed

Moot on this unit — there is no device node to chmod. The USB host API grants access through
`UsbManager.requestPermission`, and the launcher declares a `USB_DEVICE_ATTACHED` filter so
permission is granted on attach rather than re-prompted every drive.

## Wiring into the launcher

The reader is owned by a foreground service so a capture survives the screen closing — a drive is
exactly when nobody holds a settings screen open.

```kotlin
CanCaptureService.start(context)          // foreground, connectedDevice type
val source = CanCaptureService.shared(context)
// source.status : StateFlow<CanableStatus>   // version/banner, frames, rate, ids, capture bytes
```

**One reader per process.** Two claims on the same bulk endpoint split the byte stream between
them; that mistake was already made once on this project against the vendor MCU serial port, so
the screen observes the service's instance rather than creating its own.

Merge policy today: the raw bus is higher fidelity, so `applyHiworld` only fills fields the raw path
hasn't set (`speedKmh ?: …`). For a single UI model, collect `canable.state` and, wherever the
HiWorld path decodes a `CanSignal`, call `applyHiworld` on the current snapshot (or lift both into a
combined flow). Call `canable.stop()` on teardown (sends `C\r`, cancels the loop).

## Signals decoded (opendbc `toyota_nodsu`, hybrid)

All Motorola/big-endian (`startbit|len@0`). Extracted via `RawCanDecoder.bitField` (MSB-first
sawtooth — see its KDoc). Hybrid uses the *_HYBRID* gear/gas messages, not the ICE ones.

| ID | Message | Fields | Scale/notes |
|----|---------|--------|-------------|
| 0x0B4 | SPEED | speed | `47|16@0+` ×0.01 km/h |
| 0x0AA | WHEEL_SPEEDS | FL/FR/RL/RR + faults | 4×`15@0+` ×0.01 − 67.67 km/h |
| 0x127 | GEAR_PACKET_HYBRID | gear | `47|4@0+`; 0P 1R 2N 3D 4B |
| 0x025 | STEER_ANGLE_SENSOR | angle, rate | `3|12@0-` ×1.5 + `39|4@0-` ×0.1; rate `35|12@0-` |
| 0x245 | GAS_PEDAL_HYBRID | pedal | `23|8@0+` ×0.005 → 0..1 |
| 0x0A6 | BRAKE | amount, force, pressed | `7|8@0+`, `23|8@0+` ×40 N; pressed derived |
| 0x1D2 | PCM_CRUISE | active, adaptive, state | CRUISE_ACTIVE `5|1`, CRUISE_STATE `55|4` (≥8 = adaptive) |
| 0x1D3 | PCM_CRUISE_2 | main-on, set-speed, brake | MAIN_ON `15|1`, SET_SPEED `23|8` km/h, BRAKE_PRESSED `3|1` |
| 0x620 | BODY_CONTROL_STATE | 4 doors, seatbelt, park brake | single bits (FL45 FR44 RL42 RR43, belt62, pbrk60) |
| 0x614 | BLINKERS_STATE | left, right, hazard | TURN_SIGNALS `29|2` (1=L 2=R 3=none), HAZARD `27|1` |

**Extension point:** add an `ID_*` const, a `when` case in `decode`, a `decodeX()`, and a
`RawCanSignal` variant — one line each. Ready candidates from the DBC: STEER_TORQUE_SENSOR (0x260),
LIGHT_STALK (0x622, note `HEADLIGHT_MODE` is the rare **little-endian** `@1` field — `bitField`
handles it via `bigEndian = false`), PCM follow-distance, BSM (0x3F6).

**Engine RPM:** intentionally NOT decoded. The hybrid `toyota_nodsu` DBC has no reliable engine-RPM
message (`ENGINE_RPM` 0x1C4 is an ICE frame); don't fabricate one. Wheel/vehicle speed is the real
motion signal here.

## Verifying the decoder off-device

`RawCanDecoder` is pure Kotlin with a built-in `selfTest()` (and a `main`) covering a big-endian u16
(SPEED→50 km/h), a byte-boundary nibble (GEAR→D), a signed cross-byte field (STEER→−3°), a 15-bit
offset field (WHEEL→0), a status bit (BLINKER→left), and the unknown-ID fallback. It was compiled
with kotlinc (Kotlin 2.0.20/JDK17) and `selfTest()` returns **PASS**.
