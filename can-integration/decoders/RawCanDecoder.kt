package com.reveng.carlauncher.carlib

/**
 * RawCanDecoder — pure-Kotlin decoder for **raw Toyota CAN frames** as they arrive off a
 * CANable 2.0 (slcan firmware) tapped onto the 2019 RAV4 Hybrid powertrain bus.
 *
 * This is the SECOND, independent CAN data path in the launcher. The first is
 * [HiworldCanDecoder], which parses the vendor HiWorld CANBOX *digest* (a pre-chewed serial
 * protocol on `A5 5A A5` frames). This one instead sees the **real vehicle bus** 1:1, so an ID
 * here is a genuine Toyota arbitration ID and a payload is the raw 8-byte CAN data field. The two
 * decoders deliberately do NOT share a signal type (see the naming note below); the launcher merges
 * both into one `VehicleState` (see `CanableReaderService.kt`).
 *
 * Ground truth: opendbc `toyota_nodsu_pt.dbc` + its imports `_toyota_2017.dbc` /
 * `_toyota_adas_standard.dbc` (the exact `startbit|len@order(sign)` and `(scale,offset)` for every
 * signal below were read straight out of those files — see each decoder's KDoc for the DBC line).
 * This car is a hybrid, so gear and gas pedal come from the *_HYBRID* messages (0x127 / 0x245), not
 * the ICE `GEAR_PACKET` (0x3BC) / `GAS_PEDAL` (0x2C1).
 *
 * LISTEN-ONLY. Nothing in this file (or the reader) ever builds a frame to transmit — it only
 * decodes.
 *
 * ── Naming ────────────────────────────────────────────────────────────────────────────────────
 * The result type is [RawCanSignal], NOT `CanSignal`. [HiworldCanDecoder] already owns the
 * top-level name `CanSignal` in this same package (`com.reveng.carlauncher.carlib`), and two
 * top-level `CanSignal` declarations would not compile. The brief asked for `CanSignal`; renamed to
 * avoid the collision while keeping the same shape (Speed, WheelSpeeds, Gear, …, Unknown).
 *
 * ── Toyota DBC bit-numbering convention (the one gotcha) ─────────────────────────────────────────
 * Every Toyota signal here is Motorola / big-endian, written `startbit|len@0` in the DBC (`@0` =
 * Motorola, `@1` = Intel/little-endian; `+` = unsigned, `-` = signed). DBC bit numbering is:
 *
 *     dbcBit = byteIndex*8 + bitInByte,  where bitInByte 0 = LSB of that byte, 7 = MSB.
 *
 * For a Motorola signal the quoted **startbit is the MSB** of the field. The field then walks
 * *downward* through the current byte (bit7→bit0) and, on crossing a byte boundary, jumps to the
 * MSB (bit7) of the NEXT higher byte — the classic "sawtooth". [bitField] implements exactly that
 * walk, MSB-first, so e.g. SPEED `47|16@0` reads bytes 5 then 6 as a plain big-endian u16.
 *
 * Pure Kotlin, zero Android imports — unit-testable off-device via [selfTest].
 */
object RawCanDecoder {

    // ── Toyota arbitration IDs we decode (11-bit standard). Hex == DBC decimal in comments. ──
    const val ID_STEER_ANGLE_SENSOR = 0x025   // 37   STEER_ANGLE_SENSOR
    const val ID_WHEEL_SPEEDS       = 0x0AA   // 170  WHEEL_SPEEDS
    const val ID_BRAKE              = 0x0A6   // 166  BRAKE
    const val ID_SPEED              = 0x0B4   // 180  SPEED
    const val ID_GEAR_HYBRID        = 0x127   // 295  GEAR_PACKET_HYBRID
    const val ID_PCM_CRUISE         = 0x1D2   // 466  PCM_CRUISE
    const val ID_PCM_CRUISE_2       = 0x1D3   // 467  PCM_CRUISE_2
    const val ID_GAS_PEDAL_HYBRID   = 0x245   // 581  GAS_PEDAL_HYBRID
    const val ID_STEER_TORQUE       = 0x260   // 608  STEER_TORQUE_SENSOR
    const val ID_BLINKERS_STATE     = 0x614   // 1556 BLINKERS_STATE
    const val ID_BODY_CONTROL_STATE = 0x620   // 1568 BODY_CONTROL_STATE
    const val ID_LIGHT_STALK        = 0x622   // 1570 LIGHT_STALK (extension example only)

    /**
     * Decode one raw CAN frame. Returns null for an ID we do not (yet) map, so a caller can cheaply
     * drop uninteresting traffic; use [decodeOrUnknown] if you want [RawCanSignal.Unknown] instead.
     *
     * Never throws on a short [data]: [bitField] treats out-of-range bytes as 0, so a truncated
     * frame yields a low/zero reading rather than crashing the read loop.
     */
    fun decode(id: Int, data: ByteArray): RawCanSignal? = when (id) {
        ID_SPEED              -> decodeSpeed(data)
        ID_WHEEL_SPEEDS       -> decodeWheelSpeeds(data)
        ID_GEAR_HYBRID        -> decodeGear(data)
        ID_STEER_ANGLE_SENSOR -> decodeSteerAngle(data)
        ID_GAS_PEDAL_HYBRID   -> decodeGasPedal(data)
        ID_BRAKE              -> decodeBrake(data)
        ID_PCM_CRUISE         -> decodeCruise(data)
        ID_PCM_CRUISE_2       -> decodeCruise2(data)
        ID_BODY_CONTROL_STATE -> decodeDoors(data)
        ID_BLINKERS_STATE     -> decodeBlinkers(data)
        // Extension point: add a case + a decodeX() + a RawCanSignal variant. STEER_TORQUE (0x260),
        // LIGHT_STALK (0x622), PCM_CRUISE_2 follow-distance, etc. are one line each from here.
        else -> null
    }

    /** As [decode], but never null — unmapped IDs become [RawCanSignal.Unknown] (keeps raw bytes). */
    fun decodeOrUnknown(id: Int, data: ByteArray): RawCanSignal =
        decode(id, data) ?: RawCanSignal.Unknown(id, data.copyOf())

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Per-message decoders. Each cites its exact DBC line.
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /** 0xB4 SPEED — `SG_ SPEED : 47|16@0+ (0.01,0) "km/h"`. Plain big-endian u16 over bytes 5,6. */
    private fun decodeSpeed(d: ByteArray): RawCanSignal.Speed {
        val raw = bitField(d, 47, 16).toInt()
        return RawCanSignal.Speed(kmh = raw * 0.01)
    }

    /**
     * 0xAA WHEEL_SPEEDS — four `SG_ WHEEL_SPEED_xx : <sb>|15@0+ (0.01,-67.67) "km/h"`:
     * FR 6|15, FL 22|15, RR 38|15, RL 54|15. Each has a 1-bit FAULT flag just above it (the MSB the
     * 15-bit field stops short of), which we surface too.
     */
    private fun decodeWheelSpeeds(d: ByteArray): RawCanSignal.WheelSpeeds {
        fun ws(sb: Int) = bitField(d, sb, 15).toInt() * 0.01 - 67.67
        return RawCanSignal.WheelSpeeds(
            frontRight = ws(6),
            frontLeft = ws(22),
            rearRight = ws(38),
            rearLeft = ws(54),
            frontRightFault = bitField(d, 7, 1) != 0L,
            frontLeftFault = bitField(d, 23, 1) != 0L,
            rearRightFault = bitField(d, 39, 1) != 0L,
            rearLeftFault = bitField(d, 55, 1) != 0L,
        )
    }

    /**
     * 0x127 GEAR_PACKET_HYBRID — `SG_ GEAR : 47|4@0+` = high nibble of byte 5.
     * `VAL_ 295 GEAR 0 "P" 1 "R" 2 "N" 3 "D" 4 "B"`.
     */
    private fun decodeGear(d: ByteArray): RawCanSignal.Gear {
        val raw = bitField(d, 47, 4).toInt()
        return RawCanSignal.Gear(raw = raw, position = RawCanSignal.GearPos.fromHybrid(raw))
    }

    /**
     * 0x25 STEER_ANGLE_SENSOR — `SG_ STEER_ANGLE : 3|12@0- (1.5,0) "deg"` plus
     * `SG_ STEER_FRACTION : 39|4@0- (0.1,0)` and `SG_ STEER_RATE : 35|12@0- (1,0) "deg/s"`.
     * Both angle parts are signed (`@0-`). Total angle = angle*1.5 + fraction*0.1.
     */
    private fun decodeSteerAngle(d: ByteArray): RawCanSignal.SteeringAngle {
        val angle = signExtend(bitField(d, 3, 12), 12) * 1.5
        val fraction = signExtend(bitField(d, 39, 4), 4) * 0.1
        val rate = signExtend(bitField(d, 35, 12), 12).toDouble()
        return RawCanSignal.SteeringAngle(degrees = angle + fraction, rateDegPerSec = rate)
    }

    /** 0x245 GAS_PEDAL_HYBRID — `SG_ GAS_PEDAL : 23|8@0+ (0.005,0)` = byte 2 × 0.005 → 0..~1.0. */
    private fun decodeGasPedal(d: ByteArray): RawCanSignal.GasPedal {
        val fraction = bitField(d, 23, 8).toInt() * 0.005
        return RawCanSignal.GasPedal(fraction = fraction)
    }

    /**
     * 0xA6 BRAKE — `SG_ BRAKE_AMOUNT : 7|8@0+` (byte 0) and `SG_ BRAKE_FORCE : 23|8@0+ (40,0) "N"`
     * (byte 2 × 40). This message has no dedicated "pressed" bit, so [RawCanSignal.Brake.pressed]
     * is derived (amount or force non-zero). A cleaner boolean brake-pressed also exists on
     * PCM_CRUISE_2 (bit 3) — the VehicleState merge can prefer that if desired.
     */
    private fun decodeBrake(d: ByteArray): RawCanSignal.Brake {
        val amount = bitField(d, 7, 8).toInt()
        val forceN = bitField(d, 23, 8).toInt() * 40
        return RawCanSignal.Brake(amount = amount, forceN = forceN, pressed = amount > 0 || forceN > 0)
    }

    /**
     * 0x1D2 PCM_CRUISE — ACC engage/state (from `_toyota_adas_standard.dbc`):
     * `SG_ CRUISE_ACTIVE : 5|1@0+`, `SG_ GAS_RELEASED : 4|1@0+`, `SG_ CRUISE_STATE : 55|4@0+`.
     * `VAL_ 466 CRUISE_STATE`: 0 off, 1..6 non-adaptive, 7 standstill, 8 adaptive engaged, 9/10
     * adaptive click up/down, 11 timer_3sec.
     */
    private fun decodeCruise(d: ByteArray): RawCanSignal.Cruise {
        val state = bitField(d, 55, 4).toInt()
        return RawCanSignal.Cruise(
            active = bitField(d, 5, 1) != 0L,
            gasReleased = bitField(d, 4, 1) != 0L,
            state = state,
            adaptiveEngaged = state >= 8,
        )
    }

    /**
     * 0x1D3 PCM_CRUISE_2 — `SG_ MAIN_ON : 15|1@0+` (cruise master switch), `SG_ SET_SPEED : 23|8@0+
     * "km/h"` (byte 2), `SG_ BRAKE_PRESSED : 3|1@0+`. Surfaced as a Cruise2 variant so the merge can
     * combine MAIN_ON + SET_SPEED with the engage state from 0x1D2.
     */
    private fun decodeCruise2(d: ByteArray): RawCanSignal.Cruise2 = RawCanSignal.Cruise2(
        mainOn = bitField(d, 15, 1) != 0L,
        setSpeedKmh = bitField(d, 23, 8).toInt(),
        brakePressed = bitField(d, 3, 1) != 0L,
    )

    /**
     * 0x620 BODY_CONTROL_STATE — door/seatbelt/parking-brake single bits:
     * `DOOR_OPEN_FL 45|1`, `DOOR_OPEN_FR 44|1`, `DOOR_OPEN_RL 42|1`, `DOOR_OPEN_RR 43|1`,
     * `SEATBELT_DRIVER_UNLATCHED 62|1`, `PARKING_BRAKE 60|1`.
     */
    private fun decodeDoors(d: ByteArray): RawCanSignal.Doors = RawCanSignal.Doors(
        frontLeft = bitField(d, 45, 1) != 0L,
        frontRight = bitField(d, 44, 1) != 0L,
        rearLeft = bitField(d, 42, 1) != 0L,
        rearRight = bitField(d, 43, 1) != 0L,
        driverSeatbeltUnlatched = bitField(d, 62, 1) != 0L,
        parkingBrake = bitField(d, 60, 1) != 0L,
    )

    /**
     * 0x614 BLINKERS_STATE — `SG_ TURN_SIGNALS : 29|2@0+` and `SG_ HAZARD_LIGHT : 27|1@0+`.
     * `VAL_ 1556 TURN_SIGNALS 1 "left" 2 "right" 3 "none"` (0 also = none/off).
     */
    private fun decodeBlinkers(d: ByteArray): RawCanSignal.Blinkers {
        val raw = bitField(d, 29, 2).toInt()
        return RawCanSignal.Blinkers(
            left = raw == 1,
            right = raw == 2,
            hazard = bitField(d, 27, 1) != 0L,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Bit-field extraction (the DBC-convention core). Pure, allocation-free.
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Extract [len] bits from [data] as an unsigned value, following the DBC `startbit|len@order`
     * convention documented in the class KDoc.
     *
     * @param startBit DBC start bit. For [bigEndian] (Motorola, `@0`) this is the field MSB and the
     *   walk is downward-with-sawtooth. For little-endian (Intel, `@1`) it is the field LSB and the
     *   walk is upward.
     * @param bigEndian true for `@0` Toyota signals (the default here); false for the rare `@1`
     *   signal such as `LIGHT_STALK.HEADLIGHT_MODE 21|3@1+`.
     * @return the raw bits, right-aligned, as a non-negative Long. Sign-extend with [signExtend]
     *   for `@..-` signals. Bytes past the end of [data] read as 0.
     */
    fun bitField(data: ByteArray, startBit: Int, len: Int, bigEndian: Boolean = true): Long {
        var result = 0L
        var byteIndex = startBit / 8
        var bitIndex = startBit % 8            // 0 = LSB of the byte, 7 = MSB
        if (bigEndian) {
            // MSB-first: append each bit at the low end, stepping DOWN the sawtooth.
            for (i in 0 until len) {
                result = (result shl 1) or bitAt(data, byteIndex, bitIndex)
                if (bitIndex == 0) { bitIndex = 7; byteIndex++ } else bitIndex--
            }
        } else {
            // LSB-first: place each bit at its own position, stepping UP.
            for (i in 0 until len) {
                result = result or (bitAt(data, byteIndex, bitIndex) shl i)
                if (bitIndex == 7) { bitIndex = 0; byteIndex++ } else bitIndex++
            }
        }
        return result
    }

    /** One bit (0/1) at byte [byteIndex], bit [bitIndex] (0 = LSB); 0 if out of range. */
    private fun bitAt(data: ByteArray, byteIndex: Int, bitIndex: Int): Long =
        if (byteIndex in data.indices) ((data[byteIndex].toInt() ushr bitIndex) and 1).toLong() else 0L

    /** Interpret the low [len] bits of [raw] as a two's-complement signed value. */
    fun signExtend(raw: Long, len: Int): Long {
        val signBit = 1L shl (len - 1)
        return if (raw and signBit != 0L) raw - (1L shl len) else raw
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Self-test — no test framework; hand-built frames with known outputs. Returns true on pass.
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Sanity-check the bit math against hand-built frames. Callable from anywhere (a debug menu,
     * or `main` when run as pure Kotlin). Deliberately covers: a plain big-endian u16 (SPEED), a
     * sub-byte nibble at a byte boundary (GEAR), a signed sub-byte-spanning field (STEER_ANGLE),
     * a 15-bit field with offset (WHEEL_SPEEDS), and a single status bit (BLINKERS).
     */
    fun selfTest(): Boolean {
        val results = mutableListOf<Boolean>()
        fun check(name: String, cond: Boolean) { results += cond; if (!cond) println("FAIL: $name") }

        // frame builder: 8 zero bytes with the given (index,value) overrides.
        fun frame(vararg bytes: Pair<Int, Int>): ByteArray {
            val d = ByteArray(8)
            for ((i, v) in bytes) d[i] = v.toByte()
            return d
        }

        // 1) SPEED 0xB4 @ 50.00 km/h → raw 5000 = 0x1388 in bytes 5,6.
        val spd = decode(ID_SPEED, frame(5 to 0x13, 6 to 0x88)) as RawCanSignal.Speed
        check("speed=50.0", kbEq(spd.kmh, 50.0))

        // 2) GEAR 0x127 = D (3) in the high nibble of byte 5.
        val gear = decode(ID_GEAR_HYBRID, frame(5 to 0x30)) as RawCanSignal.Gear
        check("gear=D", gear.raw == 3 && gear.position == RawCanSignal.GearPos.D)

        // 3) STEER_ANGLE 0x25: put -2 into the 12-bit signed field 3|12.
        //    -2 in 12-bit two's complement = 0xFFE. startbit 3 ⇒ byte0 low nibble = 0xF, byte1 = 0xFE.
        //    angle = -2 * 1.5 = -3.0 deg; fraction field left 0.
        val steer = decode(ID_STEER_ANGLE_SENSOR, frame(0 to 0x0F, 1 to 0xFE)) as RawCanSignal.SteeringAngle
        check("steer=-3.0", kbEq(steer.degrees, -3.0))

        // 4) WHEEL_SPEEDS 0xAA front-right 6|15 (0.01,-67.67). Want 0.00 km/h ⇒ raw 6767 = 0x1A6F.
        //    15-bit field with MSB at bit6 of byte0 ⇒ byte0 low7 = high bits, byte1 = low 8.
        //    6767 in binary (0011010 0110 1111) ⇒ byte0 high7 = 0x1A; verify via decode round-trip below.
        val wsFrame = ByteArray(8)
        // write 6767 into 6|15 by hand using the same convention (independent of decoder).
        writeBigEndianField(wsFrame, 6, 15, 6767)
        val ws = decode(ID_WHEEL_SPEEDS, wsFrame) as RawCanSignal.WheelSpeeds
        check("wheelFR≈0", kbEq(ws.frontRight, 0.0))

        // 5) BLINKERS 0x614 left (TURN_SIGNALS 29|2 == 1). MSB at bit5 of byte3, so value 1 ⇒
        //    bits (29,28) = (0,1) ⇒ byte3 bit4 set = 0x10.
        val blink = decode(ID_BLINKERS_STATE, frame(3 to 0x10)) as RawCanSignal.Blinkers
        check("blink=left", blink.left && !blink.right)

        // 6) GAS_PEDAL 0x245 byte2=100 ⇒ 0.5.
        val gas = decode(ID_GAS_PEDAL_HYBRID, frame(2 to 100)) as RawCanSignal.GasPedal
        check("gas=0.5", kbEq(gas.fraction, 0.5))

        // 7) Unknown ID → null from decode, Unknown from decodeOrUnknown.
        check("unknown-null", decode(0x001, ByteArray(8)) == null)
        check("unknown-var", decodeOrUnknown(0x001, ByteArray(1)) is RawCanSignal.Unknown)

        return results.all { it }
    }

    /** Test helper: write [value] into a big-endian (Motorola) field, mirroring [bitField]. */
    private fun writeBigEndianField(data: ByteArray, startBit: Int, len: Int, value: Int) {
        var byteIndex = startBit / 8
        var bitIndex = startBit % 8
        for (i in 0 until len) {
            val bit = (value ushr (len - 1 - i)) and 1
            if (byteIndex in data.indices && bit == 1) {
                data[byteIndex] = (data[byteIndex].toInt() or (1 shl bitIndex)).toByte()
            }
            if (bitIndex == 0) { bitIndex = 7; byteIndex++ } else bitIndex--
        }
    }

    private fun kbEq(a: Double, b: Double) = kotlin.math.abs(a - b) < 1e-6
}

/**
 * Decoded raw-CAN signal. `Unknown` keeps the raw payload so an on-device capture can still log an
 * ID we have not mapped. Named `RawCanSignal` (not `CanSignal`) to coexist with [HiworldCanDecoder]'s
 * `CanSignal` in the same package — see [RawCanDecoder] KDoc.
 */
sealed interface RawCanSignal {

    /** 0xB4 — vehicle speed, km/h. */
    data class Speed(val kmh: Double) : RawCanSignal

    /** 0xAA — per-wheel speeds, km/h, plus each wheel's fault flag. */
    data class WheelSpeeds(
        val frontLeft: Double,
        val frontRight: Double,
        val rearLeft: Double,
        val rearRight: Double,
        val frontLeftFault: Boolean = false,
        val frontRightFault: Boolean = false,
        val rearLeftFault: Boolean = false,
        val rearRightFault: Boolean = false,
    ) : RawCanSignal {
        /** Mean of the four wheels — a smoother speed proxy than the single SPEED signal. */
        val average: Double get() = (frontLeft + frontRight + rearLeft + rearRight) / 4.0
    }

    /** Toyota hybrid shift positions (`VAL_ 295 GEAR`). */
    enum class GearPos { P, R, N, D, B, UNKNOWN;
        companion object {
            fun fromHybrid(raw: Int) = when (raw) {
                0 -> P; 1 -> R; 2 -> N; 3 -> D; 4 -> B; else -> UNKNOWN
            }
        }
    }

    /** 0x127 — shift lever position (hybrid). [raw] kept for logging unmapped codes. */
    data class Gear(val raw: Int, val position: GearPos) : RawCanSignal

    /** 0x25 — steering wheel angle (deg, + = left per Toyota) and rate (deg/s). */
    data class SteeringAngle(val degrees: Double, val rateDegPerSec: Double) : RawCanSignal

    /** 0x245 — accelerator pedal, 0.0 (released) … ~1.0 (floored). */
    data class GasPedal(val fraction: Double) : RawCanSignal

    /** 0xA6 — brake: raw [amount], [forceN] (N), and derived [pressed] (see decoder KDoc). */
    data class Brake(val amount: Int, val forceN: Int, val pressed: Boolean) : RawCanSignal

    /** 0x1D2 — ACC / cruise engage state. [adaptiveEngaged] = CRUISE_STATE ≥ 8. */
    data class Cruise(
        val active: Boolean,
        val gasReleased: Boolean,
        val state: Int,
        val adaptiveEngaged: Boolean,
    ) : RawCanSignal

    /** 0x1D3 — cruise master switch + set speed (km/h) + brake-pressed bit. */
    data class Cruise2(
        val mainOn: Boolean,
        val setSpeedKmh: Int,
        val brakePressed: Boolean,
    ) : RawCanSignal

    /** 0x620 — door / seatbelt / parking-brake booleans. */
    data class Doors(
        val frontLeft: Boolean,
        val frontRight: Boolean,
        val rearLeft: Boolean,
        val rearRight: Boolean,
        val driverSeatbeltUnlatched: Boolean,
        val parkingBrake: Boolean,
    ) : RawCanSignal {
        val anyOpen: Boolean get() = frontLeft || frontRight || rearLeft || rearRight
    }

    /** 0x614 — turn signals + hazards. */
    data class Blinkers(val left: Boolean, val right: Boolean, val hazard: Boolean) : RawCanSignal

    /** Any ID we don't map yet; raw payload preserved for capture/logging. */
    data class Unknown(val id: Int, val payload: ByteArray) : RawCanSignal {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Unknown) return false
            return id == other.id && payload.contentEquals(other.payload)
        }
        override fun hashCode(): Int = 31 * id + payload.contentHashCode()
    }
}

/** Run the decoder self-test as a plain Kotlin program (`kotlinc … -include-runtime` then run). */
fun main() {
    val ok = RawCanDecoder.selfTest()
    println(if (ok) "RawCanDecoder.selfTest: PASS" else "RawCanDecoder.selfTest: FAIL")
    if (!ok) kotlin.system.exitProcess(1)
}
