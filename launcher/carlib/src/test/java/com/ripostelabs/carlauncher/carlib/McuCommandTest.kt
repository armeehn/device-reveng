package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The frame that will be transmitted at a car, pinned byte for byte.
 *
 * These vectors come from the vendor's own transmit builder, read out of the decompile, and the
 * arithmetic was re-derived here independently rather than copied. A wrong byte is not a failing
 * test in a car, it is a frame the MCU silently drops, which is indistinguishable from a link
 * that does not work at all.
 */
class McuCommandTest {

    private fun hex(data: ByteArray) = data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    /** The vendor's read-only settings poll. Sum 0xD5, so the checksum is 0xD4. */
    @Test
    fun `the settings query matches the vendor frame exactly`() {
        assertEquals("0D 08 5A A5 03 6A 05 01 62 D4", hex(McuCommand.settingsQuery()))
    }

    /**
     * The header must be excluded from the sum. Including `5A A5` would add 0xFF and give a
     * checksum of 0xD3, a frame the MCU drops without a word.
     */
    @Test
    fun `the checksum excludes the header bytes`() {
        val frame = McuCommand.settingsQuery()
        val payload = frame.drop(4).dropLast(1).sumOf { it.toInt() and 0xFF }

        assertEquals((payload - 1) and 0xFF, frame.last().toInt() and 0xFF)
    }

    /** Vendor climate frames, as a check that the builder generalises beyond the one query. */
    @Test
    fun `climate payloads produce the vendor's own bytes`() {
        assertEquals("0D 08 5A A5 02 3D 02 01 41", hex(McuCommand.framed(intArrayOf(0x02, 0x3D, 0x02, 0x01))))
        assertEquals("0D 08 5A A5 02 3D 01 01 40", hex(McuCommand.framed(intArrayOf(0x02, 0x3D, 0x01, 0x01))))
        assertEquals("0D 08 5A A5 02 3D 0B 01 4A", hex(McuCommand.framed(intArrayOf(0x02, 0x3D, 0x0B, 0x01))))
    }

    /** The outer envelope belongs to the receiver. Building it here would double it. */
    @Test
    fun `the outer envelope is not built here`() {
        val frame = McuCommand.settingsQuery()

        assertEquals(0x0D, frame[0].toInt() and 0xFF)
        assertEquals(0x08, frame[1].toInt() and 0xFF)
        assertEquals(0x5A, frame[2].toInt() and 0xFF)
    }

    /** Inbound frames carry a three-byte header, and outbound ones a two-byte header. */
    @Test
    fun `an inbound reply is recognised by its own header`() {
        val reply = byteArrayOf(0xA5.toByte(), 0x5A, 0xA5.toByte(), 0x02, 0x62, 0x00, 0x00)

        assertEquals(McuCommand.REPLY_OPCODE_CAR_SET, McuCommand.inboundOpcode(reply))
    }

    /**
     * A frame we do not recognise must not be read as an answer. A caller uses this to decide
     * whether the car replied, so a loose match would turn a dead link into a false success.
     */
    @Test
    fun `a frame that is not an inbound MCU frame is refused`() {
        assertNull(McuCommand.inboundOpcode(McuCommand.settingsQuery()))
        assertNull(McuCommand.inboundOpcode(byteArrayOf(0xA5.toByte(), 0x5A)))
        assertNull(McuCommand.inboundOpcode(byteArrayOf(0x5A, 0xA5.toByte(), 0x02, 0x62, 0x00, 0x00, 0x00)))
    }

    /** The intent strings come from the port owner's own source and must not drift. */
    @Test
    fun `the intent strings are the vendor's`() {
        assertEquals("com.szchoiceway.eventcenter.EventUtils.ACTION_MCU_CMD_EVENT", McuCommand.ACTION)
        assertEquals("EventUtils.MCU_CMD_DATA", McuCommand.EXTRA_DATA)
    }
}
