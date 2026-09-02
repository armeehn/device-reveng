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
    private fun decodeBasicStatus(p: ByteArray): CanSignal.BasicStatus {
        val doorBits = u(p, 4)
        val raw = u16be(p, 6, 7)
        val signed = if (raw and 0x8000 != 0) raw - 0x10000 else raw
        return CanSignal.BasicStatus(
            swcButtonId = u(p, 2),
            swcPressed = u(p, 3) != 0,
            doorBits = doorBits,
            doorFrontLeftOpen = (doorBits and 0x40) != 0, // bit6 = driver/front-left (confirmed)
            // TODO(drive-capture): other door bits observed 0 while parked — positions unconfirmed.
            steerAngleDeg = signed / 14.0,
        )
    }

    /**
     * 0x48 TPMS — five tyre pressures.
     *
     * OEM `OnHandleCanTpmsInfoCmd` → `addTpms`: pressure = firstByte + secondByte where the
     * pairs are FL=(bArr[4],bArr[9]), FR=(bArr[5],bArr[10]), RL=(bArr[6],bArr[11]),
     * RR=(bArr[7],bArr[12]), spare=(bArr[8],bArr[13]) ⇒ in payload terms p[i] + p[i+5] for
     * i = FL:2, FR:3, RL:4, RR:5, spare:6. A byte of 0xFE ⇒ no reading (OEM tests `!= 254` on
     * the first byte; we null if *either* byte is the sentinel). Units: kPa.
     * Variance: p[2..6] all 0xFE while parked (sensors asleep) ⇒ all null.
     */
    private fun decodeTpms(p: ByteArray): CanSignal.Tpms = CanSignal.Tpms(
        frontLeftKpa = tpms(p, 2),
        frontRightKpa = tpms(p, 3),
        rearLeftKpa = tpms(p, 4),
        rearRightKpa = tpms(p, 5),
        spareKpa = tpms(p, 6),
    )

    private fun tpms(p: ByteArray, i: Int): Int? {
        val a = u(p, i)
        val b = u(p, i + 5)
        return if (a == TPMS_SENTINEL || b == TPMS_SENTINEL) null else a + b
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
        /** Raw door bitfield p[4]. */
        val doorBits: Int,
        /** bit6 (0x40): driver / front-left door open. */
        val doorFrontLeftOpen: Boolean,
        /** Degrees; positive/negative per steering direction. Scale = raw/14 (OEM). */
        val steerAngleDeg: Double,
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
