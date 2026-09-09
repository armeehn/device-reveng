package com.ripostelabs.carlauncher.carlib

/**
 * McuSerial — the framing on `/dev/ttyS1` itself, between the head unit's Android and its MCU.
 *
 * ── Where this sits ─────────────────────────────────────────────────────────────────────────────
 *
 *     /dev/ttyS1 bytes ──▶ McuSerialReader ──▶ McuSerial.Command(opcode, payload)
 *                                                       │
 *                                         opcode 0xA5   ▼   innerFrame()
 *                                              McuFrame.Decoded ──▶ HiworldCanDecoder.decodePayload
 *
 * ── Wire format, from the vendor's own port owner ───────────────────────────────────────────────
 * `android.serialport.SerialPortManager.sendDataEx` in com.szchoiceway.eventcenter writes this and
 * `SerialReadThread.parseRxData` reads it:
 *
 *     0D 0A | LEN | OPCODE | payload[LEN-1] | CK | 00
 *
 * LEN counts the opcode, the payload and CK itself. CK is the bitwise NOT of the low byte of
 * LEN + OPCODE + payload. The trailing 00 is a pad the writer appends. The reader syncs on `0D 0A`,
 * takes LEN bytes and hands them up with CK still attached; its decompile is too mangled to show
 * whether it checks CK at all.
 *
 * ── Why "A5 5A A5" was never a three-byte header ────────────────────────────────────────────────
 * Opcode 0xA5 is `onCmdCanEvent`: its payload is the CAN box's own frame, `5A A5 | len | cmd |
 * payload | ck` — the [McuFrame] format, in this direction too. What canbus2 receives by broadcast
 * is the body with its opcode still in front and CK still behind, so it sees `A5 5A A5 … C1 C2`
 * and [HiworldCanDecoder] parses it that way: its "C2 pad" is this CK. Same bytes, one framing
 * inside another.
 *
 * ── Status ──────────────────────────────────────────────────────────────────────────────────────
 * Derived from the decompile. No byte has been read off the port yet: the vendor stack owns it,
 * and reading beside it corrupts both. So a checksum mismatch is *reported*, with both values and
 * the body intact, never silently dropped — the first real capture will say in one line whether
 * the inbound formula holds. Nothing here opens a port.
 */
object McuSerial {

    private const val HEADER_0: Byte = 0x0D
    private const val HEADER_1: Byte = 0x0A
    private const val PAD: Byte = 0x00

    /** header(2) + LEN(1) + OPCODE(1) + CK(1) + pad(1) around the payload. */
    private const val OVERHEAD = 6

    /** LEN is one byte and counts the opcode and CK too, so the payload has two bytes less. */
    const val MAX_PAYLOAD = 253

    /** Opcode and CK: the least LEN can honestly claim. */
    private const val LEN_MIN = 2

    /** The MCU relays a CAN-box frame under this opcode; see [Command.innerFrame]. */
    const val OP_CAN = 0xA5

    /** What the reader hands back, in wire order. */
    sealed interface Event

    /** One body that framed and summed correctly. */
    data class Command(val opcode: Int, val payload: ByteArray) : Event {
        // ByteArray equality is identity, which would make this data class lie.
        override fun equals(other: Any?): Boolean =
            other is Command && opcode == other.opcode && payload.contentEquals(other.payload)

        override fun hashCode(): Int = 31 * opcode + payload.contentHashCode()

        /**
         * The CAN-box frame inside an [OP_CAN] command, or null for any other opcode. Trailing
         * bytes after the inner frame are tolerated, exactly as [HiworldCanDecoder.decodeFrame]
         * ignores its "C2": the inner len says where the frame ends.
         */
        fun innerFrame(): McuFrame.Decoded? {
            if (opcode != OP_CAN) {
                return null
            }
            return McuFrame.decode(payload)
        }
    }

    /**
     * A body whose CK disagreed with the formula above. Carried whole: on the day the port is
     * read for real, this is the evidence either way.
     */
    data class BadChecksum(val command: Command, val expected: Int, val actual: Int) : Event

    /** Bytes that framed nothing: junk between frames, or a frame abandoned mid-resync. */
    data class Skipped(val bytes: Int) : Event

    /**
     * Build a frame for [opcode] carrying [payload], byte for byte what `sendDataEx` writes.
     *
     * @throws IllegalArgumentException if the body cannot be described by a one-byte LEN.
     */
    fun encode(opcode: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= MAX_PAYLOAD) {
            "payload is ${payload.size} bytes; LEN counts the opcode and CK too, so $MAX_PAYLOAD is the ceiling"
        }
        require(opcode in 0..0xFF) { "opcode $opcode does not fit in a byte" }

        val out = ByteArray(payload.size + OVERHEAD)
        out[0] = HEADER_0
        out[1] = HEADER_1
        out[2] = (payload.size + LEN_MIN).toByte()
        out[3] = opcode.toByte()
        payload.copyInto(out, destinationOffset = 4)
        out[out.size - 2] = checksum(out, 2, out.size - 2).toByte()
        out[out.size - 1] = PAD
        return out
    }

    /** `~(LEN + OPCODE + payload)` low byte, over `bytes[from until until]`. */
    private fun checksum(bytes: ByteArray, from: Int, until: Int): Int {
        var sum = 0
        for (i in from until until) {
            sum += bytes[i].toInt() and 0xFF
        }
        return sum.inv() and 0xFF
    }

    private fun isHeader(bytes: ByteArray, at: Int): Boolean =
        at + 1 < bytes.size && bytes[at] == HEADER_0 && bytes[at + 1] == HEADER_1

    /**
     * Turns the port's byte stream into [Event]s, whatever the read boundaries are.
     *
     * ── Resync policy ───────────────────────────────────────────────────────────────────────────────
     * The vendor reader trusts LEN blindly. This one does not: a body that fails its CK is reported,
     * then the scan resumes right after that header's `0D 0A`, inside the bytes just rejected. A
     * corrupt LEN therefore cannot swallow the real frame behind it, and a byte-flip costs one frame,
     * not two. Waiting is bounded because LEN is a byte: at most 259 bytes sit behind a header.
     *
     * The writer's trailing pad is consumed as part of the frame even when a read boundary splits it
     * off, so a quiet link produces no [Skipped] noise.
     */
    class Reader {

        private var pending = ByteArray(0)

        /** The last frame's pad may still be in flight; a leading 00 is then not junk. */
        private var padDue = false

        /** Push one read; returns every event that completed within it, in wire order. */
        fun feed(bytes: ByteArray, length: Int = bytes.size): List<Event> {
            pending = pending + bytes.copyOf(length)

            val events = ArrayList<Event>()
            var skipped = 0
            var pos = 0

            // Bytes below this index were already reported, as a command or a rejection; a
            // rescan through a rejected body must not count them as junk a second time.
            var reported = 0

            if (padDue && pending.isNotEmpty()) {
                if (pending[0] == PAD) {
                    pos = 1
                }
                padDue = false
            }

            while (true) {
                val h = findHeader(pos)
                if (h < 0) {
                    // Nothing frames the tail. Keep a lone CR: its LF may open the next read.
                    // Never reach back past pos: a frame's own CK can be 0D, and it is spent.
                    val keep = if (pending.isNotEmpty() && pending.last() == HEADER_0) 1 else 0
                    val tail = maxOf(pos, pending.size - keep)
                    skipped += maxOf(0, tail - maxOf(pos, reported))
                    pos = tail
                    break
                }
                skipped += maxOf(0, h - maxOf(pos, reported))

                // LEN, then everything it counts, must all be here before anything is decided.
                val lenAt = h + 2
                if (lenAt >= pending.size) {
                    pos = h
                    break
                }
                val len = pending[lenAt].toInt() and 0xFF
                val ckAt = lenAt + len
                if (ckAt >= pending.size) {
                    pos = h
                    break
                }

                // A body needs an opcode and a CK. Less is junk wearing a header; step past the
                // header only.
                if (len < LEN_MIN) {
                    skipped += 2
                    pos = h + 2
                    continue
                }

                val command = Command(
                    opcode = pending[lenAt + 1].toInt() and 0xFF,
                    payload = pending.copyOfRange(lenAt + 2, ckAt),
                )
                val expected = checksum(pending, lenAt, ckAt)
                val actual = pending[ckAt].toInt() and 0xFF

                if (skipped > 0) {
                    events.add(Skipped(skipped))
                    skipped = 0
                }

                // The frame ends after CK, plus the writer's pad when it has arrived.
                var end = ckAt + 1
                if (end < pending.size && pending[end] == PAD) {
                    end++
                }

                if (expected != actual) {
                    events.add(BadChecksum(command, expected, actual))
                    reported = end
                    pos = h + 2
                    continue
                }

                events.add(command)
                reported = end
                pos = end
                padDue = end == ckAt + 1 && end == pending.size
            }

            if (skipped > 0) {
                events.add(Skipped(skipped))
            }
            pending = pending.copyOfRange(pos, pending.size)
            return events
        }

        /** Index of the next `0D 0A` at or after [from], or -1. */
        private fun findHeader(from: Int): Int {
            for (i in from until pending.size) {
                if (isHeader(pending, i)) {
                    return i
                }
            }
            return -1
        }
    }
}
