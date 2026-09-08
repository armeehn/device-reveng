package com.ripostelabs.carlauncher.carlib

/**
 * HiworldCanDecoder — pure-Kotlin decoder for the Toyota RAV4 (XA50) HiWorld **TYF2** CANBOX
 * serial stream, as broadcast to the head unit.
 *
 * Ground truth: reverse-engineered against the OEM parser
 * `com.szchoiceway.canbus2.model.vios.toyota.HiworldCanParseToyota` (the class that runs on the
 * vendor gateway) and a byte-variance capture taken with the car **parked, ignition on**. Each
 * opcode below cites which of those two the mapping came from.
 *
 * Two entry points, mirroring how bytes actually reach the launcher:
 *  - [decodeFrame] — a full framed `A5 5A A5 | LEN | OPCODE | PAYLOAD | C1 | C2` byte array
 *    (verifies the checksum, strips the header, dispatches on opcode).
 *  - [decodePayload] — a bare payload where the opcode is already known. This is the common
 *    path in the launcher: `MCU_MSG_CAN_ALL_INFO` delivers the payload already
 *    extracted as a `byte[]` extra (see `CanCapture.kt` / `CanFrame`).
 *
 * `p[i]` throughout == payload byte i, 0-based, i.e. the byte *after* the opcode. In the OEM
 * parser this is `bArr[i + 2]` (their `bArr[0]` = LEN, `bArr[1]` = OPCODE), so a citation of
 * "OEM bArr[4]" corresponds to `p[2]` here.
 *
 * Deliberately Android-free so it can be unit-tested off-device. No framework imports.
 */
object HiworldCanDecoder {

    /** Frame magic: `A5 5A A5` precedes every framed packet on the wire. */
    private val HEADER = intArrayOf(0xA5, 0x5A, 0xA5)

    // ---- Opcodes we decode (== the byte after LEN). See variance.txt for observed counts. ----
    private const val OP_BASIC_STATUS = 0x11   // key/SWC + doors + steering
    private const val OP_TRIP_INFO = 0x13      // vehicle information page (range/trip) + speed candidate p[0:1]
    private const val OP_SPEED = 0x17          // dedicated low-rate speed field (2026-08-29 drive); p[0:1] BE ×0.1 km/h
    private const val OP_RPM_GEAR_MIRROR = 0x1A // unparsed by OEM; RPM + gear raw found in capture
    private const val OP_HYBRID = 0x1F         // hybrid battery + energy flow
    private const val OP_VEHICLE_INFO = 0x32   // RPM / coolant (NOT road speed — see 2026-08-29 finding)
    private const val OP_SYS_EVENT = 0x71     // gateway system event; carries the reverse flag
    private const val OP_SIDE_CAMERA = 0x18   // OEM calls it LightInfo; on this car it drives the side cameras
    private const val OP_CLIMATE = 0x31       // full climate state (this car uses 0x31, not the generic one)
    private const val OP_RADAR = 0x41          // PDC ultrasonic front/rear
    private const val OP_TPMS = 0x48           // tyre pressures
    private const val OP_VERSION = 0xF0        // CANBOX firmware version ASCII

    /**
     * 0x32 p[4:5] "speed" scale. **NOT ROAD SPEED.** The 2026-08-29 drive capture (0→54.8 km/h vs
     * head-unit GPS) proved this field does not track road speed — the earlier 0x32 correlation was
     * an interpolation artifact across a GPS dropout. Kept only so the raw byte is still surfaced as
     * a diagnostic; do not use for a speedometer. The real speed lives in 0x17 / 0x13 (below).
     */
    const val SPEED_SCALE_KMH: Double = 1.0

    /**
     * 0x17 p[0:1] BE → km/h. Dedicated speed field found on the 2026-08-29 drive: raw 540 = 54.0 km/h
     * exactly, raw 350 ≈ 35 km/h. Scale ≈ 0.1 km/h/LSB but rests on **only 2 distinct levels** (0x17
     * updates ~once per 25–40 s), so it is a candidate, not a calibrated speedometer. A steady-cruise
     * capture holding several constant speeds is needed to confirm the scale and linearity.
     */
    const val SPEED_017_SCALE_KMH: Double = 0.1

    // Door bitfield in 0x11 p[4].
    //
    // THE VENDOR SWAPS BITS ON THE WAY IN, which is easy to miss and was missed once here.
    // OnHandleCanDoorInfoCmd passes incoming bit7 as sendDoorInfo's FIRST argument, and
    // sendDoorInfo writes its SECOND argument to bit7 of the byte DoorInfoWindow renders.
    // Net effect: incoming 6<->7 and 4<->5 are exchanged before the UI sees them. So the
    // driver's door, which DoorInfoWindow draws from 0x80, arrives here as 0x40.
    //
    // Confirmed in the car 2026-09-07: opening the driver's door was reported as the passenger
    // while this read 0x80. Do not "correct" it back by reading DoorInfoWindow alone.
    //
    // NOTE the raw CAN message is genuinely different: 0x4A5 byte 3 really does use 0x80 for the
    // driver, verified by actuation. The two layouts do NOT match, and assuming they did is what
    // caused the inversion. See RawCanDecoder.
    private const val DOOR_FRONT_LEFT = 0x40
    private const val DOOR_FRONT_RIGHT = 0x80
    private const val DOOR_REAR_RIGHT = 0x20
    private const val DOOR_REAR_LEFT = 0x10
    private const val DOOR_TAILGATE = 0x08
    private const val DOOR_HOOD = 0x04

    // 0x18 p[1]: the OEM's openRightCamera / openLeftCamera bits.
    private const val CAM_RIGHT = 0x80
    private const val CAM_LEFT = 0x40
    private const val CAM_LEFT_FORCE = 0x08

    // 0x71 p[0], from EventService.onCmdSysEvent. Only the bits the gateway names are decoded;
    // the rest stay in SysEvent.raw rather than being given invented meanings.
    private const val SYS_DISC = 0x80
    private const val SYS_USB = 0x40
    private const val SYS_REVERSE = 0x02

    /** OEM climate sentinels: the setpoint byte reads "LO"/"HI" rather than a temperature. */
    private const val TEMP_LO = 0xFE
    private const val TEMP_HI = 0xFF

    /** OEM: setpoint byte * 0.5 = degrees C. */
    private const val TEMP_SCALE_C = 0.5

    /** OEM sentinel: 0xFF in the coolant byte means "unsupported / no reading". */
    private const val COOLANT_SENTINEL = 0xFF

    /** OEM sentinel: 0xFE in a TPMS byte means "no reading" (see `addTpms`, `!= 254`). */
    private const val TPMS_SENTINEL = 0xFE

    /** 16-bit "no data" sentinel used by the OEM `computeValue` consumers (`!= 65535`). */
    private const val U16_SENTINEL = 0xFFFF

    // ---------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------

    /**
     * Decode a full framed packet: `A5 5A A5 | LEN | OPCODE | PAYLOAD[LEN] | C1 | C2`.
     *
     * LEN counts the payload only (excludes the opcode). Verifies C1 against [checkSum5AA5];
     * returns `null` on any structural or checksum failure. C2 is a trailing pad and is ignored.
     */
    fun decodeFrame(framed: ByteArray): CanSignal? {
        // Minimum: 3 header + LEN + OPCODE + 0 payload + C1 + C2 = 7 bytes.
        if (framed.size < 7) return null
        if (u(framed, 0) != HEADER[0] || u(framed, 1) != HEADER[1] || u(framed, 2) != HEADER[2]) {
            return null
        }
        val len = u(framed, 3)                 // payload length (excludes opcode)
        val opcode = u(framed, 4)
        // Bytes: [0..2]=header, [3]=LEN, [4]=OPCODE, [5 .. 5+len-1]=payload, [5+len]=C1, [6+len]=C2
        val payloadStart = 5
        val c1Index = payloadStart + len
        if (framed.size < c1Index + 1) return null   // need at least C1 (C2 optional/ignored)

        // Checksum spans LEN, OPCODE and the payload bytes = framed[3 .. c1Index-1].
        val expected = checkSum5AA5(framed, 3, c1Index)
        if (expected != u(framed, c1Index)) return null

        val payload = framed.copyOfRange(payloadStart, payloadStart + len)
        return decodePayload(opcode, payload)
    }

    /**
     * Decode a bare [payload] (opcode already known) — the launcher's broadcast path. Never
     * throws on short payloads: fields that fall off the end are reported null / raw-0 rather
     * than crashing the parse of an otherwise-usable frame.
     */
    fun decodePayload(opcode: Int, payload: ByteArray): CanSignal = when (opcode) {
        OP_VEHICLE_INFO -> decodeVehicleInfo(payload)
        OP_HYBRID -> decodeHybrid(payload)
        OP_BASIC_STATUS -> decodeBasicStatus(payload)
        OP_TPMS -> decodeTpms(payload)
        OP_SYS_EVENT -> decodeSysEvent(payload)
        OP_SIDE_CAMERA -> decodeSideCamera(payload)
        OP_CLIMATE -> decodeClimate(payload)
        OP_RADAR -> decodeRadar(payload)
        OP_TRIP_INFO -> decodeTripInfo(payload)
        OP_SPEED -> decodeSpeed(payload)
        OP_RPM_GEAR_MIRROR -> decodeRpmGearMirror(payload)
        OP_VERSION -> decodeVersion(payload)
        else -> CanSignal.Unknown(opcode, payload)
    }

    // ---------------------------------------------------------------------------------------
    // Per-opcode decoders
    // ---------------------------------------------------------------------------------------

    /**
     * 0x32 Vehicle Info — RPM / speed / coolant.
     *
     * OEM `OnHandleCanVehicleInfoCmd`: `byEngineSpeedH = bArr[4]`, `byEngineSpeedL = bArr[5]`
     * ⇒ RPM = (p[2]<<8)|p[3]; `iCarSpeed = computeValue(bArr[7], bArr[6])` ⇒ raw speed =
     * (p[4]<<8)|p[5]; coolant `bArr[11]` ⇒ p[9], value − 40 °C, 0xFF ⇒ unsupported.
     * Variance: b2/b3 carry RPM (b3 up to 0xE8), b9 static 0xFF while parked.
     */
    private fun decodeVehicleInfo(p: ByteArray): CanSignal.VehicleInfo {
        val rpm = u16be(p, 2, 3)
        val speedRaw = u16be(p, 4, 5)
        val coolantByte = u(p, 9)
        val coolantC = if (coolantByte == COOLANT_SENTINEL) null else coolantByte - 40
        return CanSignal.VehicleInfo(
            rpm = rpm,
            speedRaw = speedRaw,
            speedKmh = speedRaw * SPEED_SCALE_KMH,
            coolantC = coolantC,
        )
    }

    /**
     * 0x1F Hybrid — battery state + power-flow bitfield.
     *
     * OEM `OnHandleHybridInfoCmd`: present = `(bArr[2]>>7)&1` ⇒ p[0] bit7; batteryLevel =
     * `bArr[2] & 15` ⇒ p[0] low nibble. The energy-flow byte is `bArr[3]` ⇒ p[1], split into
     * eight direction bits (bit0..bit7) below. Variance (len=2): p[0] ∈ {0x83,0x84} (present +
     * SoC 3/4), p[1] ∈ {0x00,0x04,0x05}.
     */
    private fun decodeHybrid(p: ByteArray): CanSignal.Hybrid {
        val b0 = u(p, 0)
        val flow = u(p, 1)
        return CanSignal.Hybrid(
            present = (b0 and 0x80) != 0,
            batteryLevel = b0 and 0x0F,
            energyFlowRaw = flow,
            motorDriveBattery = (flow and 0x01) != 0,   // bit0
            motorDriveWheels = (flow and 0x02) != 0,    // bit1
            engineDriveMotor = (flow and 0x04) != 0,    // bit2
            engineDriveWheels = (flow and 0x08) != 0,   // bit3
            batteryDriveMotor = (flow and 0x10) != 0,   // bit4
            wheelDriveMotor = (flow and 0x20) != 0,     // bit5
            batteryDriveWheels = (flow and 0x40) != 0,  // bit6
            wheelsDriveBattery = (flow and 0x80) != 0,  // bit7
        )
    }

    /**
     * 0x11 Basic Status — SWC key, doors, steering angle.
     *
     * OEM `OnHandleCanBasicStatusCmd` fans out to three handlers:
     *  - `OnHandleCanKeyCmd`: button id `bArr[4]` ⇒ p[2]; pressed flag `bArr[5]` ⇒ p[3] (!=0).
     *  - `OnHandleCanDoorInfoCmd(bArr[6])` ⇒ door bitfield p[4]; the driver/front-left door is
     *    bit6 (0x40). The remaining door bits (7,5,4,3,2) were all 0 in the parked capture, so
     *    they are left as documented TODOs rather than guessed.
     *  - `OnHandleCanWheelTrackCmd`: raw = `(bArr[8]<<8)|bArr[9]` ⇒ (p[6]<<8)|p[7]; the OEM
     *    divides by 14 and sign-extends via bit15 (0x8000) ⇒ steerAngle = signed(raw)/14.0 deg.
     * Variance: p[2] ∈ {0,1,2}, p[3] ∈ {0,1}, p[4] ∈ {0,0x40}.
     */
    /**
     * What a steering-wheel button means, recovered from the OEM's own OnHandleCanKeyCmd on
     * 2026-09-07. The raw id alone is not useful: several ids map to the same action because two
     * physical controls share it, and one is context dependent.
     */
    enum class SwcAction {
        VOLUME_UP, VOLUME_DOWN, MUTE, VOICE, CALL, HANGUP, PREV, NEXT, MODE, PLAY_PAUSE, BACK, UNKNOWN
    }

    /**
     * Map a raw SWC button id to its action.
     *
     * Ids 13 and 14 duplicate 8 and 9 because two physical controls are wired to the same action.
     * Id 5 is CONTEXT DEPENDENT in the OEM: it hangs up during a call and otherwise starts one.
     * We report [SwcAction.CALL] and leave the call-state decision to the caller, which knows
     * whether a call is up; guessing here would be wrong half the time.
     * Ids 7, 10 and 11 are unhandled by this car's parser and come back [SwcAction.UNKNOWN].
     */
    fun swcAction(buttonId: Int): SwcAction = when (buttonId) {
        1 -> SwcAction.VOLUME_UP
        2 -> SwcAction.VOLUME_DOWN
        3 -> SwcAction.MUTE
        4 -> SwcAction.VOICE
        5 -> SwcAction.CALL
        6 -> SwcAction.HANGUP
        // Observed in the car 2026-09-07: these are the other way round in practice. The OEM
        // maps 8/13 to its MCU_KEY_PREV constant and 9/14 to MCU_KEY_NEXT, so either those
        // constants are named backwards in the vendor source or something downstream inverts
        // them. The car is the authority, not the decompile.
        8, 13 -> SwcAction.NEXT
        9, 14 -> SwcAction.PREV
        12 -> SwcAction.MODE
        15 -> SwcAction.PLAY_PAUSE
        16 -> SwcAction.BACK
        else -> SwcAction.UNKNOWN
    }

    private fun decodeBasicStatus(p: ByteArray): CanSignal.BasicStatus {
        val doorBits = u(p, 4)
        val raw = u16be(p, 6, 7)
        val signed = if (raw and 0x8000 != 0) raw - 0x10000 else raw
        return CanSignal.BasicStatus(
            swcButtonId = u(p, 2),
            swcPressed = u(p, 3) != 0,
            swcAction = swcAction(u(p, 2)),
            doorBits = doorBits,
            doorFrontLeftOpen = (doorBits and DOOR_FRONT_LEFT) != 0,
            doorFrontRightOpen = (doorBits and DOOR_FRONT_RIGHT) != 0,
            doorRearRightOpen = (doorBits and DOOR_REAR_RIGHT) != 0,
            doorRearLeftOpen = (doorBits and DOOR_REAR_LEFT) != 0,
            tailgateOpen = (doorBits and DOOR_TAILGATE) != 0,
            hoodOpen = (doorBits and DOOR_HOOD) != 0,
            steerAngleDeg = signed / 14.0,
        )
    }

    /**
     * 0x71 system event — the digest's own reverse flag.
     *
     * Traced through the vendor gateway rather than the car parser, which is why it is absent from
     * `HiworldCanParseToyota`: `EventService.processCmd` reads the opcode from `bArr[0]` and passes
     * the whole frame on, so its `bArr[1]` is this decoder's `payload[0]`. That offset was derived
     * hop by hop, not assumed — a wrong assumption about exactly this cost two inverted signals
     * earlier in the same session.
     *
     * `onCmdSysEvent` computes reverse as `(bArr[1] & 2) > 0 && mAccOpenState`.
     *
     * **[reverseRaw] is the bit ALONE, without the ACC gate**, because this decoder has no view of
     * accessory state. The gateway also suppresses the transition entirely while its
     * `mAppBackcarEnable` flag is set, i.e. when an app asked for the camera itself. A caller that
     * wants to match the vendor's own behaviour must AND with ACC; a caller that wants to know what
     * the car said should use the raw bit.
     *
     * NOT actuation-verified. The reverse camera was physically disconnected on the day this was
     * written, so nothing here has been seen to move on a real vehicle.
     */
    private fun decodeSysEvent(p: ByteArray): CanSignal.SysEvent {
        val b = u(p, 0)
        return CanSignal.SysEvent(
            reverseRaw = (b and SYS_REVERSE) != 0,
            discPresent = (b and SYS_DISC) != 0,
            usbPresent = (b and SYS_USB) != 0,
            raw = b,
        )
    }

    /**
     * 0x18 side-camera request.
     *
     * The OEM names this opcode LightInfo and its handler touches no lamps at all: it reads three
     * bits of p[1] and calls openRightCamera / openLeftCamera. On this car those cameras are
     * triggered by the indicators, so this doubles as the only indicator state the head unit sees.
     *
     * That matters because indicators are NOT on the raw CAN bus - three separate actuation hunts
     * on 2026-09-07 found nothing, including with the car in READY. They most likely reach the
     * decoder over its IEBUS/AVC-LAN pins, which CAN hardware cannot read. This opcode is the only
     * route to them.
     *
     * Bit 3 forces the left camera on independently of bit 6, so it is surfaced separately rather
     * than folded in: a caller that wants "is the left view wanted" should use [left], while
     * [leftForced] distinguishes the override for anyone mapping this back to indicator state.
     */
    private fun decodeSideCamera(p: ByteArray): CanSignal.SideCamera {
        val b = u(p, 1)
        val forced = (b and CAM_LEFT_FORCE) != 0
        return CanSignal.SideCamera(
            right = (b and CAM_RIGHT) != 0,
            left = (b and CAM_LEFT) != 0 || forced,
            leftForced = forced,
        )
    }

    /**
     * 0x31 climate.
     *
     * **UNVERIFIED AGAINST A REAL VEHICLE.** Every field below was read out of the decompiled
     * vendor parser and unit-tested against synthetic payloads. No frame from an actual car has
     * ever been checked against it, and the first person to look at the readout reported that it
     * does not track what they press.
     *
     * Two reasons to distrust it until a capture says otherwise:
     *  - The decompile proves how the OEM app PARSES bytes. It does not prove those bytes arrive,
     *    nor that this vehicle's CANBOX uses this opcode.
     *  - The OEM's own climate screen reportedly never worked on this unit. If their display was
     *    broken, decoding their parser may be decoding a message that is never sent.
     *
     * To settle it: logcat the unit while changing one climate control, see which opcode actually
     * moves, and confirm the byte that changes. Until then treat the readout as a hypothesis.
     *
     * Nothing drives a control from this — it feeds the CAN capture diagnostic screen only, so a
     * wrong layout shows bad numbers on a debug page and costs nothing else.
     *
     * **Believed to be the climate message on this car rather than the generic 0x2x one.** The OEM's generic
     * OnHandleCanAirCmd returns immediately when its mHas31ClimateData flag is set, which it is
     * here, so the generic handler's byte layout is dead code on this vehicle. Reading it instead
     * produces a plausible but entirely wrong decode. Layout below is from
     * HiworldCanParseToyota.OnHandleCanAirCmdVertical.
     *
     * Payload indices are OEM bArr minus 2 (bArr[2] is p[0]).
     *
     * Temperature: raw * 0.5 degrees C, with 0xFE meaning "LO" and 0xFF "HI" rather than a value.
     * In Fahrenheit mode the OEM divides the same raw byte by 2 instead; [tempUnitCelsius] says
     * which, so callers can render without re-deriving it.
     *
     * [fanStep] is the DISPLAYED step (0-7 plus off) and is the number to show a user. The raw-bus
     * blower byte (0x4AD) is a duty-like value that does not map onto those steps - see
     * can-integration/docs/VEHICLE_SIGNALS_2019.md.
     */
    private fun decodeClimate(p: ByteArray): CanSignal.Climate {
        val b0 = u(p, 0)
        val b1 = u(p, 1)
        val b2 = u(p, 2)
        val b3 = u(p, 3)
        return CanSignal.Climate(
            on = (b0 and 0x40) != 0,
            acMax = (b0 and 0x20) != 0,
            auto = (b0 and 0x08) != 0,
            // The OEM tests this bit for ZERO, not one. Inverted on purpose, not a typo.
            dual = (b0 and 0x04) == 0,
            tempUnitCelsius = (b0 and 0x01) == 0,
            acOn = (b1 and 0x40) != 0,
            recirculate = (b1 and 0x10) != 0,
            eco = (b1 and 0x02) != 0,
            airPurifier = (b1 and 0x01) != 0,
            rearDefog = (b2 and 0x40) != 0,
            maxFront = (b2 and 0x10) != 0,
            seatHeatRight = (b2 shr 2) and 0x03,
            seatHeatLeft = b2 and 0x03,
            seatCoolRight = (b3 shr 6) and 0x03,
            seatCoolLeft = (b3 shr 4) and 0x03,
            ventDirectionRaw = u(p, 4),
            fanStep = u(p, 5) and 0x0F,
            leftTempC = tempC(u(p, 6)),
            rightTempC = tempC(u(p, 7)),
            rearFanStep = u(p, 9) and 0x0F,
        )
    }

    /** Climate setpoint byte to degrees C; null for the LO/HI sentinels, which are not values. */
    private fun tempC(raw: Int): Double? =
        if (raw == TEMP_LO || raw == TEMP_HI) null else raw * TEMP_SCALE_C

    /**
     * 0x48 TPMS — five tyre pressures.
     *
     * OEM `OnHandleCanTpmsInfoCmd` → `addTpms`: pressure = firstByte + secondByte where the
     * pairs are FL=(bArr[4],bArr[9]), FR=(bArr[5],bArr[10]), RL=(bArr[6],bArr[11]),
     * RR=(bArr[7],bArr[12]), spare=(bArr[8],bArr[13]) ⇒ in payload terms p[i] + p[i+5] for
     * i = FL:2, FR:3, RL:4, RR:5, spare:6. A byte of 0xFE ⇒ no reading (OEM tests `!= 254` on
     * the first byte; we null if *either* byte is the sentinel). Units: kPa.
     * Variance: p[2..6] all 0xFE while parked (sensors asleep) ⇒ all null.
     * Sentinel is the FIRST byte of each pair only — see [tpms].
     */
    private fun decodeTpms(p: ByteArray): CanSignal.Tpms = CanSignal.Tpms(
        frontLeftKpa = tpms(p, 2),
        frontRightKpa = tpms(p, 3),
        rearLeftKpa = tpms(p, 4),
        rearRightKpa = tpms(p, 5),
        spareKpa = tpms(p, 6),
    )

    /**
     * One wheel's pressure, as the OEM computes it: `p[i] + p[i+5]`, in kPa.
     *
     * **The sentinel is tested on the FIRST byte only**, which is what `addTpms` does:
     * `if (i2 != 254)` where `i2` is that first byte. Testing both bytes — which this used to do —
     * discards readings the vendor would happily display, because 0xFE is a perfectly ordinary
     * value for the second byte. A pair of (0, 254) is 254 kPa, about 37 psi, an unremarkable
     * tyre. Being stricter than the source here is not caution, it is a wrong answer.
     */
    private fun tpms(p: ByteArray, i: Int): Int? {
        val first = u(p, i)
        if (first == TPMS_SENTINEL) {
            return null
        }
        return first + u(p, i + 5)
    }

    /**
     * 0x41 PDC — ultrasonic parking radar, rear + front.
     *
     * OEM `OnHandleCanFrontRearRadarInfoCmd`: rear sensors `bArr[2..5]` ⇒ p[0..3], front
     * `bArr[6..9]` ⇒ p[4..7]. Each byte 1..5 is a proximity step ⇒ distance = value × 30 cm;
     * anything else (incl. 0) is "no object" ⇒ null. Variance: all zero while parked.
     */
    private fun decodeRadar(p: ByteArray): CanSignal.ParkingRadar = CanSignal.ParkingRadar(
        rearCm = IntArray(4) { 0 }.let { List(4) { radar(u(p, it)) } },
        frontCm = List(4) { radar(u(p, it + 4)) },
    )

    private fun radar(v: Int): Int? = if (v in 1..5) v * 30 else null

    /**
     * 0x13 Vehicle Information Page — driving range / trip.
     *
     * NOTE ON INDEXING: the task brief listed `rangeToEmptyKm = (p[4] | (p[5]<<8))` little-endian,
     * but that contradicts both the OEM parser and the variance capture — p[4]/p[5] are static 0.
     * The OEM `OnHandleCanVehicleInformationPageCmd` reads range mileage as
     * `computeValue(bArr[5], bArr[4])`, and `computeValue(low, high) = (high<<8)|low`, i.e.
     * `(bArr[4]<<8)|bArr[5]` ⇒ **(p[2]<<8)|p[3] big-endian**. Variance confirms it: p[2]=0x01
     * (static), p[3] ∈ {0x2C,0x2D,0x2E} ⇒ 0x012C…0x012E = 300..302 km, the observed ~300. We
     * therefore follow the parser (authoritative) and expose the range from p[2]/p[3]. 0xFFFF ⇒
     * no data ⇒ null. Other page fields (trip fuel, optimal economy, elapsed time, avg speed)
     * are present in the OEM parser but left out here as not-yet-needed.
     */
    private fun decodeTripInfo(p: ByteArray): CanSignal.TripInfo {
        val raw = u16be(p, 2, 3)
        return CanSignal.TripInfo(
            rangeToEmptyKm = if (raw == U16_SENTINEL) null else raw,
            // 2026-08-29: p[0:1] is a ~10 Hz value that tracks the speed profile up and down
            // (R²≈0.66, capped by 1 Hz GPS lag). Best *live* speed candidate; scale UNCONFIRMED.
            speedCandidateRaw = u16be(p, 0, 1),
        )
    }

    /**
     * 0x17 — dedicated speed field (2026-08-29 drive). p[0:1] BE × [SPEED_017_SCALE_KMH]. Accurate
     * (raw 540 = 54.0 km/h) but low-rate (~once per 25–40 s) and the scale rests on 2 points.
     */
    private fun decodeSpeed(p: ByteArray): CanSignal.SpeedCandidate {
        val raw = u16be(p, 0, 1)
        return CanSignal.SpeedCandidate(
            source = "0x17",
            raw = raw,
            kmh = raw * SPEED_017_SCALE_KMH,
        )
    }

    /**
     * 0x1A — NOT parsed by the OEM app; mapping recovered from the variance capture.
     *
     * rpmMirror = (p[9]<<8)|p[10] — tracks 0x32's RPM byte-for-byte in the capture (both p[9]/p[10]
     * here and p[2]/p[3] on 0x32 share the identical value set 0x00..0x05 / 0x00..0xE8), which is
     * how the mapping was found.
     *
     * Gear: p[5] is the PRNDL code — mapped from a 2026-08-29 drive capture through all four gears:
     * 0=Drive, 1=Park, 2=Neutral, 3=Reverse. p[1] is coarse (0x03 in Reverse, else 0x01) and kept
     * only as a corroborating diagnostic. The parked variance (p[5] ∈ {1,3}) fits: D and N cannot
     * occur while parked-testing, so they only surfaced once the car was driven.
     */
    private fun decodeRpmGearMirror(p: ByteArray): CanSignal.RpmGearMirror = CanSignal.RpmGearMirror(
        rpmMirror = u16be(p, 9, 10),
        gear = gearFromCode(u(p, 5)),
        gearRawB1 = u(p, 1),
        gearRawB5 = u(p, 5),
    )

    /** Map the 0x1A p[5] gear code to [Gear]; anything outside the mapped set is [Gear.UNKNOWN]. */
    private fun gearFromCode(code: Int): Gear = when (code) {
        0 -> Gear.DRIVE
        1 -> Gear.PARK
        2 -> Gear.NEUTRAL
        3 -> Gear.REVERSE
        else -> Gear.UNKNOWN
    }

    /**
     * 0xF0 Version — CANBOX firmware string as ASCII.
     *
     * OEM `OnHandleCanVersionCmd`: `new String(bArr, 2, bArr.length - 3)` — i.e. the payload
     * bytes, ASCII. Variance decoded to e.g. "H1H2TYF23A-240904". Non-printable trailing bytes
     * are trimmed defensively.
     */
    private fun decodeVersion(p: ByteArray): CanSignal.Version {
        val text = buildString {
            for (b in p) {
                val c = b.toInt() and 0xFF
                if (c == 0) break            // NUL-terminate
                append(c.toChar())
            }
        }.trim()
        return CanSignal.Version(text)
    }

    // ---------------------------------------------------------------------------------------
    // Byte / checksum helpers (Kotlin bytes are signed; these keep everything unsigned)
    // ---------------------------------------------------------------------------------------

    /** Unsigned byte at [i], or 0 if [i] is out of range (short-frame tolerant). */
    private fun u(a: ByteArray, i: Int): Int = if (i in a.indices) a[i].toInt() and 0xFF else 0

    /** Big-endian 16-bit from payload bytes [hi] (high) and [lo] (low). */
    private fun u16be(a: ByteArray, hi: Int, lo: Int): Int = (u(a, hi) shl 8) or u(a, lo)

    /**
     * Checksum matching the OEM `SendCmdLstToCanbus5AA5Header` / `checkSum5AA5`:
     * `C1 = (sum(bytes[from until until]) - 1) & 0xFF`, summed over LEN + OPCODE + PAYLOAD.
     * (OEM: `for i in 2..len-2: b += bArr2[i]; bArr2[len-1] = (byte)((b - 1) & 0xFF)`.)
     */
    fun checkSum5AA5(bytes: ByteArray, from: Int, until: Int): Int {
        var sum = 0
        for (i in from until until) sum += u(bytes, i)
        return (sum - 1) and 0xFF
    }

    // ---------------------------------------------------------------------------------------
    // Self-test (pure Kotlin, no test framework) — feeds a real observed 0x32 frame.
    // ---------------------------------------------------------------------------------------

    /**
     * Standalone sanity check using the real parked-capture 0x32 frame
     * `A5 5A A5 0E 32 | 00 00 05 14 00 00 00 00 00 FF 00 00 00 00 | C1 C2`
     * (payload values from variance.txt: RPM hi/lo = 0x05/0x14 = 1300, coolant = 0xFF ⇒ null).
     * C1 = (sum(0x0E,0x32,payload) − 1) & 0xFF = 0x57. Returns true on success.
     */
    fun selfTest(): Boolean {
        val payload = intArrayOf(
            0x00, 0x00, 0x05, 0x14, 0x00, 0x00, 0x00, 0x00, 0x00, 0xFF, 0x00, 0x00, 0x00, 0x00,
        ).map { it.toByte() }.toByteArray()

        // Build the framed packet and compute its checksum the same way the CANBOX would.
        val len = payload.size                       // 14 = 0x0E
        val opcode = OP_VEHICLE_INFO                  // 0x32
        val body = ByteArray(2 + len)
        body[0] = len.toByte()
        body[1] = opcode.toByte()
        payload.copyInto(body, 2)
        val c1 = checkSum5AA5(body, 0, body.size)     // spans LEN + OPCODE + PAYLOAD

        val framed = ByteArray(3 + body.size + 2)
        framed[0] = 0xA5.toByte(); framed[1] = 0x5A.toByte(); framed[2] = 0xA5.toByte()
        body.copyInto(framed, 3)
        framed[3 + body.size] = c1.toByte()
        framed[3 + body.size + 1] = 0x00              // C2 pad (ignored)

        val viaFrame = decodeFrame(framed)
        val viaPayload = decodePayload(opcode, payload)

        val okFrame = viaFrame is CanSignal.VehicleInfo &&
            viaFrame.rpm == 1300 &&
            viaFrame.speedRaw == 0 &&
            viaFrame.coolantC == null
        val okPayload = viaPayload is CanSignal.VehicleInfo && viaPayload.rpm == 1300
        val okChecksum = c1 == 0x57
        val okBadChecksum = run {
            val bad = framed.copyOf(); bad[3 + body.size] = (c1 xor 0xFF).toByte()
            decodeFrame(bad) == null
        }
        return okFrame && okPayload && okChecksum && okBadChecksum
    }
}

/**
 * Decoded result model. `Unknown` is the catch-all for opcodes we see on the wire but do not
 * (yet) interpret — it keeps the raw payload so a caller can still log / capture it.
 */
/**
 * PRNDL gear select, decoded from the 0x1A gear byte (p[5]). UNKNOWN covers any code outside
 * the mapped set (e.g. a transitional value), so consumers never see a wrong gear.
 */
enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

sealed interface CanSignal {

    /** 0x32 — engine RPM, raw speed (+ km/h via [HiworldCanDecoder.SPEED_SCALE_KMH]), coolant. */
    data class VehicleInfo(
        val rpm: Int,
        val speedRaw: Int,
        /** speedRaw × SPEED_SCALE_KMH; scale UNCONFIRMED (parked capture). */
        val speedKmh: Double,
        /** °C, or null when the MCU reports 0xFF (unsupported / warming up). */
        val coolantC: Int?,
    ) : CanSignal

    /** 0x1F — hybrid battery state and power-flow direction bits. */
    data class Hybrid(
        val present: Boolean,
        /** 0..15 state-of-charge bar count (p[0] low nibble). */
        val batteryLevel: Int,
        /** Raw energy-flow bitfield p[1]; individual bits decoded below. */
        val energyFlowRaw: Int,
        val motorDriveBattery: Boolean,
        val motorDriveWheels: Boolean,
        val engineDriveMotor: Boolean,
        val engineDriveWheels: Boolean,
        val batteryDriveMotor: Boolean,
        val wheelDriveMotor: Boolean,
        val batteryDriveWheels: Boolean,
        val wheelsDriveBattery: Boolean,
    ) : CanSignal

    /** 0x11 — steering-wheel-control key, door bitfield, steering angle. */
    data class BasicStatus(
        val swcButtonId: Int,
        val swcPressed: Boolean,
        /** [swcButtonId] resolved to a named action; UNKNOWN for ids this car does not use. */
        val swcAction: HiworldCanDecoder.SwcAction,
        /** Raw door bitfield p[4] (the OEM's bArr[6]). */
        val doorBits: Int,
        /**
         * bit7 (0x80): driver / front-left door open.
         *
         * **Was bit6 (0x40), and that was wrong** - 0x40 is the FRONT RIGHT door, so this flag
         * reported the passenger door as the driver's. Two independent sources agree on 0x80:
         * the vendor's own DoorInfoWindow.setDoorData maps (i and 128) to the front-left image,
         * and a 2026-09-07 actuation capture on the raw bus (0x4A5 byte 3, same bit layout) had
         * bit 0x80 set for 96% of a run with only the driver's door open, and 6% of a run with
         * only the passenger's.
         */
        val doorFrontLeftOpen: Boolean,
        /** bit6 (0x40): front-right / passenger door open. */
        val doorFrontRightOpen: Boolean,
        /** bit5 (0x20): rear right door. Swaps with rear-left on RHD (OEM bRearDoorSet). */
        val doorRearRightOpen: Boolean,
        /** bit4 (0x10): rear left door open. */
        val doorRearLeftOpen: Boolean,
        /** bit3 (0x08): tailgate open. */
        val tailgateOpen: Boolean,
        /** bit2 (0x04): bonnet open. From the vendor UI mapping; never actuated to confirm. */
        val hoodOpen: Boolean,
        /** Degrees; positive/negative per steering direction. Scale = raw/14 (OEM). */
        val steerAngleDeg: Double,
    ) : CanSignal

    /**
     * 0x71 — gateway system event. [reverseRaw] is the reverse bit WITHOUT the vendor's ACC gate;
     * see the decoder for why, and [raw] carries the bits nobody has named yet.
     */
    data class SysEvent(
        val reverseRaw: Boolean,
        val discPresent: Boolean,
        val usbPresent: Boolean,
        val raw: Int,
    ) : CanSignal

    /**
     * 0x18 — which side camera the car is asking for. Also the only indicator state available,
     * since the indicators are not present on the raw CAN bus.
     */
    data class SideCamera(
        val left: Boolean,
        val right: Boolean,
        /** p[1] bit3, which forces the left view on regardless of bit6. */
        val leftForced: Boolean,
    ) : CanSignal

    /** 0x31 — full climate state. [fanStep] is the displayed step; temps are null when LO/HI. */
    data class Climate(
        val on: Boolean,
        val acOn: Boolean,
        val acMax: Boolean,
        val auto: Boolean,
        val dual: Boolean,
        val eco: Boolean,
        val recirculate: Boolean,
        val airPurifier: Boolean,
        val rearDefog: Boolean,
        val maxFront: Boolean,
        val maxFrontDefrost: Boolean = maxFront,
        /** 0-3, off to high. */
        val seatHeatLeft: Int,
        val seatHeatRight: Int,
        /** 0-3, off to high. Ventilated seats. */
        val seatCoolLeft: Int,
        val seatCoolRight: Int,
        /** OEM enum: 1 face, 5 level+foot, 12 head+foot, 13 head+level. Kept raw, unmapped. */
        val ventDirectionRaw: Int,
        /** 0-7 front blower, 0 = off. */
        val fanStep: Int,
        val rearFanStep: Int,
        /** Null when the display reads LO or HI rather than a number. */
        val leftTempC: Double?,
        val rightTempC: Double?,
        val tempUnitCelsius: Boolean,
    ) : CanSignal

    /** 0x48 — tyre pressures in kPa; null = no reading (0xFE sentinel). */
    data class Tpms(
        val frontLeftKpa: Int?,
        val frontRightKpa: Int?,
        val rearLeftKpa: Int?,
        val rearRightKpa: Int?,
        val spareKpa: Int?,
    ) : CanSignal

    /**
     * 0x41 — PDC ultrasonic distances in cm; null = no object at that sensor.
     * Lists are left→right as the OEM iterates them; each is 4 sensors.
     */
    data class ParkingRadar(
        val rearCm: List<Int?>,
        val frontCm: List<Int?>,
    ) : CanSignal

    /** 0x13 — driving range to empty (km; null = no data) + the ~10 Hz raw speed candidate p[0:1]. */
    data class TripInfo(
        val rangeToEmptyKm: Int?,
        /** p[0:1] BE — live speed candidate (2026-08-29); tracks the profile, scale UNCONFIRMED. */
        val speedCandidateRaw: Int = 0,
    ) : CanSignal

    /**
     * 0x17 — dedicated speed field (2026-08-29 drive). Accurate scale (~0.1 km/h/LSB) but low-rate
     * and 2-point; a candidate speedometer, not yet calibrated. [kmh] = [raw] × SPEED_017_SCALE_KMH.
     */
    data class SpeedCandidate(
        val source: String,
        val raw: Int,
        val kmh: Double,
    ) : CanSignal

    /**
     * 0x1A — RPM mirror + PRNDL gear.
     *
     * Gear mapping recovered from a 2026-08-29 drive capture through P/R/N/D: p[5] is the
     * definitive gear code (0=D, 1=P, 2=N, 3=R); p[1] is coarse (0x03 in Reverse, else 0x01).
     * Raw bytes are kept for diagnostics; [gear] is the resolved value.
     */
    data class RpmGearMirror(
        val rpmMirror: Int,
        val gear: Gear,
        val gearRawB1: Int,
        val gearRawB5: Int,
    ) : CanSignal

    /** 0xF0 — CANBOX firmware version string (ASCII). */
    data class Version(
        val text: String,
    ) : CanSignal

    /** Fallback for an opcode we don't interpret yet; raw payload preserved. */
    data class Unknown(
        val opcode: Int,
        val payload: ByteArray,
    ) : CanSignal {
        // ByteArray needs value-based equals/hashCode for a data class to behave.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Unknown) return false
            return opcode == other.opcode && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = 31 * opcode + payload.contentHashCode()
    }
}
