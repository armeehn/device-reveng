# RAV4 XA50 (2019 Hybrid, TSS2) — Raw CAN Tap → Head-Unit Integration Plan

**Vehicle:** 2019 Toyota RAV4 **Hybrid**, XA50 / TNGA-K, Toyota Safety Sense 2.0 (TSS2), first XA50 model year.
**Goal:** physically tap the raw CAN bus (read-only) and bridge decoded frames into the rooted Android head unit (QCM6125, HiWorld TYF2 MCU) as a *separate data path* from the existing CANBOX serial digest.
**Confidence legend:** ✅ confirmed from Toyota RM / primary source · 🟡 inferred (mostly from GR-Yaris / other-year RAV4) · ❓ open, verify on the car.

Primary Toyota source used throughout: **Toyota RM3510U**, *2019 MY RAV4 HV, NETWORKING: CAN COMMUNICATION SYSTEM (for HV Model)* — the correct 2019-MY manual (RM…U), per the coordinator's constraint. Individual bus pages cited inline.

---

## 1. Bus topology (2019 RAV4 HV)

The car uses a **star-of-buses** architecture: a **Central Gateway ECU (a.k.a. Network Gateway ECU)** sits at the hub, and each CAN sub-bus terminates on its own pair of gateway terminals (`CAnH`/`CAnL`). High-speed buses run **500 kbps**; body/comfort multiplex runs slower (100–125 kbps — ❓ exact rate not printed on the pages read). Every high-speed bus is terminated by two 120 Ω resistors → **~60 Ω H-to-L** when measured across an idle bus.

| Bus | Gateway terminals | Speed | Confirmed member ECUs | Carries (for our purposes) | Conf. |
|-----|-------------------|-------|------------------------|----------------------------|-------|
| **Bus 1** (ADAS / TSS2) | CA1H = term 23, CA1L = term 8 | 500k ✅ | Central Gateway; **Forward Recognition Camera** (PCS); **Millimeter-Wave Radar**; Blind-Spot Monitor LH; Parking-Assist ECU (PVM); TV Camera; Clearance-Warning ECU | ADAS + the frames openpilot reads (speed/steer/ACC are re-broadcast here) | ✅ |
| **Bus 2** (powertrain / "V-bus") | CA2H, CA2L (on gateway); **Skid Control ECU = CA2H A42-35 / CA2L A42-34** | 500k ✅ | **Skid Control ECU (ABS/VSC)** is a dual-bus node bridging Bus 1↔Bus 2; engine/HV/e-CVT + EPS live here (🟡 exact membership not printed) | **speed, wheel speeds, steering, gear, brake, ACC, HV** | 🟡 |
| **Bus 5** (body / comfort) | CA5H = term 15, CA5L = term 16 | slow ✅ | Central Gateway; Headlight ECU LH+RH; Position-Control ECU; **Main Body ECU (Multiplex Network Body ECU)**; **Certification (Smart Key) ECU**; **A/C Amplifier**; **Multiplex Network Door ECU**; Combination Meter | **doors, locks, lights, blinkers, HVAC** | ✅ |
| Others (Bus 3/4/6/…) | — | mixed | Referenced by RM but not enumerated on pages read (telematics/DCM, etc.) | — | ❓ |

Sources: [RM3510U Bus 1 short-to-GND](https://estimateca.mymitchell.com/t3Portal/document/rm/RM3510U/xhtml/RM100000001FYH3.html?hashId=RM100000001FYH3) · [RM3510U Bus 5 short-to-GND](https://estimateca.mymitchell.com/t3Portal/document/rm/RM3510U/xhtml/RM100000001FYHN.html?hashId=RM100000001FYHN) · [RM3510U Skid Control ECU comm-stop (CA1/CA2, 54–69 Ω)](https://estimateca.mymitchell.com/t3Portal/document/rm/RM3510U/xhtml/RM100000001H5I6.html?hashId=RM100000001H5I6) · [RAV4World CAN wiring thread (paywalled/tollbit)](https://www.rav4world.com/threads/can-bus-wiring-diagram.332364/).

**Key structural fact:** the **Skid Control ECU** reports `CA1H/CA1L` **and** `CA2H/CA2L` with a measured **54–69 Ω** across each pair — i.e. it straddles two terminated 500 k buses. That makes the ABS/skid-control connector, and equally the **forward-camera connector on Bus 1**, the natural high-speed tap that carries the powertrain-relevant broadcast frames.

---

## 2. Where to physically tap — ranked options

### Option A ✅🟡 — Inline at the **forward-recognition camera** (Bus 1, behind rear-view mirror) — RECOMMENDED for powertrain signals
This is exactly where **openpilot's Toyota harness** taps a RAV4 TSS2: an inline connector at the forward camera exposes the 500 k ADAS bus, on which the gateway re-broadcasts `SPEED`, `WHEEL_SPEEDS`, `STEER_ANGLE`, `STEER_TORQUE`, `GEAR`, `PCM_CRUISE` — the full opendbc set.
- **Why:** proven, high-speed, read-only friendly, and it is the same bus family (Bus 1) the RM confirms carries the camera.
- **Wire it:** splice CAN-H/CAN-L off the camera harness (T-tap or back-probe; do **not** cut). Camera pinout: ❓ verify on car (openpilot harness maps it).
- **Confidence:** tap location ✅ (openpilot-proven); exact RAV4-HV camera pinout 🟡.
- Source: [openpilot port guide for Toyota](https://blog.comma.ai/openpilot-port-guide-for-toyota-models/), [openpilot Toyota fingerprints/harness](https://github.com/commaai/openpilot).

### Option B ✅ — At the **Smart Key / Certification ECU** (Bus 5, body/comfort) — RECOMMENDED for doors/lights/HVAC
Steve Fisk's RAV4 CAN RE tapped the Smart Key ECU directly and decoded the body bus. Note his write-up is a **2023 MY** RAV4 — frame IDs are stable across the XA50 run but **connector/pin numbers shifted**, so treat his G54/pin-13 detail as year-approximate.
- **Gets:** doors `0x620`, locks `0x626`/`0x638`, blinkers `0x614`, headlights `0x699`, HVAC `0x590` (CAN 500k on his 2023; RAV4 body multiplex on the 2019 is the slower Bus 5 — ❓ re-confirm bitrate with a scope/adapter auto-baud).
- **Confidence:** ECU-as-tap ✅; 2019 pinout 🟡 (year gap).
- Source: [Fisk CANalysis](https://pages.cs.wisc.edu/~fisk/personal/CANalysis/index.html).

### Option C ✅(present)/🟡(usefulness) — **OBD-II / DLC3** port, pins **6 (CAN-H) / 14 (CAN-L)**
Cheapest, non-invasive, factory connector. **But** on TSS2 Toyotas the DLC3 sits behind the central gateway and is largely **diagnostic/request-response**; many broadcast frames are **filtered or absent** vs. a bus tap. Expect to see some periodic frames but not the full opendbc stream that Options A/B give.
- **Verify what's live:** plug an adapter and just sniff — if you don't see `0xB4`/`0xAA`/`0x25` broadcasting at high rate, it's gatewayed and you need Option A.
- **Confidence:** pins ✅ (Toyota/Lexus DLC3 standard: 6=CANH, 14=CANL, 4/5=GND, 16=+B); data completeness 🟡.
- Sources: [Toyota/Lexus DLC pinout](https://pinoutguide.com/CarElectronics/Toyota_lexus_obd2_diagnostic_pinout.shtml), [RAV4 OBD threads](https://www.rav4world.com/threads/obd-ii-diagram.309172/).

### Option D 🟡❓ — Empty **gateway "H62"-analog** connector
The GR-Yaris community exploits an **empty 2-pin plug (brown = CAN-H, white = CAN-L)** in the RH footwell wired to the Bus-Buffer ECU (Bus 7); mating shell ≈ Toyota **6098-6662** on AliExpress. **No confirmed RAV4 XA50 equivalent yet.** The RAV4 central gateway lives up under the dash (driver side / behind lower trim ❓). Worth a look for a spare gateway connector, but unconfirmed — do not build the plan around it.
- **Confidence:** 🟡 inferred from GR-Yaris only; ❓ existence/location on RAV4.
- Source: [gr-zoo CAN RE thread](https://www.gr-zoo.com/threads/can-bus-reverse-engineering.7834/).

### Multimeter verification (any tap, key OFF, before connecting anything)
1. **Termination:** H↔L should read **~60 Ω** (two 120 Ω in parallel) on a high-speed bus. The RM's **54–69 Ω** spec at the skid-control connector confirms this is the healthy range. A body/comfort bus may read differently (single-terminated / higher).
2. **Idle voltage (key ON, engine off):** CAN-H and CAN-L both idle **~2.5 V** to GND (recessive); during traffic H rises toward ~3.5 V, L drops toward ~1.5 V.
3. **Identify H vs L:** H is the one that swings *up* from 2.5 V; on Toyota body harness H is often the brown/white-tracer, but **measure, don't assume**.

---

## 3. Hardware to log / bridge — options + recommended BOM

Two viable data paths into Android. **SocketCAN in-kernel is unlikely to work** on the QCM6125 stock Android-13 `4.14-perf` kernel: automotive Android kernels ship without `CONFIG_CAN`/`CAN_RAW`/`CAN_SLCAN`, and building/inserting matching modules against a vendor kernel is fragile. **Pragmatic path = a USB-serial *slcan* adapter parsed in userspace, or a BLE adapter.** ([SocketCAN/slcan background](https://python-can.readthedocs.io/en/stable/interfaces/socketcan.html), [kernel CAN docs](https://docs.kernel.org/networking/can.html).)

### Path 1 — USB **slcan** into the head unit (RECOMMENDED if a spare USB port exists)
- **Adapter:** CANable 2.0 / CANable Pro (or CANtact) flashed with **slcan (CDC-ACM)** firmware → enumerates as `/dev/ttyACM0`. ASCII slcan protocol (`t<id><len><data>\r`) is trivial to parse. ~$25–40.
  - Alt transceiver-only route: **Teensy 4.0 + SN65HVD230** (3.3 V transceiver) running an slcan/print sketch, or an **MCP2515+MCP2551** Arduino — cheaper but MCP2515 is known to wedge and need a watchdog reset ([RaceChrono DIY notes](https://github.com/timurrrr/RaceChronoDiyBleDevice)).
- **On Android (rooted):** read `/dev/ttyACM0` directly (root gives node access) with a small Kotlin/JNI serial reader, **or** use the Android **USB-host API + usb-serial-for-android** (no root needed for USB host). Parse slcan ASCII → frames in userspace. No kernel module required.
- **comma panda** is a strong alternative adapter: robust, Toyota-tuned, well-documented USB protocol with existing Java/Android consumers; RX-only is a config choice. Slightly pricier but the most turnkey for Toyota. ([openpilot/panda](https://github.com/commaai/openpilot)).

### Path 2 — **BLE** CAN (RECOMMENDED for a clean permanent install / no USB port)
- **ESP32 (S3 or C3) + SN65HVD230**, running **timurrrr/RaceChronoDiyBleDevice** or the ESP32-S3 fork. Reaches 120–200 Hz; BLE is the bottleneck, not CPU. ~$10–15. ([timurrrr repo](https://github.com/timurrrr/RaceChronoDiyBleDevice), [ESP32-S3 fork](https://github.com/kevenduchesneau/racechrono-diy-esp32)).
- The launcher consumes it as a **BLE GATT client**: subscribe to the device's CAN-frame characteristic (the RaceChrono DIY GATT protocol is documented — [protocol thread](https://racechrono.com/forum/discussion/1922/racechrono-diy-bluetooth-ble-protocol)); each notification = one CAN frame (11-bit ID + up to 8 bytes). Android BLE is first-class, no root needed.

### Power / mounting for a permanent install
- Tap **ACC-switched 12 V** via an add-a-fuse (fuse tap) in the driver/passenger fuse box so the logger powers down with the car (no battery drain). Feed a small **12 V→5 V buck** for the ESP32/CANable. Common ground to chassis.
- Mount the adapter behind the dash near the tap point; strain-relief the CAN splice; keep the stub as short as possible to avoid loading the bus.

### Recommended BOM (BLE build, cleanest permanent install)
| Item | Part | ~USD |
|------|------|------|
| MCU | ESP32-S3 devkit | 8 |
| CAN transceiver | SN65HVD230 breakout (3.3 V) | 2 |
| Power | 12 V→5 V buck (e.g. MP1584) | 3 |
| Fuse tap | Add-a-circuit (low-profile mini per RAV4 box) | 4 |
| Wiring | T-taps / back-probe, 22 AWG twisted pair, heat-shrink | 5 |
| (verify) | Cheap USB slcan (CANable) for bench + baud probing | 30 |

USB build swaps the ESP32/transceiver for a **CANable 2.0** (~$35) or **comma panda** (~$150) and drops BLE.

---

## 4. Software integration into the launcher

```
Raw CAN (Bus 1 camera tap  +  Bus 5 body tap)
        │  120Ω-terminated, 500k / slow
   [ transceiver ]
        │
   ┌────┴─────────────────────────┐
   │ ESP32 (BLE)  OR  CANable(slcan/USB) │
   └────┬─────────────────────────┘
        │  BLE GATT notify         │  /dev/ttyACM0 (slcan ASCII)
        ▼                          ▼
  Android CanReaderService (Kotlin, foreground service)
     - BLE: GATT client, parse RaceChrono-DIY frame char
     - USB: usb-serial-for-android OR root read of ttyACM, slcan parser
        │  emits {canId:Int, data:ByteArray, ts}
        ▼
  DbcDecoder  ← opendbc toyota_nodsu_hybrid_pt DBC (bit offset/len/scale/offset)
        │
        ▼
  SharedFlow<VehicleSignal>  →  launcher widgets (speed/gear/HVAC/doors/lights)
```

**The big win over the CANBOX:** these are **raw broadcast frames**, so they map **1:1 to opendbc message IDs and scales** — no reverse-engineering a vendor digest. Decode with **`toyota_nodsu_hybrid_pt_generated.dbc`** (hybrid variant — see §hybrid note). Concrete IDs verified from the DBC:

| Signal | ID (hex) | ID (dec) | DBC message | Bus | Notes |
|--------|----------|----------|-------------|-----|-------|
| Vehicle speed | **0xB4** | 180 | `SPEED` | 1/2 | |
| Wheel speeds ×4 | **0xAA** | 170 | `WHEEL_SPEEDS` | 1/2 | FR/FL/RR/RL |
| Steering angle | **0x25** | 37 | `STEER_ANGLE_SENSOR` | 1/2 | angle+fraction+rate |
| Steering torque | **0x260** | 608 | `STEER_TORQUE_SENSOR` | 1/2 | driver+EPS |
| EPS status | **0x262** | 610 | `EPS_STATUS` | 1/2 | |
| Brake | **0x226** | 550 | `BRAKE_MODULE` | 1/2 | hybrid also `BRAKE` 0xA6/166 |
| Cruise/ACC state | **0x1D2** | 466 | `PCM_CRUISE` | 1/2 | gas-released, standstill |
| ACC #2 | **0x1D3** | 467 | `PCM_CRUISE_2` | 1/2 | set speed |
| **Gear (HYBRID)** | **0x127** | 295 | `GEAR_PACKET` | 1/2 | **hybrid-specific ID** |
| Gear (gas, ✗ our car) | 0x3BC | 956 | `GEAR_PACKET` | — | gas-model only |
| **Gas pedal (HYBRID)** | **0x245** | 581 | `GAS_PEDAL` | 1/2 | **hybrid-specific ID** |
| Gas pedal (gas, ✗) | 0x2C1 | 705 | `GAS_PEDAL` | — | gas-model only |
| Engine RPM (gas) | 0x1C4 | 452 | `ENGINE_RPM` | — | **gas file only — see note** |
| Doors/belts | 0x620 | 1568 | (body) | 5 | Fisk |
| Locks | 0x626 / 0x638 | | (body) | 5 | Fisk |
| Blinkers | 0x614 | 1556 | (body) | 5 | Fisk |
| Headlights | 0x699 | 1689 | (body) | 5 | Fisk |
| HVAC | 0x590 | 1424 | (body) | 5 | Fisk |

Source DBCs: [`toyota_nodsu_hybrid_pt_generated.dbc`](https://github.com/BogGyver/opendbc/blob/tesla_unity_dev/toyota_nodsu_hybrid_pt_generated.dbc) and [`toyota_nodsu_pt_generated.dbc`](https://github.com/BogGyver/opendbc/blob/tesla_unity_dev/toyota_nodsu_pt_generated.dbc) (mirror of commaai/opendbc). Body IDs from [Fisk](https://pages.cs.wisc.edu/~fisk/personal/CANalysis/index.html).

> ### ⚠ Hybrid-specific signal note (per coordinator constraint)
> - **Gear** on the hybrid is `GEAR_PACKET` at **0x127 (295)**, *not* the gas model's 0x3BC (956). Use the hybrid ID and hybrid gear enum.
> - **Gas pedal** hybrid = **0x245 (581)**, not gas 0x2C1.
> - **Engine RPM:** the plain `ENGINE_RPM` 0x1C4 exists in the **gas** DBC; on the e-CVT hybrid, engine RPM is *not* a simple always-on broadcast — expect it via a hybrid/HV-specific frame (❓ confirm on car; the hybrid DBC does not expose a clean `ENGINE_RPM`). Treat "engine RPM" as low-confidence for the hybrid and prefer motor/road-speed derived values.
> - **SecOC:** Toyota's Secure On-board Communication authenticates **TX (injection)** frames only; it **does not affect passive RX** — read-only sniffing of these broadcast IDs is unaffected. ([optskug SecOC docs](https://github.com/optskug/docs)).

---

## 5. Risk / legal / safety

- **This is the user's own vehicle** — owner-side diagnostics/tinkering.
- **Read-only.** Do **not** transmit on the powertrain bus. Use RX-only firmware (ESP32 sketch in listen/silent mode; panda "silent"/no-output; CANable `slcan` without sending `t` frames). Ideally put the transceiver in **listen-only/silent mode** so it never ACKs, guaranteeing zero bus disturbance.
- **Never inject** on Bus 1/2 — spoofing speed/steer/ACC frames is genuinely dangerous while driving and could trip TSS2. Sniffing is safe; writing is not.
- **Bus-load / termination:** you are adding a *listener*, not a node — do **not** add a third 120 Ω terminator (that drops the bus to ~40 Ω and breaks comms). Keep the tap stub short. Splice with T-taps/back-probes; never cut the factory twisted pair.
- **Electrical:** fuse the 12 V feed; ACC-switched to avoid battery drain; common chassis ground; verify polarity/voltage before connecting the adapter.
- **Verify before trusting:** confirm ~60 Ω and ~2.5 V idle at the chosen tap; sniff first to confirm the expected IDs are actually broadcasting there before wiring anything permanent.

---

## 6. Open questions to resolve on the actual car
1. ❓ **Full bus map / numbering** — pull the RM3510U *CAN communication system* **line diagram** (system-diagram page) to enumerate Bus 2/3/4/6 membership and confirm which bus number the engine/**HV ECU**/**e-CVT transaxle**/**EPS** sit on (inferred Bus 2 here).
2. ❓ **Does the OBD-II DLC3 broadcast the full opendbc set, or is it gatewayed?** Sniff pins 6/14 first; if sparse, commit to the camera tap (Option A).
3. ❓ **Forward-camera connector pinout** on the 2019 RAV4 HV (cross-check with the comma Toyota harness mapping).
4. ❓ **2019 Smart Key ECU connector/pin** for the body tap (Fisk's G54/pin-13 is 2023 MY — re-map for 2019).
5. ❓ **Body-bus (Bus 5) bitrate** — confirm 100 vs 125 kbps with a scope or auto-baud adapter (Fisk saw 500k on the *main* bus of a 2023; the 2019 comfort multiplex is slower).
6. ❓ **Hybrid engine-RPM frame** — capture and identify the HV-specific frame if RPM display is desired (not cleanly in opendbc hybrid DBC).
7. ❓ **RAV4 empty gateway connector (H62 analog)** — inspect under-dash for a spare 2-pin CAN plug; unconfirmed.
