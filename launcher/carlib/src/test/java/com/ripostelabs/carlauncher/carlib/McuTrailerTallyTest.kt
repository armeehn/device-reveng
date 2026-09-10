package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trailer check is the one claim in McuSerial the car can answer without the port being
 * opened, so its two failure modes are pinned harder than its success: a disagreement must be
 * reported at once with the body, and a body too short to carry a CK must never count as one.
 */
class McuTrailerTallyTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** A wire frame with header, LEN and pad stripped: what eventcenter broadcasts. */
    private fun body(opcode: Int, payload: ByteArray): ByteArray {
        val wire = McuSerial.encode(opcode, payload)
        return wire.copyOfRange(3, wire.size - 1)
    }

    private val CAN_BODY = body(McuSerial.OP_CAN, McuFrame.encode(0x32, bytes(0x00, 0x00, 0x05, 0x14)))

    @Test
    fun `a broadcast body ends in the outer CK`() {
        // Hand-summed twin of the McuSerialTest vector: body 7E 01 7D, LEN is the body's size, 3.
        assertEquals(McuSerial.Trailer.AGREES, McuSerial.trailer(bytes(0x7E, 0x01, 0x7D)))
        assertEquals(McuSerial.Trailer.AGREES, McuSerial.trailer(CAN_BODY))
    }

    @Test
    fun `the first disagreement is due at once, with the body`() {
        val tally = McuTrailerTally(every = 1000)
        assertEquals(McuTrailerTally.Report.QUIET, tally.onBody(CAN_BODY))

        val flipped = CAN_BODY.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0xFF).toByte() }
        assertEquals(McuTrailerTally.Report.DUE, tally.onBody(flipped))

        assertEquals(1, tally.agrees)
        assertEquals(1, tally.disagrees)
        assertTrue(tally.summary(), tally.summary().contains("DISAGREES"))
        assertTrue(tally.summary(), tally.summary().contains("samples=[A5 5A A5 04 32"))
    }

    @Test
    fun `a report is due every nth body`() {
        val tally = McuTrailerTally(every = 4)

        val reports = (1..8).map { tally.onBody(CAN_BODY) }

        assertEquals(
            listOf(
                McuTrailerTally.Report.QUIET, McuTrailerTally.Report.QUIET, McuTrailerTally.Report.QUIET, McuTrailerTally.Report.DUE,
                McuTrailerTally.Report.QUIET, McuTrailerTally.Report.QUIET, McuTrailerTally.Report.QUIET, McuTrailerTally.Report.DUE,
            ),
            reports,
        )
        assertTrue(tally.summary(), tally.summary().contains("HOLDS"))
    }

    // ── Negative controls ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a body too short for a CK is short, not a disagreement`() {
        assertEquals(McuSerial.Trailer.SHORT, McuSerial.trailer(bytes(0x7E)))
        assertEquals(McuSerial.Trailer.SHORT, McuSerial.trailer(ByteArray(0)))

        val tally = McuTrailerTally()
        tally.onBody(bytes(0x7E))
        assertEquals(1, tally.short)
        assertEquals(0, tally.disagrees)
        assertTrue(tally.summary(), tally.summary().contains("HOLDS"))
    }

    @Test
    fun `a wrong CK is a disagreement whatever the opcode`() {
        assertEquals(McuSerial.Trailer.DISAGREES, McuSerial.trailer(bytes(0x7E, 0x01, 0x7C)))
    }

    @Test
    fun `only the first samples are kept`() {
        val tally = McuTrailerTally()
        val bad = bytes(0x7E, 0x01, 0x00)
        repeat(5) { tally.onBody(bad) }

        assertEquals(3, tally.samples.size)
        assertEquals(5, tally.disagrees)
    }

    @Test
    fun `nothing seen is not a verdict`() {
        assertTrue(McuTrailerTally().summary().contains("no bodies yet"))
    }

    /**
     * The car sent back 861 agreements, 139 disagreements, and `00 00 00` as the disagreeing
     * body. That body carries no opcode, no payload and no checksum. It cleared the length gate,
     * failed arithmetic it was never part of, and counted against the formula.
     *
     * Counting padding as evidence is the more dangerous of the two possible errors here: it
     * would send someone to rewrite arithmetic that works.
     */
    @Test
    fun `an all-zero body is not evidence against the formula`() {
        val tally = McuTrailerTally()

        repeat(5) { tally.onBody(byteArrayOf(0, 0, 0)) }

        assertEquals(0, tally.disagrees)
        assertEquals(0, tally.agrees)
        assertEquals(5, tally.blank)
    }

    /** Padding is reported, not hidden: a stream that is mostly padding is worth knowing about. */
    @Test
    fun `blank bodies are reported in the summary`() {
        val tally = McuTrailerTally()

        tally.onBody(byteArrayOf(0, 0, 0))

        assertTrue(tally.summary(), tally.summary().contains("blank=1"))
    }

    /** A real body still counts, and one zero byte inside it does not make it padding. */
    @Test
    fun `a body with any non-zero byte is still judged`() {
        val tally = McuTrailerTally()

        tally.onBody(byteArrayOf(0x11, 0x00, 0x00))

        assertEquals(0, tally.blank)
        assertEquals(1, tally.agrees + tally.disagrees)
    }

    /**
     * Every kept sample reaches the log, not just the first. One body says the formula disagreed
     * somewhere; several say whether the disagreements look alike, which is the difference
     * between a bug to fix and a stream to filter.
     */
    @Test
    fun `the summary carries every kept sample`() {
        val tally = McuTrailerTally()

        tally.onBody(byteArrayOf(0x11, 0x22, 0x33))
        tally.onBody(byteArrayOf(0x44, 0x55, 0x66))
        val line = tally.summary()

        assertTrue(line, line.contains("11 22 33"))
        assertTrue(line, line.contains("44 55 66"))
        assertTrue(line, line.contains("samples=["))
    }
}
