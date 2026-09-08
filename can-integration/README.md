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
- **`RawCanDecoder.kt`** — decodes **raw Toyota body-bus** frames. Now implemented and unit
  tested against real 2019 RAV4 captures (see `docs/VEHICLE_SIGNALS_2019.md`): doors **0x4A5**
  byte 3 bitfield, climate on/off **0x380**/**0x3B0**, blower **0x4AD** byte 6.
  Two ids in the earlier 2023-derived list were re-verified on the 2019 and are **wrong**:
  `doors 0x620` is a ~0.3 s activity *pulse*, not door state (it stayed clear for 47 s of a 60 s
  door-held-open run), and `blinkers 0x614` is not indicators — indicators are not on this bus at
  all. Speed 0xB4, gear 0x127, steer 0x25 and gas 0x245 remain plausible but are **not yet
  actuation-verified on this car**, so the decoder does not emit them.
- **`LinClimateDecoder.kt`** — decodes the **A/C-amp climate LIN** (frames 0xB1 status /
  0x39 buttons). Only useful with a LIN transceiver tap.

### `readers/` — staging only, NOT in the gradle build
- **`CanableReaderService.kt`** — `CanableReader` over `/dev/ttyACM0`. **Superseded and unused.**
  This head unit has no CDC-ACM driver, so the serial-node approach cannot work here; the shipped
  path is the Android USB host API in `launcher/carlib/` (`CanableUsbLink` and friends). Kept for
  reference on a device that *does* expose a node.
- **`LinReaderService.kt`** — **does not exist.** Listed here previously as though it did. A LIN
  reader has not been written; `LinClimateDecoder.kt` is the only LIN code in the repo.

### `docs/` and `probes/`
- `CANABLE_INTEGRATION.md`, `LIN_INTEGRATION.md`, `H62_TAP_PLAN.md` — wiring, protocol, tap
  points. `body-can-tap.html` — a field wiring guide.
- `canable-probe.sh`, `lin-probe.sh` — laptop/adb hardware bring-up + toggle-and-diff.

## Where each file goes when someone integrates it
| File | Destination | Note |
|---|---|---|
| `decoders/HiworldCanDecoder.kt` | `launcher/carlib/…/carlib/` | closes the deferred CAN decode; wire to `CanCapture`'s `byte[]` |
| `decoders/RawCanDecoder.kt` | `carlib/` | already shipped; the reader beside it is superseded by `CanableUsbLink` |
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

