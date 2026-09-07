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

    // ── Not yet verified on this car ────────────────────────────────────────────────────────────
    // Present so VehicleState stays stable and the LIN/IEBUS path can fill them in. Nothing below
    // is emitted by [RawCanDecoder.decode]; adding one means capturing it first.
    enum class GearPos { P, R, N, D, B, UNKNOWN }
    data class Speed(val kmh: Double) : RawCanSignal()
    data class WheelSpeeds(val flKmh: Double, val frKmh: Double, val rlKmh: Double, val rrKmh: Double) : RawCanSignal()
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

    // ── Byte offsets ────────────────────────────────────────────────────────────────────────────
    private const val DOOR_BYTE = 3
    private const val CLIMATE_A_BYTE = 2
    private const val CLIMATE_B_BYTE = 5
    private const val FAN_BYTE = 6

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

        // An id we do not handle must cost nothing.
        if (decode(0x0B4, frame(0, 0, 0, 0, 0, 0, 0, 0)) != null) {
            return "unhandled id did not return null"
        }

        return null
    }
}
