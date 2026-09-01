# can-integration — CAN/LIN decoders (staged, not yet wired)

> **Staging drop.** Nothing here is compiled or imported by the app yet: it sits outside the
> Gradle source set on purpose, so it cannot break the build. Integration is a separate step
> (see "Where each file goes" below). Treat it as reference material that happens to compile.

## Where this fits on the roadmap

`launcher/ROADMAP.md` → **Deferred — needs the car, not the desk**:

> **CAN bulk-frame speed decode**, preferred over GPS. The capture instrument shipped; the
> decoder needs one real capture. This is what makes the safety gate work in a garage and at
> power-on, where GPS cannot.

That item is the headline this drop addresses. A **real capture** was taken parked
(785 CANBOX frames) and decoded
against the on-device vendor parser (`com.szchoiceway.canbus2 → HiworldCanParseToyota.java`).
`HiworldCanDecoder.kt` is the decoder that item was waiting on.

The rest (raw-bus CAN via a CANable, climate LIN via a TJA1020) are **further** "needs the
car" hardware paths, beyond the shipped capture instrument — staged here so they exist when
the hardware is installed, not because they're ready to wire today.

## What's here

### `decoders/` — pure Kotlin, no Android deps, each has a passing `selfTest()`
- **`HiworldCanDecoder.kt`** — decodes the vendor **CANBOX serial digest** the head unit
  already receives (`MCU_CAR_CAN_INFO` / `CAN_BASIC_EVT` `byte[]`). Frame
  `A5 5A A5 | LEN | OPCODE | PAYLOAD | C1 C2`. Live-verified signals: RPM (0x32 p[2:3]),
  hybrid battery/energy (0x1F), SWC buttons + driver door + steering (0x11), range (0x13),
  gear-mirror (0x1A). **This is the one that closes the roadmap's deferred CAN item.**
- **`RawCanDecoder.kt`** — decodes **raw Toyota bus** frames (opendbc `toyota_nodsu_hybrid`
  IDs: speed 0xB4, gear 0x127, steer 0x25, gas 0x245, brake, cruise, doors 0x620,
  blinkers 0x614). Only useful with a physical bus tap (below).
- **`LinClimateDecoder.kt`** — decodes the **A/C-amp climate LIN** (frames 0xB1 status /
  0x39 buttons). Only useful with a LIN transceiver tap.

### `readers/` — Android glue (compiled against `carlib`'s real `RootShell`, not wired in)
- **`CanableReaderService.kt`** — `CanableReader` over a CANable 2.0 (slcan, `/dev/ttyACM0`),
  listen-only, exposes `StateFlow<VehicleState>`.
- **`LinReaderService.kt`** — `LinReader` over a LIN transceiver → USB-UART (`/dev/ttyUSB0`),
  plus `combineWithVehicle(can, lin)` folding CAN + LIN into one `StateFlow<UnifiedCarState>`.

### `docs/` and `probes/`
- `CANABLE_INTEGRATION.md`, `LIN_INTEGRATION.md`, `H62_TAP_PLAN.md` — wiring, protocol, tap
  points. `body-can-tap.html` — a field wiring guide.
- `canable-probe.sh`, `lin-probe.sh` — laptop/adb hardware bring-up + toggle-and-diff.

## Where each file goes when someone integrates it
| File | Destination | Note |
|---|---|---|
| `decoders/HiworldCanDecoder.kt` | `launcher/carlib/…/carlib/` | closes the deferred CAN decode; wire to `CanCapture`'s `byte[]` |
| `decoders/RawCanDecoder.kt` + `readers/CanableReaderService.kt` | `carlib/` | only after a raw bus tap exists |
| `decoders/LinClimateDecoder.kt` + `readers/LinReaderService.kt` | `carlib/` | only after a LIN tap exists; note existing `ClimateState.kt` is a *different* (AIDL) source |

## Honest caveats (all flagged in-code, none faked)
- **Speed scale is a placeholder** (`SPEED_SCALE_KMH`) — the capture was parked. Calibrate with a
  drive capture.
- **Gear** on the CANBOX path is only in the unparsed 0x1A byte; P/N/D need a drive capture.
- **LIN checksum variant + temp scale are unconfirmed** — need a live LIN capture
  (toggle-and-diff on the panel).
- **USB host-mode + CDC-ACM on the head unit is unverified** (device was offline) — that's the
  gate for the whole USB-adapter route; `canable-probe.sh` checks it.
- Body-CAN IDs are after Fisk's **2023** car; re-verify on the 2019.

