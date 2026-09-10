package com.ripostelabs.carlauncher.carlib

/**
 * ObdPid — the standard OBD-II parameters this car answers, and how to read them.
 *
 * ── Why these exist at all ──────────────────────────────────────────────────────────────────────
 * Every other vehicle value in this project was reverse engineered: a byte watched across a
 * capture, attributed by actuation, then argued about. These are the opposite. They are defined
 * by SAE J1979, the ECU answers them on request, and the number needs no interpretation. Where a
 * standard PID exists, querying it beats decoding a candidate — a single query once overturned an
 * hour of capture work that had confidently mis-identified coolant temperature.
 *
 * ── Which ones, and why not more ────────────────────────────────────────────────────────────────
 * A probe on 2026-09-08 found three responders (`0x7E8`, `0x7EA`, `0x7EE`) supporting sixteen
 * PIDs between them. Only the ones carrying information the bus does not already give up are
 * asked for. Engine rpm and intake air temperature are deliberately absent: the raw bus already
 * carries both, verified, and asking again would spend bus budget to learn nothing.
 *
 * Fuel level is not here because this car does not support it. `0x2F` was absent from every
 * responder's supported-PID bitmap.
 */
enum class ObdPid(
    /** The PID byte, as sent in the service 01 request. */
    val code: Int,
    /** How many data bytes the answer carries. Decides the expected ISO-TP length. */
    val dataBytes: Int,
) {
    /** Calculated engine load, percent. */
    ENGINE_LOAD(0x04, 1),

    /**
     * Engine coolant temperature, degrees C.
     *
     * The reason this whole file is worth having. The launcher had a coolant reading taken from
     * `0x1C4` byte 4 that looked plausible for an hour and was wrong: measured against this PID at
     * the same instant, the ECU said 74 C while that byte said 88 and was falling as real coolant
     * held steady. This is the authoritative source.
     */
    COOLANT_C(0x05, 1),

    /** Road speed, km/h. Kept as the cross-check the raw-bus decode was validated against. */
    SPEED_KMH(0x0D, 1),

    /** Absolute throttle position, percent. */
    THROTTLE_PCT(0x11, 1),
    ;

    /**
     * Turn the answer's data bytes into a real value.
     *
     * [b] is ignored by every single-byte PID and is only meaningful where [dataBytes] is 2.
     * The formulas are J1979's, not this project's, so they carry no uncertainty of ours.
     */
    fun decode(a: Int, b: Int = 0): Double = when (this) {
        ENGINE_LOAD, THROTTLE_PCT -> a * PERCENT_FULL / BYTE_FULL
        COOLANT_C -> (a - TEMP_OFFSET_C).toDouble()
        SPEED_KMH -> a.toDouble()
    }

    private companion object {
        /** J1979 scales a full byte to 100%. */
        const val PERCENT_FULL = 100.0
        const val BYTE_FULL = 255.0

        /** J1979 temperatures are offset so -40 C is zero. */
        const val TEMP_OFFSET_C = 40
    }
}

/**
 * Obd — build one request, and read one answer, for any [ObdPid].
 *
 * This is the whole of what the launcher puts on the vehicle bus. It sends functional requests to
 * `0x7DF`, which every emissions ECU listens to, at a rate bounded by [ObdPoller].
 *
 *     request   7DF # 02 01 <pid> 00 00 00 00 00
 *     reply     7E8 # 03 41 <pid> <A> 00 00 00 00      one data byte
 *               7E8 # 04 41 <pid> <A> <B> 00 00 00     two data bytes
 *     refused   7E8 # 03 7F 01 <NRC>
 *
 * ── Strictness ──────────────────────────────────────────────────────────────────────────────────
 * The response id range carries every ECU's answer to every question, including questions the
 * launcher did not ask. A reply is accepted only when the service, the PID and the length all
 * match a PID we know, so an answer about one parameter can never surface as another. A negative
 * response must never appear as a reading of NRC units.
 */
object Obd {

    /** Functional request id: every emissions ECU listens here. */
    const val REQUEST_ID = 0x7DF

    /** ECU responses land in this range, 0x7E8 for the engine ECU upward. */
    const val RESPONSE_ID_FIRST = 0x7E8
    const val RESPONSE_ID_LAST = 0x7EF

    private const val SERVICE_CURRENT_DATA = 0x01
    private const val SERVICE_POSITIVE_OFFSET = 0x40
    private const val SERVICE_NEGATIVE = 0x7F

    /** ISO-TP single-frame PCI: how many meaningful bytes follow. */
    private const val PCI_BASE = 2

    private const val AT_PCI = 0
    private const val AT_SERVICE = 1
    private const val AT_PID = 2
    private const val AT_VALUE = 3

    private const val REQUEST_LEN = 8
    private const val REQUEST_PCI = 0x02
    private const val PAD = 0x00

    /** What the car said, once it is known to answer a question we actually asked. */
    sealed interface Reply {
        data class Value(val pid: ObdPid, val value: Double, val ecu: Int) : Reply

        /** The ECU refused service 01. [nrc] is the negative response code, for the log. */
        data class Refused(val nrc: Int, val ecu: Int) : Reply
    }

    /** The request frame. Padded to 8 bytes as the shell probe did; some ECUs ignore short ones. */
    fun request(pid: ObdPid): SlcanFrame {
        val data = MutableList(REQUEST_LEN) { PAD }
        data[AT_PCI] = REQUEST_PCI
        data[AT_SERVICE] = SERVICE_CURRENT_DATA
        data[AT_PID] = pid.code

        return SlcanFrame(REQUEST_ID, data)
    }

    /**
     * Interpret a frame. Null means "not an answer to any question we asked", which is the normal
     * result for the overwhelming majority of bus traffic.
     */
    fun parse(frame: SlcanFrame): Reply? {
        if (frame.id !in RESPONSE_ID_FIRST..RESPONSE_ID_LAST) {
            return null
        }
        if (frame.data.size <= AT_VALUE) {
            return null
        }

        val pci = frame.data[AT_PCI]
        val service = frame.data[AT_SERVICE]
        val second = frame.data[AT_PID]

        if (service == SERVICE_NEGATIVE && second == SERVICE_CURRENT_DATA) {
            return Reply.Refused(nrc = frame.data[AT_VALUE], ecu = frame.id)
        }

        if (service != SERVICE_CURRENT_DATA + SERVICE_POSITIVE_OFFSET) {
            return null
        }

        val pid = ObdPid.entries.firstOrNull { it.code == second } ?: return null

        // Length is part of identity, not a formality: a two-byte answer arriving with a
        // one-byte PCI is a malformed or truncated frame, and reading it would invent a value.
        if (pci != PCI_BASE + pid.dataBytes) {
            return null
        }
        if (frame.data.size < AT_VALUE + pid.dataBytes) {
            return null
        }

        val a = frame.data[AT_VALUE]
        val b = if (pid.dataBytes > 1) frame.data[AT_VALUE + 1] else 0

        return Reply.Value(pid = pid, value = pid.decode(a, b), ecu = frame.id)
    }
}

/**
 * ObdRotation — which parameter to ask for next.
 *
 * The launcher asks for several parameters but must not transmit any more often than when it
 * asked for one. Bus budget is the constraint, not curiosity: the body modules on this car are
 * entitled to the wire, and a diagnostic tool that raises the traffic floor is a bad guest. So
 * the send rate stays where [ObdPoller] set it and the questions take turns, which costs each
 * parameter some latency and costs the bus nothing.
 */
class ObdRotation(private val pids: List<ObdPid> = ObdPid.entries) {

    private var next = 0

    /** The next parameter to ask about, advancing the cycle. */
    fun next(): ObdPid {
        val pid = pids[next % pids.size]
        next = (next + 1) % pids.size

        return pid
    }
}

/**
 * ObdPoller — how often a request goes out at all.
 *
 * Bounded on purpose. A read loop turns ~1215 times a second, and asking on every turn would put
 * more traffic on the bus than the car's own body modules do. One request every [intervalMs] is
 * plenty and is invisible to everything else on the wire. [ObdRotation] decides *which* question
 * that request carries, so adding parameters never raises this rate.
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
        /** 2 Hz. With four parameters taking turns, each is answered about every two seconds. */
        const val DEFAULT_INTERVAL_MS = 500L
    }
}
