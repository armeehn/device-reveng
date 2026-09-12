# A replacement CANbox

The decoder box between the car and this head unit is a black box from a third party. It reads
both vehicle buses, decides what is worth forwarding, and hands the head unit a proprietary
serial digest. Everything this project knows about the car arrives filtered through that
decision, and nobody outside its vendor knows what it drops.

Replacing it removes the filter. This document is the design, and the honest state of each part
of it.

## What makes this tractable

The existing box is a specification we can execute against, not just an obstacle.

For any window of time we can record two things at once: the traffic arriving on the vehicle
buses, and the serial digest the box produced from it. That pair is a test fixture. A replacement
is correct, for that window, when the same input produces the same output.

```
   vehicle buses ─────▶ [ vendor box ] ─────▶ serial digest     ← recorded together
          │                                          │
          └──────────▶ [ our box ] ─────▶ digest ────┴─▶ compare
```

That turns "reimplement an undocumented protocol" into a test that runs on a desk, against real
recordings, with no car present. It is the difference between this being a research project and
an engineering one.

**It is also the prerequisite.** Nothing below should be committed to hardware before those
paired recordings exist. The launcher can already capture the vehicle bus, and a read-only tap on
the box's serial line is what captures the other half.

## The interface

One 20-pin 2.0 mm connector carries everything: the car on one side, the head unit on the other.

| Pin | Signal | Direction | Confidence |
|---|---|---|---|
| 1 | GND | — | measured |
| 2 | Battery, permanently live | in | manual |
| 3, 4 | Second vehicle bus, differential pair | in | manual |
| 5 | Reverse trigger | out | manual |
| 8 | Camera power | out | manual |
| 9 | Accessory sense | in | manual |
| 10 | Ignition sense | in | manual |
| 11 | CAN high | in | measured |
| 12 | CAN low | in | measured |
| 13 | Serial, box receives | in | manual |
| 16 | Serial, box transmits | out | manual |
| 17, 19, 20 | Steering wheel switch inputs | in | manual |
| 6, 7, 14, 15, 18 | Not connected | — | manual |

**Three pins are confirmed by measurement and the rest are read from the vendor's manual.** A
widely circulated generic table for this family has the CAN pair swapped and the serial on the
wrong pins, so treat any pinout that disagrees with this one as suspect until measured. Confirm
each pin before relying on it, and note that the numbering mirrors between the connector face and
the wire side.

The serial link is 115200 8N1 at 3.3 V logic.

## Architecture

```
        car                          our box                        head unit
                     ┌───────────────────────────────────────┐
   CAN H/L ─────────▶│ transceiver ─▶ controller ─┐          │
                     │                            │          │
   bus 2 +/- ───────▶│ line receiver ─▶ PIO ──────┤          │
                     │                            ├─▶ MCU ───┼──▶ serial 115200 ──▶
   SWC ladders ─────▶│ divider + clamp ─▶ ADC ────┤          │
                     │                            │          │
   ACC, IGN ────────▶│ divider + clamp ─▶ GPIO ───┘          │
                     │                                       │
   battery 12 V ────▶│ protection ─▶ buck ─▶ 3.3 V rail      │
                     │                                       │
                     │        reverse, camera power ─────────┼──▶ (see failure analysis)
                     └───────────────────────────────────────┘
```

## Parts, and what was rejected

**Processor: RP2040.** Its programmable IO blocks are the reason. The second vehicle bus is a
bit-level protocol with timing this project has never measured, and PIO decodes that kind of
signal deterministically without the processor having to meet an interrupt deadline. Two cores
allow the timing-critical work to be isolated from everything else. It is 3.3 V native, which the
serial link needs anyway.

Its weakness is that it has no CAN peripheral.

*Rejected: a microcontroller with CAN built in.* That removes a chip, and is the obvious choice on
a bill of materials. It was rejected for the first revision because the harder problem here is the
second bus, not CAN, and moving that onto general-purpose timers trades a solved problem for an
unsolved one. Worth revisiting once the second bus is characterised.

*Rejected: the part used for the accessory board.* Its analogue inputs are not linear enough to
sit under a resistor-ladder switch decode without calibration, and a radio stack sharing the
processor is the wrong neighbour for bit-level timing.

**CAN: a discrete controller and transceiver over SPI.** Mature, predictable, and its behaviour
under bus faults is documented. Choose a transceiver with a separate logic supply pin so the
logic side sits at 3.3 V.

**Second bus: a differential line receiver into PIO.** The purpose-built protocol chip for this
bus is long obsolete and hard to source honestly, so the front end is a receiver feeding software
decode.

**This is the highest-risk block in the design.** Its signalling has not been measured on this
car, and the design cannot be finalised from a datasheet. It needs a bench prototype against the
real bus before any board is laid out.

**Power.** The battery pin is permanently live, so quiescent draw is a real constraint rather than
a detail: a box that costs the car a few tens of milliamps will flatten it over a fortnight of
standing. Needs reverse-polarity protection, transient suppression sized for automotive load dump,
a wide-input regulator, and a genuine sleep when the accessory sense line is low.

## Failure analysis

This box sits between a car and the device the driver looks at. The interesting failures are not
software crashes.

**Transmitting on the vehicle bus.** A box that transmits malformed frames produces error frames
and can take itself, or the bus, off line. Body functions degrade and the cause is not obvious
from the driver's seat. **The first revision must be listen-only on CAN**, with transmit added
only after the digest side is proven.

**The reverse trigger.** This drives a reversing aid. If our box is wrong or unpowered, the camera
does not appear when the driver selects reverse, and they find out while reversing. This is the
one function that must not depend on our firmware being correct. Design it as a hardware path
from the car's reverse signal to the output, with the processor able to assert but not to prevent.

**Battery drain.** Covered above, and worth restating because it fails silently and slowly.

**Losing the digest.** If our box stops talking, the head unit loses every vehicle reading. That is
an inconvenience rather than a hazard, but it should be visible: a watchdog and a heartbeat in the
digest let the head unit say the box is gone rather than quietly showing stale values.

## Phases

**Phase 0 — capture the specification.** Record the vehicle bus and the box's digest together
across ordinary driving. Software for both halves exists. Nothing else starts until this does.

**Phase 1 — a decoder on a desk.** Replay recorded bus traffic into our decode and compare its
digest against what the real box produced. No hardware beyond a development board, no car, and a
failing comparison is a specific unimplemented message rather than a vague doubt.

**Phase 2 — in parallel, listening only.** Our box on the same buses as the real one, producing a
digest that goes nowhere except a log. The real box stays in charge of the car. Differences are
now measured on real traffic rather than recordings.

**Phase 3 — in line.** Swap it in, with the original box kept in the vehicle. The fallback is a
five-minute job in a car park, which is the point.

## Open questions

- The second bus's signalling, which decides the front end. Measurement, not research.
- What the reverse and camera-power outputs actually drive, electrically. Measure the real box.
- The steering wheel ladder resistances, which decide the divider values.
- Whether the box ever transmits on the vehicle bus itself, or only on the second bus. This
  changes whether a listen-only first revision is a limitation or simply correct.
- Quiescent current of the existing box, as the budget to beat.
