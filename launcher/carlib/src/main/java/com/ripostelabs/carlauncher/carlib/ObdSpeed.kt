package com.ripostelabs.carlauncher.carlib

/**
 * ObdSpeed — ask the car how fast it is going, and believe only a well-formed answer.
 *
 * ── Why this exists ─────────────────────────────────────────────────────────────────────────────
 * Every speed the launcher decodes is a candidate. `0x32`, `0x17` and `0x13` were all disproved
 * by real drives, and GPS — the old reference — drops out for minutes at a time. Checking a
 * candidate needs a number the car itself vouches for. OBD PID `0x0D` is exactly that: the ECU's
 * own integer km/h, answered on request. It is the reference the raw-bus decode was validated
 * against, and the one any future decode gets validated against.
 *
 * ── The one frame the launcher transmits ────────────────────────────────────────────────────────
 * This is the only code path that puts anything on the vehicle bus. It sends one functional
 * request, `0x7DF`, the same frame the shell probe sent from the car on 2026-09-08, at a bounded
 * rate. The listen-only mode that would have made transmission impossible does not work on this
 * firmware, so the guard is that there is nothing else here to send.
 *
 *     request   7DF # 02 01 0D 00 00 00 00 00      service 01 (current data), PID 0D
 *     reply     7E8 # 03 41 0D <kmh> 00 00 00 00   service 41 = 01 + 0x40, then the PID, then km/h
 *     refused   7E8 # 03 7F 01 <NRC>               negative response for service 01
 *
 * ── Strictness ──────────────────────────────────────────────────────────────────────────────────
 * The response id range carries every ECU's answer to every PID. A reply to a different PID —
 * RPM is `41 0C`, one byte away — must be null rather than a speed, and a negative response must
 * never surface as a speed of `NRC` km/h. Every field is checked before a number comes out.
 */
object ObdSpeed {

    /** Functional request id: every emissions ECU listens here. */
    const val REQUEST_ID = 0x7DF

    /** ECU responses land in this range, 0x7E8 for the engine ECU upward. */
    const val RESPONSE_ID_FIRST = 0x7E8
    const val RESPONSE_ID_LAST = 0x7EF

    private const val SERVICE_CURRENT_DATA = 0x01
    private const val SERVICE_POSITIVE_OFFSET = 0x40
    private const val SERVICE_NEGATIVE = 0x7F
    private const val PID_VEHICLE_SPEED = 0x0D

    /** ISO-TP single-frame PCI: the number of meaningful bytes that follow. */
    private const val PCI_SINGLE_FRAME_3 = 0x03

    /** Byte positions within a single-frame payload. */
    private const val AT_PCI = 0
    private const val AT_SERVICE = 1
    private const val AT_PID = 2
    private const val AT_VALUE = 3

    private const val REQUEST_LEN = 8
    private const val REPLY_MIN_LEN = 4
    private const val PAD = 0x00

    /** What the car said, once it is known to be an answer to *this* question. */
    sealed interface Reply {
        /** [kmh] is the ECU's integer speed; [ecu] is which one answered (0x7E8 = engine). */
        data class Speed(val kmh: Int, val ecu: Int) : Reply

        /** The ECU refused service 01. [nrc] is the negative response code, for the log. */
        data class Refused(val nrc: Int, val ecu: Int) : Reply
    }

    /** The request frame. Padded to 8 bytes as the shell probe did; some ECUs ignore short ones. */
    fun request(): SlcanFrame {
        val data = MutableList(REQUEST_LEN) { PAD }
        data[AT_PCI] = 0x02
        data[AT_SERVICE] = SERVICE_CURRENT_DATA
        data[AT_PID] = PID_VEHICLE_SPEED

        return SlcanFrame(REQUEST_ID, data)
    }

    /**
     * Interpret a frame. Null means "not an answer to our question" — a frame from elsewhere on
     * the bus, another PID, another service — and is the normal result for most of the traffic.
     */
    fun parse(frame: SlcanFrame): Reply? {
        if (frame.id !in RESPONSE_ID_FIRST..RESPONSE_ID_LAST) {
            return null
        }
        if (frame.data.size < REPLY_MIN_LEN) {
            return null
        }
        if (frame.data[AT_PCI] != PCI_SINGLE_FRAME_3) {
            return null
        }

        val service = frame.data[AT_SERVICE]
        val second = frame.data[AT_PID]
        val value = frame.data[AT_VALUE]

        if (service == SERVICE_CURRENT_DATA + SERVICE_POSITIVE_OFFSET && second == PID_VEHICLE_SPEED) {
            return Reply.Speed(kmh = value, ecu = frame.id)
        }

        // A refusal of service 01. Which PID was refused is not in the frame, so this could be
        // an answer to someone else's request — but the launcher only ever asks one question.
        if (service == SERVICE_NEGATIVE && second == SERVICE_CURRENT_DATA) {
            return Reply.Refused(nrc = value, ecu = frame.id)
        }

        return null
    }
}

/**
 * ObdPoller — how often the one transmitted frame goes out.
 *
 * Bounded on purpose. A read loop turns ~1215 times a second, and asking the ECU on every turn
 * would put more traffic on the bus than the car's own body modules do. One request every
 * [intervalMs] is plenty for a cross-check and invisible to everything else on the wire.
 */
class ObdPoller(private val intervalMs: Long = DEFAULT_INTERVAL_MS) {

    private var lastSentAt: Long? = null

    /** Whether to send now. Saying yes records the send, so ask only when about to transmit. */
    fun shouldSend(now: Long): Boolean {
        val last = lastSentAt
        if (last != null && now - last < intervalMs) {
            return false
        }

        lastSentAt = now
        return true
    }

    private companion object {
        /** 2 Hz: two ECU answers a second against ~10 Hz `0x13` is a comfortable fit density. */
        const val DEFAULT_INTERVAL_MS = 500L
    }
}
