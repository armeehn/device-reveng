package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RAV4-38 plumbing: the dashboard's steering must come from the same 0x11 decode the capture
 * screen shows, a foreign or corrupt frame must not touch it, and a reading must age into
 * "stale" rather than sit as a frozen number.
 */
class SteeringReadingTest {

    private val at = 1_000_000L

    /** `A5 5A A5 | LEN | OPCODE | PAYLOAD | C1 | C2` with the real checksum. */
    private fun framed(opcode: Int, payload: ByteArray): ByteArray {
        val body = byteArrayOf(payload.size.toByte(), opcode.toByte()) + payload
        val c1 = HiworldCanDecoder.checkSum5AA5(body, 0, body.size)
        return byteArrayOf(0xA5.toByte(), 0x5A, 0xA5.toByte()) + body + byteArrayOf(c1.toByte(), 0)
    }

    /** 0x11 payload with the raw angle at p[6:7] big-endian. */
    private fun basicStatus(raw: Int): ByteArray {
        val p = ByteArray(8)
        p[6] = ((raw shr 8) and 0xFF).toByte()
        p[7] = (raw and 0xFF).toByte()
        return p
    }

    @Test
    fun basicStatusFrameYieldsOemScaledDegrees() {
        val reading = SteeringReading.fromFrame(framed(0x11, basicStatus(56)), at)
        assertEquals(4.0, reading!!.degrees, 0.0)
        assertEquals(at, reading.atMs)
    }

    @Test
    fun negativeRawSignExtends() {
        val reading = SteeringReading.fromFrame(framed(0x11, basicStatus(0xFFC8)), at)
        assertEquals(-4.0, reading!!.degrees, 0.0)
    }

    @Test
    fun otherOpcodeIsNoUpdate() {
        assertNull(SteeringReading.fromFrame(framed(0x48, ByteArray(12)), at))
    }

    @Test
    fun corruptChecksumIsNoUpdate() {
        val frame = framed(0x11, basicStatus(56))
        frame[frame.size - 2] = (frame[frame.size - 2] + 1).toByte()
        assertNull(SteeringReading.fromFrame(frame, at))
    }

    @Test
    fun readingGoesStaleAfterWindow() {
        val reading = SteeringReading(0.0, at)
        assertFalse(reading.isStale(at + SteeringReading.STALE_AFTER_MS - 1))
        assertTrue(reading.isStale(at + SteeringReading.STALE_AFTER_MS))
    }
}
