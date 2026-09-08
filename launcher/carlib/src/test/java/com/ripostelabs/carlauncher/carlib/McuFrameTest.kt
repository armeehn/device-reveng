package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the head-unit → MCU framing against **frames actually logged from the vendor app**, not
 * invented bytes. Both came from `SendCmdLstToCanbus` on 2026-09-07.
 *
 * They are also what proved the checksum. The two differ in exactly one payload byte, by 5, and
 * their trailers differ by exactly 5 — which rules out a CRC or an XOR and leaves a plain sum,
 * which then reproduces both trailers exactly.
 */
class McuFrameTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** Logged verbatim: 5a a5 0a cb 00 00 0a 00 00 01 18 0c 1b 00 1e */
    private val LOGGED_1 = bytes(0x5A, 0xA5, 0x0A, 0xCB,
        0x00, 0x00, 0x0A, 0x00, 0x00, 0x01, 0x18, 0x0C, 0x1B, 0x00, 0x1E)

    /** The same command with one payload byte 5 higher; the trailer is 5 higher too. */
    private val LOGGED_2 = bytes(0x5A, 0xA5, 0x0A, 0xCB,
        0x00, 0x00, 0x0F, 0x00, 0x00, 0x01, 0x18, 0x0C, 0x1B, 0x00, 0x23)

    private val PAYLOAD_1 = bytes(0x00, 0x00, 0x0A, 0x00, 0x00, 0x01, 0x18, 0x0C, 0x1B, 0x00)

    @Test
    fun `encoding reproduces a frame the vendor app really sent`() {
        assertArrayEquals(LOGGED_1, McuFrame.encode(0xCB, PAYLOAD_1))
    }

    @Test
    fun `the second logged frame reproduces too`() {
        val p = PAYLOAD_1.copyOf().also { it[2] = 0x0F }
        assertArrayEquals(LOGGED_2, McuFrame.encode(0xCB, p))
    }

    /** The relationship that identified the algorithm: +5 in, +5 out. */
    @Test
    fun `the checksum is linear in the payload, which is what ruled out a CRC`() {
        val a = McuFrame.encode(0xCB, PAYLOAD_1)
        val b = McuFrame.encode(0xCB, PAYLOAD_1.copyOf().also { it[2] = 0x0F })
        val delta = (b.last().toInt() and 0xFF) - (a.last().toInt() and 0xFF)
        assertEquals(5, delta)
    }

    @Test
    fun `len counts the payload only, so a frame is len plus five`() {
        assertEquals(5, McuFrame.encode(0x11).size)
        assertEquals(0, McuFrame.encode(0x11)[2].toInt())
        assertEquals(9, McuFrame.encode(0x11, ByteArray(4)).size)
    }

    @Test
    fun `a round trip returns the command and payload unchanged`() {
        val d = McuFrame.decode(McuFrame.encode(0x31, PAYLOAD_1))
        assertTrue(d is McuFrame.Decoded.Frame)
        d as McuFrame.Decoded.Frame
        assertEquals(0x31, d.cmd)
        assertArrayEquals(PAYLOAD_1, d.payload)
    }

    @Test
    fun `a logged frame decodes to its command`() {
        val d = McuFrame.decode(LOGGED_1) as McuFrame.Decoded.Frame
        assertEquals(0xCB, d.cmd)
        assertArrayEquals(PAYLOAD_1, d.payload)
    }

    /** A corrupted frame must be refused, not acted on — the car is downstream of this. */
    @Test
    fun `a wrong checksum is rejected rather than tolerated`() {
        val bad = LOGGED_1.copyOf().also { it[it.size - 1] = 0x00 }
        val d = McuFrame.decode(bad)
        assertTrue(d is McuFrame.Decoded.Malformed)
        assertTrue((d as McuFrame.Decoded.Malformed).reason.contains("checksum"))
    }

    /** A flipped payload bit changes the sum, so it must fail even though the length is right. */
    @Test
    fun `a corrupted payload fails the checksum`() {
        val bad = LOGGED_1.copyOf().also { it[6] = 0x0B }
        assertTrue(McuFrame.decode(bad) is McuFrame.Decoded.Malformed)
    }

    @Test
    fun `the inbound header is not accepted here`() {
        // A5 5A A5 is the MCU -> Android framing and belongs to HiworldCanDecoder.
        val inbound = bytes(0xA5, 0x5A, 0xA5, 0x04, 0x11, 0x00, 0x00)
        val d = McuFrame.decode(inbound)
        assertTrue(d is McuFrame.Decoded.Malformed)
        assertTrue((d as McuFrame.Decoded.Malformed).reason.contains("header"))
    }

    @Test
    fun `a truncated frame reports what it expected`() {
        val d = McuFrame.decode(LOGGED_1.copyOfRange(0, 9))
        assertTrue(d is McuFrame.Decoded.Malformed)
        assertTrue((d as McuFrame.Decoded.Malformed).reason.contains("15"))
    }

    @Test
    fun `too short to be a frame at all is refused`() {
        assertTrue(McuFrame.decode(bytes(0x5A, 0xA5)) is McuFrame.Decoded.Malformed)
    }

    @Test
    fun `a payload that cannot be described by a one byte length is refused`() {
        var threw = false
        try {
            McuFrame.encode(0x11, ByteArray(McuFrame.MAX_PAYLOAD + 1))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("an over-long payload must not silently truncate", threw)
    }
}
