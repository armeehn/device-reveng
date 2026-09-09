# Verified body-bus signals — 2019 RAV4 Hybrid (XA50)

Confirmed by actuation on 2026-09-07 and, for everything that only moves while driving, on a
real drive on 2026-09-09 with the car's own ECU answering OBD PID `0x0D` as the reference.
Decoder: `carlib/RawCanDecoder.kt`. Tests: `carlib/src/test/.../RawCanDecoderTest.kt`
(10 tests, real captured frames). This table is every id `RawCanDecoder.decode` emits; the bus
carries ~111 and the rest are undecoded.

## Where the bus is

**Not the OBD-II port.** The DLC on this car is gatewayed down to two 1 Hz heartbeats
(0x4E0, 0x45A) with no diagnostic responder. The usable bus is the HiWorld **TYF2.20**
decoder behind the head unit: **pin 11 CAN-H, pin 12 CAN-L, pin 1 GND**, 500 kbit/s,
111 ids, ~1215 frames/s. Pins 13/16 are 3.3 V UART to Android — never put CAN hardware there.

## Signals

| Signal | Id | Byte | Encoding |
|---|---|---|---|
| Doors | `0x4A5` | 3 | bitfield: 0x80 driver, 0x40 passenger, 0x20 rear R, 0x10 rear L, 0x08 tailgate, 0x04 hood |
| Climate on | `0x380` | 2 | 0x00 off, non-zero on |
| Outside air | `0x380` | 6 | signed, 0.625 °C per LSB (opendbc TAMOUT) |
| Climate on | `0x3B0` | 5 | bit 0x08 |
| Cabin air | `0x3B0` | 1 | 0.25 °C per LSB, −6.5 offset (opendbc TR_TEMP). A sensor, not the setpoint |
| Blower | `0x4AD` | 6 | 0x00 stopped, 0x52..0x5B running. Duty, not the displayed step — see below |
| Ambient light | `0x3FC` | 6-7 | 13-bit: all of byte 6 plus the top five bits of byte 7 (opendbc N_LX) |
| Road speed | `0x361` | 6 | integer km/h, ~15 Hz. Not in opendbc. The best speed source on this bus |
| Road speed | `0x498` | 5 | integer km/h, ~2 Hz (opendbc DRENG06) |
| Road speed | `0x0B4` | 5-6 | signed big-endian, km/h × 0.01, ~39 Hz (opendbc SP1) |
| Wheel speeds | `0x0AA` | 0-7 | four 15-bit big-endian fields, order FR FL RR RL, 0.01 km/h, −67.67 offset |
| Steering angle | `0x025` | 0-1 | 12-bit signed (low nibble of byte 0 + byte 1), 1.5° per LSB (opendbc SSA) |
| Yaw rate | `0x024` | 0-1 | 10-bit, 0.244 °/s per LSB, −125 offset (opendbc YR) |
| Lateral accel | `0x024` | 2-3 | 10-bit, 0.03589 m/s² per LSB, −18.375 offset (opendbc GL1X) |
| Longitudinal accel | `0x320` | 4 | signed, 0.04 m/s² per LSB (opendbc GVC) |
| Brake pressure | `0x226` | 0-1 | 10-bit, 0.02 MPa per LSB (opendbc PMC). Exactly 0 with the pedal up |
| Engine rpm | `0x1C4` | 0-1 | signed big-endian, 0.78125 rpm per LSB (opendbc NE1). 0 whenever the hybrid runs electric |
| Intake air | `0x1C4` | 2 | 2.5 °C per LSB, −40 offset (opendbc THA1) |
| Gear | `0x3BC` | 1, 5 | byte 1 bit 0x20 P, 0x10 R; byte 5 bit 0x80 D. N and B never seen, so they read UNKNOWN |
| Odometer | `0x611` | 5-7 | 24-bit big-endian, whole km. 16 bits would wrap at 65 536 and this car is past 100 000 |

`0x4A6` byte 6 bit 0x01 is a courtesy line that follows any opening but names none. It is
decoded by nothing and is here only so the next person does not re-find it.

The door bitfield is **identical to the vendor MCU's own door byte** (cmd 0x11 byte 6, see
`HIWORLD_MCU_PROTOCOL.md`). Ours came from opening doors, theirs from decompiling the head
unit, and they agree bit for bit. The hood bit is taken from the vendor byte and is the one
bit never actually tested.

## How the driving signals were checked

Names from opendbc's `toyota_2017_ref_pt.dbc` were not taken on trust. Each was checked against
physics from the speed trace: steering angle tracks yaw rate at r=0.99, longitudinal
acceleration tracks dv/dt at r=0.82, brake pressure averaged 0.27 MPa while decelerating and was
exactly 0 while accelerating, engine rpm sat at 0 for 83% of the drive, and the odometer advanced
4 km against 3.86 km of integrated ECU speed. All four speed fields hit zero median error
against PID `0x0D`; the residuals are sampling latency during acceleration, not scale.

## Method

Hold one state ~60 s, capture, then compare the **fraction of frames a bit is set** against a
run holding a different state. Driver bit: 96% own door open, 6% passenger-only. Passenger
bit: 0% and 84%.

Three traps this method exists to avoid:

1. **Never diff captures of different lengths.** A longer capture accumulates more distinct
   values for free and every id looks like it gained signal. One cross-session diff produced
   14 bits reading a clean 0%→100%; all were drift.
2. **Check level versus pulse.** The first door candidate, `0x620`, gave 32 clean toggles and
   was wrong — a 0.3 s event pulse. A 60 s hold settles it.
3. **Exclude counters and checksums first.** Byte 7 on many ids here changes every frame and
   will masquerade as signal in any statistical test. `0x63B` byte 7 takes 200 distinct values
   across 190 frames.

## Not on this bus

**Exterior lighting.** Three hunts — headlights once, indicators twice, once with the car in
READY — found nothing. The vendor MCU does report indicators, but its cmd `0x18` handler drives
the turn-signal cameras, so that state most likely arrives over the IEBUS/AVC-LAN pins (TYF2.20
3/4), which CAN hardware cannot read.

**Fan step.** `0x4AD` byte 6 tracks the blower but is not the displayed step: the car has 7
steps plus off while the byte spans ten values and skips and backtracks when stepped. It is
probably duty. For the selected step use the MCU path, cmd `0x31` byte 7 low nibble.

**The rest of climate.** Setpoint, vent mode and recirculation are not on this bus either.
Nothing moved anywhere in a 1..6 fan sweep. All four stay on the MCU link, so do not go hunting
for them here.

## Diagnostics are available and easier

OBD-II works on this bus — request on `0x7DF`, replies from `0x7E8`, `0x7EA`, `0x7EE`.
Anything with a standard PID should be queried, not reverse engineered. This is how the
earlier "coolant at `0x1C4` byte 4" claim was disproved: PID 05 read 74 °C at the same instant
that byte read 88.
