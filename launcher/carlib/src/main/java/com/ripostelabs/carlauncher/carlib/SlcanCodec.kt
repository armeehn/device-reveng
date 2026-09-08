package com.ripostelabs.carlauncher.carlib

/**
 * SlcanCodec — the LAWICEL ASCII ("slcan") wire format spoken by the CANable 2.0 Pro.
 *
 * ── Why this exists ─────────────────────────────────────────────────────────────────────────────
 * The head unit enumerates the adapter fine (`16d0:117e CANable2 b158aa7` on the USB hub) but its
 * kernel ships **no CDC-ACM driver**: there is no `/dev/ttyACM*`, and `/sys/bus/usb-serial` does
 * not exist at all. Verified 2026-09-08 on both USB ports, so it is not a cabling or port fault.
 * CDC-ACM is only a pair of bulk endpoints, so the launcher claims the device through Android's USB
 * host API and speaks the protocol itself. This file is that protocol and nothing else: pure
 * Kotlin, no Android imports, fully unit-testable at a desk.
 *
 * ── The dialect ─────────────────────────────────────────────────────────────────────────────────
 * Every command is ASCII terminated by CR. The adapter answers a bare CR for OK and BELL for error.
 *
 *     C\r            close the channel
 *     S6\r           set 500 kbit/s (the Toyota body bus)
 *     O\r            open the channel, ACTIVE
 *     t7DF8020...\r  transmit standard frame: id 7DF, 8 bytes
 *
 * ── Do not use listen-only ──────────────────────────────────────────────────────────────────────
 * Firmware `b158aa7` accepts `L` (listen-only) and silently ignores it: the channel never opens,
 * yet the host sees a healthy interface with zero frames AND zero errors. That failure mode cost
 * an hour in the car. Listen-only is therefore deliberately absent from this file — only the
 * active open exists, so the mistake cannot be made from code.
 */

/** Channel bitrate. Codes are from the LAWICEL spec, not chosen by us. */
enum class SlcanBitrate(val code: String) {
    KBIT_125("S4"),
    KBIT_250("S5"),

    /** The Toyota body bus behind the head unit. */
    KBIT_500("S6"),
    MBIT_1("S8"),
}

/** CAN identifier width. */
enum class IdFormat {
    STANDARD,
    EXTENDED,
}

/** One CAN data frame. `data` holds unsigned byte values so equality is by value, not identity. */
data class SlcanFrame(
    val id: Int,
    val data: List<Int>,
    val format: IdFormat = IdFormat.STANDARD,
) {
    /** Canonical `7DF#02 01 0D` rendering, for logs and captures. */
    fun render(): String {
        val width = if (format == IdFormat.EXTENDED) EXT_ID_DIGITS else STD_ID_DIGITS
        val idHex = id.toString(RADIX_HEX).uppercase().padStart(width, '0')
        return idHex + "#" + data.joinToString(" ") { hex2(it) }
    }
}

/** What a single terminated line from the adapter meant. */
sealed class SlcanEvent {
    data class Received(val frame: SlcanFrame) : SlcanEvent()

    /** Bare CR: the previous command succeeded. */
    object Ack : SlcanEvent()

    /** BELL: the previous command was rejected. */
    object Rejected : SlcanEvent()

    /** A version/serial banner, or a malformed frame we refuse to guess at. */
    data class Text(val text: String) : SlcanEvent()
}

private const val RADIX_HEX = 16
private const val STD_ID_DIGITS = 3
private const val EXT_ID_DIGITS = 8
private const val MAX_DLC = 8
private const val BYTE_MASK = 0xFF
private const val CR = '\r'
private const val BELL = '\u0007'

/** Longest line worth keeping: `T` + 8 id + 1 dlc + 16 data = 26. Anything longer is line noise. */
private const val MAX_LINE = 32

private fun hex2(value: Int): String =
    (value and BYTE_MASK).toString(RADIX_HEX).uppercase().padStart(2, '0')

object SlcanCodec {

    private const val CMD_OPEN_ACTIVE = "O"
    private const val CMD_CLOSE = "C"

    /** Close, set bitrate, open. In this order because the adapter refuses `S` while open. */
    fun startup(bitrate: SlcanBitrate): List<ByteArray> =
        listOf(line(CMD_CLOSE), line(bitrate.code), line(CMD_OPEN_ACTIVE))

    fun shutdown(): ByteArray = line(CMD_CLOSE)

    /** Encode a frame for transmission, e.g. OBD request `7DF#02 01 0D 00 00 00 00 00`. */
    fun transmit(frame: SlcanFrame): ByteArray {
        require(frame.data.size <= MAX_DLC) { "DLC ${frame.data.size} exceeds $MAX_DLC" }

        val prefix = if (frame.format == IdFormat.EXTENDED) "T" else "t"
        val width = if (frame.format == IdFormat.EXTENDED) EXT_ID_DIGITS else STD_ID_DIGITS

        val body = StringBuilder(prefix)
        body.append(frame.id.toString(RADIX_HEX).uppercase().padStart(width, '0'))
        body.append(frame.data.size.toString(RADIX_HEX).uppercase())
        for (b in frame.data) {
            body.append(hex2(b))
        }

        return line(body.toString())
    }

    /**
     * Interpret one CR-terminated line. A malformed *frame* comes back as [SlcanEvent.Text] rather
     * than being dropped, so corruption is visible in a log instead of looking like silence.
     */
    fun decode(line: String): SlcanEvent {
        if (line.isEmpty()) return SlcanEvent.Ack

        val frame = parseFrame(line) ?: return SlcanEvent.Text(line)
        return SlcanEvent.Received(frame)
    }

    /**
     * Strict frame parse. Every field must be present at exactly its declared width — a truncated
     * read must not decode as a short frame, because a short frame looks like real data.
     */
    private fun parseFrame(line: String): SlcanFrame? {
        val format = when (line.first()) {
            't' -> IdFormat.STANDARD
            'T' -> IdFormat.EXTENDED
            else -> return null
        }

        val idDigits = if (format == IdFormat.EXTENDED) EXT_ID_DIGITS else STD_ID_DIGITS
        val headerLength = 1 + idDigits + 1
        if (line.length < headerLength) return null

        val id = line.substring(1, 1 + idDigits).hexOrNull() ?: return null
        val dlc = line.substring(1 + idDigits, headerLength).hexOrNull() ?: return null
        if (dlc > MAX_DLC) return null

        // The payload must occupy the rest of the line exactly: no short read, no trailing junk.
        val payload = line.substring(headerLength)
        if (payload.length != dlc * 2) return null

        val data = ArrayList<Int>(dlc)
        for (i in 0 until dlc) {
            data.add(payload.substring(i * 2, i * 2 + 2).hexOrNull() ?: return null)
        }

        return SlcanFrame(id, data, format)
    }

    private fun line(body: String): ByteArray = (body + CR).toByteArray(Charsets.US_ASCII)

    /** `toIntOrNull(16)` accepts a leading '+' or '-'; the wire format does not. */
    private fun String.hexOrNull(): Int? {
        if (isEmpty()) return null
        if (any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null

        return toInt(RADIX_HEX)
    }
}

/**
 * Reassembles CR-delimited lines out of arbitrarily chopped bulk-transfer reads.
 *
 * A USB bulk read returns whatever happened to be sitting in the endpoint buffer, so one read
 * routinely ends mid-frame and the next carries the tail. Feeding raw reads straight to
 * [SlcanCodec.decode] would drop or corrupt a frame at every buffer boundary.
 */
class SlcanReader {

    /** Whether the bytes arriving now belong to a line worth keeping. */
    private enum class LineState {
        COLLECTING,

        /** An overlong line was seen; skip to the next delimiter rather than resync mid-frame. */
        DISCARDING,
    }

    private val pending = StringBuilder()
    private var state = LineState.COLLECTING

    /** Push one bulk read; returns every event that completed within it. */
    fun feed(bytes: ByteArray, length: Int = bytes.size): List<SlcanEvent> {
        val events = ArrayList<SlcanEvent>()

        for (i in 0 until length) {
            val c = (bytes[i].toInt() and BYTE_MASK).toChar()

            // BELL is its own terminator: it answers a command, so whatever preceded it is junk.
            if (c == CR || c == BELL) {
                if (state == LineState.COLLECTING) {
                    events.add(if (c == BELL) SlcanEvent.Rejected else SlcanCodec.decode(pending.toString()))
                }

                pending.setLength(0)
                state = LineState.COLLECTING
                continue
            }

            if (state == LineState.DISCARDING) {
                continue
            }

            // A line this long is not a frame. Drop it, and stay dropping until the next
            // delimiter: restarting here would splice garbage onto the front of a real frame.
            if (pending.length >= MAX_LINE) {
                pending.setLength(0)
                state = LineState.DISCARDING
                continue
            }

            pending.append(c)
        }

        return events
    }
}
