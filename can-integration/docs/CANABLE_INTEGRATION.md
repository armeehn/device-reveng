# CANable 2.0 → RAV4 launcher: raw Toyota CAN integration

A **second, independent** CAN data path for the launcher, alongside the existing HiWorld CANBOX
digest. Where HiWorld gives a pre-chewed vendor serial protocol, this reads the **real vehicle bus
1:1** off a CANable 2.0 (slcan firmware) on `/dev/ttyACM0` and decodes it against opendbc
`toyota_nodsu` (hybrid). Both paths fold into one `VehicleState`.

```
CANable 2.0 (slcan fw) ──USB CDC-ACM──▶ /dev/ttyACM0
        │
        ▼
  CanableReader  ──(t/T lines)──▶ RawCanDecoder.decode(id, data) ──▶ RawCanSignal
        │                                                              │
        │  VehicleState.apply(signal, atMs)  ◀───────────────────────┘
        ▼
  StateFlow<VehicleState>  ──▶ launcher UI
        ▲
        └── VehicleState.applyHiworld(canSignal)   ◀── HiWorld path (HiworldCanDecoder)
```

## The three pieces

| File | Layer | Depends on |
|------|-------|-----------|
| `RawCanDecoder.kt` | **Pure Kotlin**, no Android. `decode(id, data): RawCanSignal?` + `selfTest()`. | nothing |
| `CanableReaderService.kt` | Android glue: `class CanableReader` + pure `data class VehicleState`. | `RawCanDecoder`, `RootShell`, coroutines |
| `canable-probe.sh` | Hardware bring-up / tap discovery, laptop **or** adb-shell. | POSIX sh only |

> **Naming note:** the decoder's result type is `RawCanSignal`, **not** `CanSignal` — the HiWorld
> decoder already owns `CanSignal` in `com.ripostelabs.carlauncher.carlib`, and two top-level `CanSignal`
> would not compile. Same shape as the brief asked for (Speed, WheelSpeeds, Gear, SteeringAngle,
> GasPedal, Brake, Cruise, Cruise2, Doors, Blinkers, Unknown).

## STEP 0 (gate): verify USB host + CDC-ACM BEFORE anything else

The head unit must (a) act as a USB **host** on the port you use and (b) have the `cdc_acm` kernel
driver. This is unverified on the offline unit — do not integrate until it's confirmed:

```sh
adb shell ls -l /dev/ttyACM*                 # a node must appear when the CANable is plugged in
adb shell "dmesg | grep -i cdc_acm"          # expect 'cdc_acm ... ttyACM0: USB ACM device'
adb shell "zcat /proc/config.gz | grep -i USB_ACM"   # expect CONFIG_USB_ACM=y (or =m)
adb shell lsusb                              # if present; CANable VID often 1d50/16d0
```

`canable-probe.sh` runs all of these first and refuses to continue if no node shows up. If nothing
enumerates: the port may be host-incapable or stuck in ADB mode, or the kernel lacks `cdc_acm`. Try
the other port / a powered hub. **No app work is worth doing until a `/dev/ttyACM*` node exists.**

## slcan init sequence (exact)

ASCII commands, each terminated by CR (`\r`). Sent to the same node we read from:

```
C\r     close channel (harmless if already closed)
S6\r    set bitrate 500 kbps  ← Toyota powertrain bus
L\r     OPEN LISTEN-ONLY   (preferred: adapter sends no ACKs, physically cannot transmit)
```

`L` is tried first. Some slcan builds (e.g. older CANable/candleLight) don't implement `L` and NAK
it with a BELL (`0x07`); the reader detects "no frames within 1.5 s" and reopens with `O\r` (normal).
**Either way the software never emits a `t`/`T` transmit frame** — `CanableReader.writeCmd` hard-
asserts against any command starting with `t`/`T`. On stop we send `C\r` to close cleanly.

Bitrate codes if you need them: `S4`=125k, `S5`=250k, `S6`=500k, `S8`=1M.

## Listen-only safety

Three independent guards, so a bug can't put traffic on the car's bus:

1. **Open with `L`** (listen-only) when supported — the adapter won't even ACK frames.
2. `writeCmd` **refuses** any `t`/`T` (transmit) command via `require(...)`.
3. There is **no transmit code path at all** — the reader only ever writes the four control
   commands `C`/`S6`/`L`/`O`.

The probe script is equally transmit-free.

## Root / device-node access

`/dev/ttyACM*` is usually `crw-rw---- root:dialout`, so the launcher's app uid can't open it. On this
rooted unit (SELinux **Permissive**, per the ZLink notes) the reader:

1. `RootShell.exec("chmod 666 <node>")` — then a plain-uid `FileInputStream`/`FileOutputStream` open
   succeeds (primary path).
2. If chmod is refused, falls back to piping: `su -c 'stdbuf -o0 cat <node>'` for reads and a fresh
   `su -c 'printf …\r > <node>'` per control write.

`RootShell`/`RootSession` are the launcher's existing helpers — no new privileged surface is added.

## Wiring into the launcher (alongside HiWorld)

`VehicleState` is pure and immutable; both CAN paths mutate it by returning a new copy:

```kotlin
// one reader, held in launcher DI like CarService / CarEvents:
val canable = CanableReader()                 // defaults: /dev/ttyACM0, S6 (500k)
canable.start(appScope)                        // launches read+reconnect loop on Dispatchers.IO
// canable.state : StateFlow<VehicleState>     // collect in Compose
// canable.connected / canable.frameCount      // for a capture/diagnostic view

// merge the HiWorld digest into the SAME snapshot (raw bus wins; HiWorld fills gaps):
val merged: VehicleState = canable.state.value.applyHiworld(hiworldSignal)
```

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
