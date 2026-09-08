package com.ripostelabs.carlauncher.carlib

/**
 * The head-unit → MCU frame format, encoded and decoded here rather than only observed.
 *
 * ── Why this exists ─────────────────────────────────────────────────────────────────────────────
 * Today the launcher can only *watch* the vendor stack: `HiworldCanDecoder` parses the digest the
 * MCU broadcasts, and everything else is read-only. This is the other direction. The MCU's serial
 * port is world-readable on this unit, so anything that can speak this framing can talk to the car
 * without the vendor apps in the path at all.
 *
 * That matters well beyond a feature. The standing conclusion about a custom system was that the
 * car functions "effectively break" because the Choiceway apps fail without their platform-signed
 * settings provider. But those apps are only necessary because they own this port. With the framing
 * known, that argument weakens considerably — see CUSTOM_ANDROID.md.
 *
 * ── Format, derived from captured frames rather than assumed ────────────────────────────────────
 *
 *     5A A5 | len | cmd | payload[len] | checksum
 *
 * `len` counts the payload only — not the header, the command or the checksum, so a whole frame is
 * `len + 5` bytes. `checksum` is the low byte of the sum of every preceding byte, header included.
 *
 * The checksum was not guessed. Two frames were logged from `SendCmdLstToCanbus`, identical except
 * that one payload byte differed by 5 — and their trailers differed by exactly 5 too. A linear
 * relationship like that rules out a CRC or an XOR, and a plain sum then reproduces both trailers
 * exactly. Both frames are pinned as test vectors.
 *
 * ── The inbound direction is NOT this ───────────────────────────────────────────────────────────
 * Frames the MCU sends *to* Android use a different framing — a three-byte `A5 5A A5` header and a
 * two-byte trailer — and are [HiworldCanDecoder]'s job. Do not feed one to the other; the headers
 * are similar enough to look interchangeable and are not.
 *
 * **Nothing here writes to a serial port.** This is the codec alone, so it can be tested without a
 * car. Transmission is a separate, deliberate decision.
 */
object McuFrame {

    /** Outbound header. The inbound one is three bytes and belongs to HiworldCanDecoder. */
    private val HEADER = byteArrayOf(0x5A, 0xA5.toByte())

    /** header(2) + len(1) + cmd(1) + checksum(1) */
    private const val OVERHEAD = 5

    /** `len` is a single byte, so a payload cannot exceed this. */
    const val MAX_PAYLOAD = 255

    /**
     * Build a frame for [cmd] carrying [payload].
     *
     * @throws IllegalArgumentException if the payload cannot be described by a one-byte length.
     */
    fun encode(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= MAX_PAYLOAD) {
            "payload is ${payload.size} bytes; len is one byte so $MAX_PAYLOAD is the ceiling"
        }
        require(cmd in 0..0xFF) { "cmd $cmd does not fit in a byte" }

        val out = ByteArray(payload.size + OVERHEAD)
        out[0] = HEADER[0]
        out[1] = HEADER[1]
        out[2] = payload.size.toByte()
        out[3] = cmd.toByte()
        payload.copyInto(out, destinationOffset = 4)
        out[out.size - 1] = checksum(out, out.size - 1)
        return out
    }

    /** Low byte of the sum of [count] bytes from the start of [bytes]. */
    private fun checksum(bytes: ByteArray, count: Int): Byte {
        var sum = 0
        for (i in 0 until count) {
            sum += bytes[i].toInt() and 0xFF
        }
        return (sum and 0xFF).toByte()
    }

    /** A frame that failed to parse, and why. Callers log the reason rather than guessing. */
    sealed interface Decoded {
        data class Frame(val cmd: Int, val payload: ByteArray) : Decoded {
            // ByteArray equality is identity, which would make this data class lie.
            override fun equals(other: Any?): Boolean =
                other is Frame && cmd == other.cmd && payload.contentEquals(other.payload)

            override fun hashCode(): Int = 31 * cmd + payload.contentHashCode()
        }

        data class Malformed(val reason: String) : Decoded
    }

    /**
     * Parse one complete frame from the start of [bytes].
     *
     * A bad checksum is reported rather than tolerated. On a link that also carries frames we did
     * not send, accepting a frame whose checksum disagrees means acting on a misread — and the
     * only thing downstream of this is the car.
     */
    fun decode(bytes: ByteArray): Decoded {
        if (bytes.size < OVERHEAD) {
            return Decoded.Malformed("frame is ${bytes.size} bytes; the minimum is $OVERHEAD")
        }
        if (bytes[0] != HEADER[0] || bytes[1] != HEADER[1]) {
            return Decoded.Malformed("bad header; expected 5A A5")
        }
        val len = bytes[2].toInt() and 0xFF
        val expected = len + OVERHEAD
        if (bytes.size < expected) {
            return Decoded.Malformed("len says $len, so $expected bytes, but got ${bytes.size}")
        }
        val actual = checksum(bytes, expected - 1)
        if (actual != bytes[expected - 1]) {
            return Decoded.Malformed(
                "checksum ${"0x%02X".format(bytes[expected - 1])} does not match " +
                    "${"0x%02X".format(actual)}"
            )
        }
        return Decoded.Frame(
            cmd = bytes[3].toInt() and 0xFF,
            payload = bytes.copyOfRange(4, 4 + len),
        )
    }
}
