# Verified body-bus signals — 2019 RAV4 Hybrid (XA50)

Confirmed by actuation on 2026-09-07. Decoder: `carlib/RawCanDecoder.kt`.
Tests: `carlib/src/test/.../RawCanDecoderTest.kt` (10 tests, real captured frames).

## Where the bus is

**Not the OBD-II port.** The DLC on this car is gatewayed down to two 1 Hz heartbeats
(0x4E0, 0x45A) with no diagnostic responder. The usable bus is the HiWorld **TYF2.20**
decoder behind the head unit: **pin 11 CAN-H, pin 12 CAN-L, pin 1 GND**, 500 kbit/s,
111 ids, ~1215 frames/s. Pins 13/16 are 3.3 V UART to Android — never put CAN hardware there.

## Signals

| Signal | Id | Byte | Encoding |
|---|---|---|---|
| Doors | `0x4A5` | 3 | bitfield: 0x80 driver, 0x40 passenger, 0x20 rear R, 0x10 rear L, 0x08 tailgate, 0x04 hood |
| Any door | `0x4A6` | 6 | bit 0x01 — courtesy line, not door-specific |
| Climate on | `0x380` | 2 | 0x00 off, non-zero on |
| Climate on | `0x3B0` | 5 | bit 0x08 |
| Blower | `0x4AD` | 6 | 0x00 stopped, 0x52..0x5B running |

The door bitfield is **identical to the vendor MCU's own door byte** (cmd 0x11 byte 6, see
`HIWORLD_MCU_PROTOCOL.md`). Ours came from opening doors, theirs from decompiling the head
unit, and they agree bit for bit. The hood bit is taken from the vendor byte and is the one
bit never actually tested.

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

## Diagnostics are available and easier

OBD-II works on this bus — request on `0x7DF`, replies from `0x7E8`, `0x7EA`, `0x7EE`.
Anything with a standard PID should be queried, not reverse engineered. This is how the
earlier "coolant at `0x1C4` byte 4" claim was disproved: PID 05 read 74 °C at the same instant
that byte read 88.
