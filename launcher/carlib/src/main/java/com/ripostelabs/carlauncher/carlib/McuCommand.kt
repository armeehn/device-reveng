package com.ripostelabs.carlauncher.carlib

/**
 * McuCommand — build the byte array that asks the vendor stack to talk to the car.
 *
 * ── The route, and why it is this one ───────────────────────────────────────────────────────────
 * The head unit's own buttons reach the car by broadcasting a framed command; the vendor's port
 * owner receives that broadcast and writes it to the serial link. Nothing guards the receiver: it
 * is registered dynamically with the two-argument form, so no permission applies, and the handler
 * performs no caller check. The vendor's own debug app transmits the identical intent with a
 * plain broadcast, which is the form available to a third-party app.
 *
 * That matters because the alternative was owning the serial port, and opening it alongside the
 * vendor stack is proven harmful: two readers split the byte stream, and a six-second read once
 * stole 682 bytes the vendor stack never received. This route touches nothing.
 *
 * ── Framing: the caller builds the inner frame ONLY ─────────────────────────────────────────────
 *
 *     what we broadcast:   0D 08 | 5A A5 | payload | innerCK
 *     what reaches the wire: 0D 0A | LEN | <all of the above> | outerCK | 00
 *
 * The outer envelope is added by the receiver. Building it here would double it.
 *
 * ── Two checksums, which is the trap ────────────────────────────────────────────────────────────
 * The inner sum is ours: the payload bytes less one, with the `5A A5` header EXCLUDED. The outer
 * sum is the receiver's, a different algorithm over a different range.
 *
 * This is almost certainly why the launcher's own wire-checksum tally reported that its derived
 * formula disagreed with real traffic. That formula was derived from the RECEIVE side and then
 * checked against the wire, where two different sums are in play. **Derive a send checksum from
 * the send path, never from the parse path.**
 *
 * ── Inbound frames are not shaped like outbound ones ────────────────────────────────────────────
 * A frame arriving from the MCU starts `A5 5A A5`, three bytes. One going out starts `5A A5`, two.
 * They are not the same header and must not be shared between the two directions.
 */
object McuCommand {

    /** Intent the vendor's port owner listens on. Read from its own source, not guessed. */
    const val ACTION = "com.szchoiceway.eventcenter.EventUtils.ACTION_MCU_CMD_EVENT"

    /** Extra carrying the framed bytes. */
    const val EXTRA_DATA = "EventUtils.MCU_CMD_DATA"

    /**
     * A read-only poll of the car's settings state, and the safest thing this project can send.
     *
     * It actuates nothing. The head unit already sends it whenever its own settings screen is
     * open, so it adds no traffic pattern the car has not seen. A reply proves three things at
     * once that nothing else separates: the broadcast reaches the port owner, the port owner is
     * willing to write for us, and the gate that drops commands with the ignition off is open.
     *
     * Send this before anything that moves, every time.
     */
    fun settingsQuery(): ByteArray = framed(SETTINGS_QUERY_PAYLOAD)

    /** The opcode a settings reply carries. */
    const val REPLY_OPCODE_CAR_SET = 0x62

    /**
     * The opcode of an inbound MCU frame, or null when this is not one.
     *
     * Deliberately strict about the three-byte inbound header. A caller uses this to decide
     * whether the car answered, and answering that question from a frame we did not really
     * recognise would turn a silent link into a false success.
     */
    fun inboundOpcode(framed: ByteArray): Int? {
        if (framed.size < MIN_INBOUND_LEN) {
            return null
        }
        INBOUND_HEADER.forEachIndexed { index, expected ->
            if (u(framed, index) != expected) {
                return null
            }
        }

        return u(framed, INBOUND_OPCODE_AT)
    }

    /**
     * Wrap a payload as the vendor does: routing prefix, header, payload, inner checksum.
     *
     * Internal rather than public. Every command this project sends should be a named function
     * with a comment saying what it does to the car, because a general "send these bytes" door
     * is how an unreviewed frame reaches a vehicle bus.
     */
    internal fun framed(payload: IntArray): ByteArray {
        val out = ByteArray(PREFIX.size + HEADER.size + payload.size + 1)
        var at = 0

        PREFIX.forEach { out[at++] = it.toByte() }
        HEADER.forEach { out[at++] = it.toByte() }

        var sum = 0
        payload.forEach {
            sum += it
            out[at++] = it.toByte()
        }

        // The header is NOT in the sum. Including it is the obvious mistake and produces a frame
        // the MCU silently drops, which reads exactly like a link that is not working.
        out[at] = ((sum - 1) and BYTE_MASK).toByte()

        return out
    }

    /** Routing prefix for a command that goes to the car, as opposed to the head unit itself. */
    private val PREFIX = intArrayOf(0x0D, 0x08)

    /** Outbound header. Two bytes. Not the inbound one. */
    private val HEADER = intArrayOf(0x5A, 0xA5)

    /** Inbound header. Three bytes. Not the outbound one. */
    private val INBOUND_HEADER = intArrayOf(0xA5, 0x5A, 0xA5)

    private const val INBOUND_OPCODE_AT = 4
    private const val MIN_INBOUND_LEN = 7
    private const val BYTE_MASK = 0xFF

    /** The vendor's own settings poll: length 3, opcode 0x6A, then its three-byte request. */
    private val SETTINGS_QUERY_PAYLOAD = intArrayOf(0x03, 0x6A, 0x05, 0x01, 0x62)

    private fun u(data: ByteArray, at: Int): Int = data[at].toInt() and BYTE_MASK
}
