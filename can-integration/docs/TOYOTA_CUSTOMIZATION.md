# Toyota car customisation through the HiWorld box (2019 RAV4, XA50)

Research for a launcher "Car settings" page that changes the car's own customisations (door
locks, lights, feedback) the way the stock vendor app did. Written 2026-09-27. Nothing here
has been sent to the car yet.

Confidence marks used below:

- **Verified**: read in decompiled code, a primary source, or our own capture.
- **Inferred**: follows from verified facts, not observed.
- **Guess**: plausible, no evidence either way.

## Summary

1. **Verified.** The stock app writes every car setting as one box command,
   `03 6A <group> <key> <value>`, inside the usual `5A A5` frame. Groups 1 to 3 are vehicle,
   remote and lighting settings.
2. **Verified.** The box reports the current state of 28 settings in one frame, cmd `0x62`.
   The app asks for it with the existing query `03 6A 05 01 62` each time the page opens.
3. **Verified.** The Toyota owner's manual for the XA50 lists most of these items as
   changeable from the *multimedia system* ("Vehicle customize"), and not from the cluster.
   The box stands in for that stock head unit, so this is the path it must emulate.
4. **Verified.** The TYF2.20 bus on this car already carries body frames `0x610`-`0x63B`,
   `0x6F3`, and answers OBD requests. The Techstream path (body ECU on `0x750`, sub-address
   `0x40`) is not yet probed.
5. **Inferred.** Door-lock, feedback, DRL and light-timer items are the likeliest to work.
   Seat, steering column, "smoke sensor" and radar items are the least likely.

## How the path works

```
 launcher (Car settings page)
   │  canBox(03 6A 02 05 03)            McuOwnerProtocol builds the box frame
   ▼
 5A A5 | 03 6A 02 05 03 | CK            CK = (sum of bytes after 5A A5) - 1
   │  wrapped as outer opcode 0x0D, first payload byte 0x08
   ▼
 /dev/ttyS1 ──▶ head-unit MCU           relays box frames both ways (inbound opcode 0xA5)
   │  UART, TYF2.20 pins 13 / 16
   ▼
 HiWorld TYF2.20 CAN box                configured with a car type (02 24 21 01 on this unit)
   │  CAN, TYF2.20 pins 11 / 12, 500 kbit/s
   ▼
 car-side bus at the head-unit harness  111 ids seen, incl. body 0x61x-0x63x
   │
   ▼
 central gateway ──▶ body bus ──▶ Main Body ECU / Certification ECU / A/C amplifier
                                   applies the setting, broadcasts the new state
   │
   ▲  box reads the state back and reports it as cmd 0x62
```

What each hop is based on:

- **Launcher to box.** Verified in code. canbus2 `SendUtil.SendCmdLstToCanbus5AA5Header`
  prefixes `5A A5`, appends the checksum, then `sendDataToCanbus` prepends `0D 08` and
  broadcasts it to the MCU service. Our `McuOwnerProtocol.canBox()` already builds this frame
  for `canBoxInit`. See [HIWORLD_MCU_PROTOCOL.md](HIWORLD_MCU_PROTOCOL.md).
- **Box to car.** Unknown. What the box puts on the CAN side for a setting has never been
  captured. Two mechanisms are possible, and the next section weighs them.
- **Car to box.** Unknown for the same reason. The box must learn the current state from
  somewhere to fill cmd `0x62`.

## Where the car takes customisation commands

### What the XA50 manual says (verified)

The owner's manual customisation chapter lists three columns per item: A = navigation or
multimedia system, B = multi-information display, C = Toyota dealer
([trav4.net copy of the XA50 manual](https://www.trav4.net/customization-4558.html)).
For the items the box offers, the manual gives:

| Car function (manual wording) | Default | Options | A | B | C |
|---|---|---|---|---|---|
| Automatic door locking function | Shift linked | Off, speed linked | O | - | O |
| Automatic door unlocking function | Shift linked | Off, driver's door linked | O | - | O |
| Unlocking using a key | Driver first, all second | All in first step | - | - | O |
| Smart key system | On | Off | - | - | O |
| Smart door unlocking | Driver's door | All the doors | O | - | O |
| Remote unlocking operation | Driver first, all second | All in first step | O | - | O |
| Operation signal (emergency flashers) | On | Off | O | - | O |
| Operation buzzer volume | 5 | Off, 1 to 7 | O | - | O |
| Daytime running lights | On | Off | O | - | O |
| Light sensor sensitivity | Standard | Brighter, Bright, Dark, Darker | O | - | O |
| Headlights off after doors closed | 30 s | Off, 60 s, 90 s | O | - | O |
| Interior lights off time | 15 s | Off, 7.5 s, 30 s | O | - | O |
| A/C Auto switch operation | On | Off | O | - | O |
| Intuitive parking assist, buzzer volume | On, 2 | Off; 1, 3 | - | O | O |

The manual also lists "time before auto relock if no door opened" (60 s; off, 30, 120) and
"locking operation when door opened" under column A. The box has no command for either.

So on this car the stock head unit, not the cluster, owns nearly every body setting. An
aftermarket unit loses them unless the box speaks the stock head unit's messages. This is
the strongest reason to expect the HiWorld commands to do something on the RAV4.

### The Techstream path (verified addresses, inferred use)

openpilot's Toyota firmware-query table lists the late-model Toyota ECU addresses. It names
the Body Control Module at `0x750` with extended-address byte `0x40`, answering KWP2000
`1A 88 81`, the Central Gateway at `(0x750, 0x5f)` and the combination meter at `0x7C0`
([opendbc toyota/values.py](https://github.com/commaai/opendbc/blob/master/opendbc/car/toyota/values.py)).
Techstream's "Customize Setting" function talks to those ECUs over diagnostics. Which service
and data ids it writes is not public in any source found. A box could use the same requests.
That is a guess, not evidence.

### The broadcast path (verified frames, inferred role)

opendbc's Toyota DBC defines body frames that carry customisation state and commands
([_toyota_2017.dbc](https://github.com/commaai/opendbc/blob/master/opendbc/dbc/generator/toyota/_toyota_2017.dbc)):

- `0x623` CERTIFICATION_ECU: `DOOR_LOCK_FEEDBACK_LIGHT`, `KEYFOB_LOCKING_FEEDBACK_LIGHT`,
  `KEYFOB_UNLOCKING_FEEDBACK_LIGHT`.
- `0x6F3` ADAS_TOGGLE_STATE: the cluster's toggle commands for LKA, PCS, BSM and sonar.
  This shows the cluster setting ECU options with plain broadcast frames.
- `0x610` BODY_CONTROL_STATE_2 and `0x611` UI_SETTING: units and meter brightness.
- `0x638` DOOR_LOCKS: lock state and whether the fob locked it.

A 311 s capture from the TYF2.20 bus on this car (2026-09-15) contains all of these.
`0x623` was constant `19 00 80 00 00 00 00 00`, and `0x6F3` constant `20 00 00 00 00 00 00 00`.
Both arrive about once a second. The box can therefore *read* body state without diagnostics
(inferred). Whether it *writes* with broadcast frames or with diagnostic requests is the open
question the first in-car test answers.

### What the gateway lets through (verified in part)

- The OBD port on this car is gatewayed down to two heartbeats, with no diagnostic responder.
  See [VEHICLE_SIGNALS_2019.md](VEHICLE_SIGNALS_2019.md).
- The TYF2.20 bus is different: OBD requests on `0x7DF` get replies from `0x7E8`, `0x7EA` and
  `0x7EE`. So the gateway routes at least some diagnostics from the head-unit side.
- Whether `0x750` requests reach the body ECU from that side is unknown. It is testable with
  one read-only tester-present request from a CANable on the TYF2.20 pins.

### What other boxes do

- canbox-core speaks the Raise (`2E`), HiWorld (`5A A5`) and Bagoo (`FD`) head-unit protocols
  ([canbox-core](https://github.com/fazerxlo/canbox-core)). It documents the head-unit side only.
- The Simple Soft RP5-TY-101 reverse engineering covers only the head-unit side
  ([simplesoft-canbus-box-reverse-engineer](https://github.com/zugetor/simplesoft-canbus-box-reverse-engineer)).
- Carista changes XA50 items such as DRL and remote unlock mode over OBD, which proves the car
  accepts diagnostic customisation
  ([Carista RAV4 XA50](https://carista.com/en-us/apps/supported-cars/toyota/rav4/5th-gen)).

No public source shows what a HiWorld box sends on the car side. That stays open.

## The stock command set (decompiled, verified)

Source: canbus2 `ui/carset/vios/toyota/HiworldToyotaSetConfig.java` (items and builders),
`adapter/CarSetAdapter.java` (value selection), `ui/console/vios/toyota/HiworldToyotaCarSetUILandscapeDefault.java`
(send and query).

### Builders

The adapter calls `getSendToCanByteArray(key, value, sendType)`. The builder is picked by
`sendType`:

| sendType | Bytes after `5A A5` | Used for |
|---|---|---|
| 0 | `02 2A value key` | climate panel style, a head-unit layout choice |
| 1 | `03 6A 01 key value` | vehicle settings |
| 2 | `03 6A 02 key value` | remote / key settings |
| 3 | `03 6A 03 key value` | lighting settings |
| 4 | `02 9A key value` | language (key 1) |
| query | `03 6A 05 01 id` | ask for data block `id`; the page asks for `0x62` |

The value is the chosen entry of the item's value list, plus any offset. Two items override
it with a fixed send value, noted in the table.

### Report frame, cmd `0x62` (CarSetInfo)

`HiworldCanParseToyota.OnHandleCanCarSetInfoCmd` reads it. Indices are the handler's `bArr`,
so `bArr[2]` is the first payload byte. A field is `(bArr[n] >> shift) & mask`.

### Per-setting table

"Report" is `bArr[n]` bit shift / width. "RAV4 match" is the manual row above, where one
exists.

| Setting (stock label) | Command | Values | Report | RAV4 match |
|---|---|---|---|---|
| Autolock by speed | `6A 01 01 v` | 0 off, 1 on | [3] b6 /1 | Auto lock, speed linked (A) |
| Auto door unlock | `6A 01 02 v` | 0 all doors, 1 driver | [3] b5 /1 | Smart door unlocking (A) |
| Driver door linkage unlock | `6A 01 03 v` | 0 off, 1 on | [3] b4 /1 | Auto unlock, driver door linked (A) |
| Autounlock by shift to P | `6A 01 04 v` | 0 off, 1 on | [3] b3 /1 | Auto unlock, shift linked (A) |
| Autolock by shift from P | `6A 01 05 v` | 0 off, 1 on | [3] b2 /1 | Auto lock, shift linked (A) |
| Climate and AUTO linkage | `6A 01 06 v` | 0 off, 1 on | [3] b1 /1 | A/C Auto switch operation (A) |
| Recirculation and AUTO linkage | `6A 01 07 v` | 0 off, 1 on | [3] b0 /1 | none in manual |
| Radar display | `6A 01 08 v` | 0 off, 1 on | [2] b7 /1 | parking assist, cluster only (B) |
| Radar volume | `6A 01 09 v` | 1 to 5 | [2] b4 /3 | buzzer 1 to 3, cluster only (B) |
| Front radar distance | `6A 01 0A 01` | fixed send 1 | [2] b2 /2 | none |
| Rear radar distance | `6A 01 0A 02` | fixed send 2 | [2] b0 /2 | none |
| Daytime running lights | `6A 01 0B v` | 0 off, 1 on | [5] b7 /1 | DRL (A) |
| Left seat temp auto | `6A 01 0C v` | 0 to 4 = -2 to +2 | [6] b5 /3 | none |
| Right seat temp auto | `6A 01 0D v` | 0 to 4 = -2 to +2 | [6] b2 /3 | none |
| "Smoke sensor" sensitivity | `6A 01 0E v` | 0 to 6 = -3 to +3 | [4] b1 /3 | none; label meaning unknown |
| Steering column exit move | `6A 01 0F v` | 0 off, 1 tilt, 2 telescopic, 3 both | [6] b0 /2 | none |
| Driver seat exit move | `6A 01 10 v` | 0 off, 1 partial, 2 full | [8] b6 /2 | none |
| ACC customization | `6A 01 11 v` | 0 off, 1 on | [8] b3 /1 | none; meaning unknown |
| Vehicle recommendations | `6A 01 12 v` | 0 off, 1 when stopped, 2 on | [8] b4 /2 | none |
| Left / right hand drive | `6A 01 13 v` | 0 left, 1 right | [8] b2 /1 | none; likely box-side |
| Fuel economy unit | `6A 01 14 v` | 0 MPG US, 1 km/L, 2 L/100km, 3 MPG UK | cmd `0x13` [12] | cluster units |
| Temperature unit | `6A 01 15 v` | 0 C, 1 F | cmd `0x31` [2] b0 | cluster units |
| Lock feedback by lights | `6A 02 01 v` | 0 off, 1 on | [4] b7 /1 | Operation signal, flashers (A) |
| Smart lock and one-push start | `6A 02 02 v` | 0 off, 1 on | [4] b5 /1 | Smart key system (C, dealer only) |
| Unlock on key turned twice | `6A 02 03 v` | 0 off, 1 on | [4] b4 /1 | Unlocking using a key (C, dealer only) |
| Remote 2-press unlock | `6A 02 04 v` | 0 off, 1 on | [4] b6 /1 | Remote unlocking operation (A) |
| Lock/unlock feedback tone | `6A 02 05 v` | 0 to 7 | [7] b0 /3 | Operation buzzer, off and 1 to 7 (A) |
| Auto light sensitivity | `6A 03 01 v` | 0 to 4, shown 1 to 5 | [5] b0 /3 | Light sensor, 5 steps (A) |
| Interior light off time | `6A 03 02 v` | 0 off, 1 7.5 s, 2 15 s, 3 30 s | [5] b3 /2 | Interior lights, same 4 steps (A) |
| Exterior light off time | `6A 03 03 v` | 0 off, 1 7.5 s, 2 15 s, 3 30 s | [5] b5 /2 | Headlights off, off/30/60/90 s (A) |
| Language | `9A 01 v` | 1 EN-US, 2 ZH, 5 FR, 7 ES, and more | not reported | head-unit side |
| Climate panel style | `2A v 00` | 1 landscape, 2 vertical | not reported | head-unit side |

Notes on the table:

- The two radar distance items share key `0x0A` and always send the same value, so they act
  as a "toggle front" and "toggle rear" button (inferred from `setSendValueArray`).
- Fuel and temperature units are reported in other frames, and language is only saved on the
  head unit. The page shows the saved value for language.
- The exterior light labels are generic Toyota. The RAV4 steps are off, 30, 60 and 90 s.
  That index 1 means 30 s on this car is a guess.
- The light sensor direction (whether 0 is "Brighter" or "Darker") is unknown.

### Model gating (verified)

There is none inside the page. `ViosConsoleConfigDetailed` shows the same Toyota settings page
for every HiWorld Toyota car id except the Crown "TP004" ids. `isVisibility` always returns
true. The only per-car input is the car type sent at startup (`02 24 type 01`). This unit
sends `0x21` ("RAV4 16-21") from its stock backup, while `0x9D` is "RAV4 22-Present". Whether
the box needs a different type for 2019 body frames is unknown.

## What the box reports back

- The page sends `03 6A 05 01 62` on open and on every return to it.
- The box replies with one cmd `0x62` frame; the stock page just redraws from it.
- No `0x62` frame has been captured on this car yet. The first test must log one before
  sending anything, to learn whether the box knows the RAV4's body state at all.
- If the reply is all zeros, or never comes, the box has no settings support for this car
  type. Stop there.

## What to expect on this RAV4

- **Climate.** Box climate *control* does nothing on this RAV4, with the stock app too. Climate
  settings (`6A 01 06`, `6A 01 07`) likely go to the A/C amplifier by the same broken route, so
  expect nothing. That is a guess based on that failure.
- **Likely to work (inferred).** Operation buzzer volume, flasher answer-back, interior light
  timer, headlight off delay, light sensor, DRL, the auto lock and unlock items, smart door
  unlocking and remote unlock mode. All are multimedia-owned (column A) on this car, and the
  box's value sets fit the manual, e.g. buzzer off and 1 to 7.
- **Unlikely.** Smart key on/off and key-twice unlock are dealer-only on this car. Radar items
  are cluster-only. Seat, steering column, "smoke sensor", ACC and recommendations have no
  XA50 counterpart.
- **Headlight delay values** may be relabelled: show the box index and the car's step names.

## In-car test plan

Nobody touches the car for this document. When someone does, work in this order and stop at
the first step that fails.

Preparation:

1. Park outside, P, parking brake on, engine or READY on, as the manual asks.
2. Keep both keys **outside the car, in a pocket of the person testing**, from the first write on.
   Keep a window open.
3. Before sending anything, write down the current value of every item from the cluster,
   the manual defaults and the car's behaviour. This is the restore list.
4. Start logging: launcher diagnostics on (all `CAN box tx` and box rx lines), and a CANable
   on TYF2.20 pins 11 / 12 in listen-only mode.

Steps:

1. **Read only.** Send `03 6A 05 01 62`. Record the `0x62` reply. Decode it with the table and
   compare with step 3. No match means the report layout is wrong for this car. Stop.
2. **Buzzer volume** (`6A 02 05`). Set it one step from the current value. Lock and unlock
   with the remote from outside. Hear the change, confirm the next `0x62` shows it. Set it back.
3. **Flasher answer-back** (`6A 02 01`). Off, lock with the remote, watch the hazards. Back on.
4. **DRL** (`6A 01 0B`). Off with the car in P, look at the front. Back on.
5. **Interior light timer** (`6A 03 02`). Change it, close the door, time the dome light.
   Restore.
6. **Headlight off delay** (`6A 03 03`). Change by one index, lights to AUTO at dusk, close the
   doors and time it. This step also settles the index-to-seconds mapping. Restore.
7. **Light sensor** (`6A 03 01`). One step, note which way. Restore.
8. **Lock items last**, one at a time, keys outside, window open: auto unlock by shift to P,
   auto lock by shift from P, auto lock by speed, then smart door unlocking and remote unlock
   mode. Each one: change, test the behaviour, restore, confirm with `0x62`.

How to observe each change, three ways:

- The car behaves differently.
- The next `0x62` frame shows the new value.
- The CANable log shows what the box sent. Look for `0x750` / `0x758` traffic or a new
  frame near `0x6F3` or `0x623` at the moment of the command.

Restoring defaults: send each value from the restore list, then read `0x62` again. If the box
path fails midway, a Toyota dealer or Techstream restores any item (column C covers them all).

## Risks

- **Lock-out.** Lock items can lock the car with keys inside if the test script is wrong.
  Never test lock settings with a key in the car or the cabin closed.
- **Smart key off.** `6A 02 02` might disable keyless entry and push start. It is dealer-only
  on this car, but do not send it at all until the other items are proven.
- **Wrong car type.** A mismatched car type may make the box write the wrong frames. Change
  one item at a time and read back before the next.
- **Flooding.** The stock page sends one frame per tap. The launcher must do the same, never a
  repeat loop.
- **Battery.** The manual asks for the engine running while customising. Keep the session short.
- **Hidden side effects.** The manual warns some settings change together. Read the whole
  `0x62` frame after every write, not just the one field.

## Open questions

1. Does the box answer `6A 05 01 62` on this car, and with the right values?
2. What does the box put on the car bus for a write: diagnostics to `(0x750, 0x40)` or
   broadcast frames?
3. Does the car type `0x21` or `0x9D` matter for body settings on a 2019?
4. Which way does the light sensor scale run, and what do the exterior timer indices mean?
5. What are the "smoke sensor", "ACC customization" and "vehicle recommendations" items?
   The Chinese source strings may explain them.
6. Do the relock time and "lock when door opened" items exist as undocumented box keys?
