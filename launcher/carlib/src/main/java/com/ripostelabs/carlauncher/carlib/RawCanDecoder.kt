package com.ripostelabs.carlauncher.carlib

/**
 * RawCanDecoder — decodes **raw Toyota body-bus** frames tapped behind the head unit.
 *
 * ── Provenance ──────────────────────────────────────────────────────────────────────────────────
 * Every signal below was confirmed on a **2019 RAV4 Hybrid (XA50)** on 2026-09-07 by actuation:
 * hold one state for ~60 s, capture, then compare the *fraction of frames a bit is set* against a
 * second run holding a different state. Raw captures and the full write-up are archived alongside
 * `can-integration/docs/`.
 *
 * ── Where this bus is ───────────────────────────────────────────────────────────────────────────
 * NOT the OBD-II port. The DLC on this car is gatewayed down to two 1 Hz heartbeat IDs (0x4E0,
 * 0x45A) with no diagnostic responder — useless for this. The real bus is the HiWorld TYF2.20
 * CANbus decoder behind the head unit: **pin 11 CAN-H, pin 12 CAN-L, pin 1 GND**, 500 kbit/s.
 * 111 distinct IDs, ~1215 frames/s. See `docs/CANABLE_INTEGRATION.md`.
 *
 * ── Two IDs that earlier notes got wrong ────────────────────────────────────────────────────────
 * Prior staging notes carried `doors 0x620` and `blinkers 0x614` from a 2023 car, flagged
 * "re-verify on the 2019". Re-verified, and both are wrong here:
 *  - **0x620 is NOT doors.** Byte 1 bit 0x80 pulses for ~0.3 s on door activity. With a door held
 *    open for 60 s it stayed CLEAR for 47 of them, so it is an event, not a state. 0x626 carries
 *    the same event latched to exactly 1.0 s.
 *  - **0x614 is not blinkers, and blinkers are not on this bus at all.** Three separate hunts
 *    (headlights once, indicators twice, once with the car in READY) found nothing. The vendor MCU
 *    does report them — its cmd 0x18 handler drives the turn-signal cameras — so that state most
 *    likely arrives over the IEBUS/AVC-LAN pins (TYF2.20 pins 3/4), which CAN hardware cannot read.
 * Neither is emitted here. [Blinkers] stays in the model for the LIN/IEBUS path to fill later.
 *
 * ── Speed, settled 2026-09-09 ───────────────────────────────────────────────────────────────────
 * A real drive with the ECU answering OBD PID 0x0D at 2 Hz (0..60 km/h, three ECUs agreeing on
 * every sample) is the reference. Four raw-bus fields track it with zero median error:
 *   0x361 byte 6 and 0x498 byte 5 are integer km/h; 0x0B4 bytes 5-6 are km/h × 0.01 (opendbc
 *   SP1); 0x0AA carries four 15-bit wheel speeds at 0.01 km/h with a −67.67 offset (opendbc
 *   VXFR/VXFL/VXRR/VXRL). Residuals are sampling latency during acceleration, not scale.
 * The MCU candidates were wrong: 0x17 never arrived in 996 paired rows and 0x13 spans 0..175
 * while the car holds 16 km/h. Speed comes from here now, and needs no request on the bus.
 */
sealed class RawCanSignal {

    /** Which openings are ajar. Mirrors the vendor MCU's own door byte bit-for-bit. */
    data class Doors(
        val driver: Boolean,
        val passenger: Boolean,
        val rearLeft: Boolean,
        val rearRight: Boolean,
        val tailgate: Boolean,
        val hood: Boolean,
    ) : RawCanSignal() {
        val anyOpen: Boolean get() = driver || passenger || rearLeft || rearRight || tailgate || hood
    }

    /** Climate power state. Speed lives in [Fan]; this is only on/off. */
    data class Climate(val on: Boolean) : RawCanSignal()

    /**
     * Blower state. [level] is the raw byte, NOT the displayed step — see [FAN_LEVEL_MIN].
     * Use [running] for "is the fan moving air", which is the part that is trustworthy.
     */
    data class Fan(val running: Boolean, val level: Int) : RawCanSignal()

    /** A frame we recognise the id of but do not decode. Carried so callers can log it. */
    data class Unknown(val id: Int) : RawCanSignal()

    /** Road speed. Emitted by three ids at 2, 15 and 39 Hz; the freshest wins downstream. */
    data class Speed(val kmh: Double) : RawCanSignal()

    /** Per-wheel speed from the stability ECU, ~78 Hz. */
    data class WheelSpeeds(val flKmh: Double, val frKmh: Double, val rlKmh: Double, val rrKmh: Double) : RawCanSignal()

    // ── Not yet verified on this car ────────────────────────────────────────────────────────────
    // Present so VehicleState stays stable and the LIN/IEBUS path can fill them in. Nothing below
    // is emitted by [RawCanDecoder.decode]; adding one means capturing it first.
    enum class GearPos { P, R, N, D, B, UNKNOWN }
    data class Gear(val position: GearPos) : RawCanSignal()
    data class SteeringAngle(val degrees: Double) : RawCanSignal()
    data class GasPedal(val fraction: Double) : RawCanSignal()
    data class Brake(val pressed: Boolean, val forceN: Int?) : RawCanSignal()
    data class Cruise(val active: Boolean, val adaptiveEngaged: Boolean) : RawCanSignal()
    data class Cruise2(val mainOn: Boolean, val setSpeedKmh: Int?, val brakePressed: Boolean?) : RawCanSignal()
    data class Blinkers(val left: Boolean, val right: Boolean, val hazard: Boolean) : RawCanSignal()
}

object RawCanDecoder {

    // ── Frame ids (11-bit) ──────────────────────────────────────────────────────────────────────
    /** Door/opening status bitfield, ~2 Hz. */
    const val ID_DOOR_STATUS = 0x4A5
    /** Climate on/off. Named ACN1S01 by opendbc's toyota_2017_ref_pt.dbc. */
    const val ID_CLIMATE_A = 0x380
    /** Climate on/off, second copy. opendbc ACN1S07. */
    const val ID_CLIMATE_B = 0x3B0
    /** Blower state. opendbc names the message ENG1D59 but gives it no signals. */
    const val ID_FAN = 0x4AD
    /** Integer km/h in byte 6, ~15 Hz. Not in opendbc. The best speed source on this bus. */
    const val ID_SPEED_FAST = 0x361
    /** Integer km/h in byte 5, ~2 Hz. opendbc ENG1D50, field DRENG06. */
    const val ID_SPEED_SLOW = 0x498
    /** Stability ECU speed, km/h × 0.01 in bytes 5-6, ~39 Hz. opendbc VSC1S03 SP1. */
    const val ID_SPEED_VSC = 0x0B4
    /** Four wheel speeds, ~78 Hz. opendbc VSC1F01. */
    const val ID_WHEEL_SPEEDS = 0x0AA

    // ── Byte offsets ────────────────────────────────────────────────────────────────────────────
    private const val DOOR_BYTE = 3
    private const val CLIMATE_A_BYTE = 2
    private const val CLIMATE_B_BYTE = 5
    private const val FAN_BYTE = 6
    private const val SPEED_FAST_BYTE = 6
    private const val SPEED_SLOW_BYTE = 5
    private const val SPEED_VSC_HI = 5
    private const val SPEED_VSC_SCALE = 0.01

    /** Wheel order on the wire: FR, FL, RR, RL — each a 15-bit big-endian field. */
    private const val WHEEL_FR_HI = 0
    private const val WHEEL_FL_HI = 2
    private const val WHEEL_RR_HI = 4
    private const val WHEEL_RL_HI = 6
    private const val WHEEL_MASK_HI = 0x7F
    private const val WHEEL_SCALE = 0.01
    private const val WHEEL_OFFSET_KMH = -67.67

    // ── Door bits. Identical to the vendor MCU's cmd 0x11 byte 6, which is a strong mutual check:
    // ours came from opening doors, theirs from decompiling the head unit, and they agree. ────────
    private const val DOOR_DRIVER = 0x80
    private const val DOOR_PASSENGER = 0x40
    private const val DOOR_REAR_RIGHT = 0x20
    private const val DOOR_REAR_LEFT = 0x10
    private const val DOOR_TAILGATE = 0x08
    /** Predicted from the vendor byte, never actually opened. Reported, flagged here as untested. */
    private const val DOOR_HOOD = 0x04

    /** 0x3B0 byte 5 carries this bit only while climate is on. */
    private const val CLIMATE_B_ON = 0x08
    /** 0x380 byte 2 reads 0x00 with climate off and is non-zero (0x20/0xA0) when on. */
    private const val CLIMATE_A_OFF = 0x00

    /**
     * 0x4AD byte 6 is 0x00 with the blower stopped and spans 0x52..0x5B while it runs.
     *
     * **It is not the displayed step.** The car offers 7 steps plus off — eight states — but this
     * byte takes ten values and stepping the fan up produced 52 54 53 54 57 58 59 5B, which skips
     * and backtracks. It is most likely blower duty, which auto mode trims independently of the
     * step the driver selected. For the *selected* step use the vendor MCU path (cmd 0x31 byte 7
     * low nibble), which is a clean 4-bit value.
     */
    private const val FAN_OFF = 0x00
    private const val FAN_LEVEL_MIN = 0x52

    private const val DLC_MIN = 8

    /**
     * Decode one frame. Returns null for ids we do not handle, so callers can cheaply ignore the
     * ~107 other ids on this bus without allocating.
     */
    fun decode(id: Int, data: ByteArray): RawCanSignal? {
        if (data.size < DLC_MIN) {
            return null
        }

        return when (id) {
            ID_DOOR_STATUS -> decodeDoors(data[DOOR_BYTE].toInt() and 0xFF)
            ID_CLIMATE_A -> RawCanSignal.Climate(on = (data[CLIMATE_A_BYTE].toInt() and 0xFF) != CLIMATE_A_OFF)
            ID_CLIMATE_B -> RawCanSignal.Climate(on = (data[CLIMATE_B_BYTE].toInt() and CLIMATE_B_ON) != 0)
            ID_FAN -> decodeFan(data[FAN_BYTE].toInt() and 0xFF)
            ID_SPEED_FAST -> RawCanSignal.Speed(u8(data, SPEED_FAST_BYTE).toDouble())
            ID_SPEED_SLOW -> RawCanSignal.Speed(u8(data, SPEED_SLOW_BYTE).toDouble())
            ID_SPEED_VSC -> RawCanSignal.Speed(s16be(data, SPEED_VSC_HI) * SPEED_VSC_SCALE)
            ID_WHEEL_SPEEDS -> RawCanSignal.WheelSpeeds(
                flKmh = wheel(data, WHEEL_FL_HI),
                frKmh = wheel(data, WHEEL_FR_HI),
                rlKmh = wheel(data, WHEEL_RL_HI),
                rrKmh = wheel(data, WHEEL_RR_HI),
            )
            else -> null
        }
    }

    private fun decodeDoors(b: Int) = RawCanSignal.Doors(
        driver = b and DOOR_DRIVER != 0,
        passenger = b and DOOR_PASSENGER != 0,
        rearLeft = b and DOOR_REAR_LEFT != 0,
        rearRight = b and DOOR_REAR_RIGHT != 0,
        tailgate = b and DOOR_TAILGATE != 0,
        hood = b and DOOR_HOOD != 0,
    )

    private fun decodeFan(b: Int) = RawCanSignal.Fan(
        running = b != FAN_OFF,
        level = if (b >= FAN_LEVEL_MIN) b - FAN_LEVEL_MIN else 0,
    )

    private fun wheel(data: ByteArray, hi: Int): Double =
        (((u8(data, hi) and WHEEL_MASK_HI) shl 8) or u8(data, hi + 1)) * WHEEL_SCALE + WHEEL_OFFSET_KMH

    // ── Field readers. DBC "@0" big-endian: the high byte comes first. ──────────────────────────
    internal fun u8(data: ByteArray, at: Int): Int = data[at].toInt() and 0xFF

    internal fun u16be(data: ByteArray, hi: Int): Int = (u8(data, hi) shl 8) or u8(data, hi + 1)

    internal fun s16be(data: ByteArray, hi: Int): Int = u16be(data, hi).toShort().toInt()

    /**
     * Self-test over **real frames from the 2026-09-07 captures**, not hand-written bytes. Each
     * vector below appears verbatim in the archived logs.
     *
     * @return null when every assertion holds, otherwise the first failure.
     */
    fun selfTest(): String? {
        fun frame(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

        // capture-2026-09-07-driver-hold.log — driver's door held open
        val driverOpen = decode(ID_DOOR_STATUS, frame(0x00, 0x01, 0xE0, 0x80, 0xC8, 0x00, 0x08, 0xC6))
        if (driverOpen !is RawCanSignal.Doors || !driverOpen.driver || driverOpen.passenger) {
            return "driver-open vector decoded as $driverOpen"
        }

        // same capture, everything shut
        val allShut = decode(ID_DOOR_STATUS, frame(0x00, 0x01, 0xE0, 0x00, 0xC8, 0x00, 0x08, 0xC3))
        if (allShut !is RawCanSignal.Doors || allShut.anyOpen) {
            return "all-shut vector decoded as $allShut"
        }

        // capture-2026-09-07-passenger-hold.log — front passenger held open
        val passOpen = decode(ID_DOOR_STATUS, frame(0x00, 0x01, 0xE0, 0x40, 0xC8, 0x00, 0x08, 0xB5))
        if (passOpen !is RawCanSignal.Doors || !passOpen.passenger || passOpen.driver) {
            return "passenger-open vector decoded as $passOpen"
        }

        // capture-2026-09-07-rear-sequence.log — 0x28 = tailgate + rear right together
        val two = decode(ID_DOOR_STATUS, frame(0x00, 0x01, 0xE0, 0x28, 0xC8, 0x00, 0x08, 0x7A))
        if (two !is RawCanSignal.Doors || !two.tailgate || !two.rearRight || two.rearLeft) {
            return "tailgate+rear-right vector decoded as $two"
        }

        // capture-2026-09-07-fan-sweep.log — blower running, byte 6 = 0x56
        val fanOn = decode(ID_FAN, frame(0x00, 0x00, 0x01, 0xFF, 0x51, 0x26, 0x56, 0x7B))
        if (fanOn !is RawCanSignal.Fan || !fanOn.running || fanOn.level != 0x56 - FAN_LEVEL_MIN) {
            return "fan-running vector decoded as $fanOn"
        }

        // A short frame must be refused rather than read past its end.
        if (decode(ID_DOOR_STATUS, frame(0x00, 0x01)) != null) {
            return "short frame was not refused"
        }

        // can-1.log, 2026-09-09 drive: the ECU answered 42 km/h at the same instant.
        val fast = decode(ID_SPEED_FAST, frame(0x80, 0x27, 0x56, 0x00, 0x56, 0x00, 0x2A, 0x7B))
        if (fast != RawCanSignal.Speed(42.0)) {
            return "0x361 speed vector decoded as $fast"
        }
        val wheels = decode(ID_WHEEL_SPEEDS, frame(0x2B, 0x39, 0x2B, 0x34, 0x2B, 0x28, 0x2B, 0x12))
        if (wheels !is RawCanSignal.WheelSpeeds || wheels.frKmh !in 42.9..43.1) {
            return "0x0AA wheel vector decoded as $wheels"
        }

        // An id we do not handle must cost nothing.
        if (decode(0x0A4, frame(0, 0, 0, 0, 0, 0, 0, 0)) != null) {
            return "unhandled id did not return null"
        }

        return null
    }
}
