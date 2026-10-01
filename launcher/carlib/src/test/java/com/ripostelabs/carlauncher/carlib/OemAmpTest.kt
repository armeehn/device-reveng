package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the factory amplifier (JBL) against stock canbus2.
 *
 * Report 0xA6, `HiworldCanParseToyota.java:559-607`: stock bArr[2..8] are our p[0..6]:
 * volume (0-63, capped), balance and fade (0-14, 7 = centre), bass, mid, treble (0-10,
 * 5 = flat), then p[6] bit 1 ASL and bit 0 surround.
 * Writes, `HiworldToyotaAMPSetConfig.java:7` and `HiworldToyotaAMPUILandscapeDefault.java:109-157`:
 * `02 AD key value`, volume only as a step `02 AD 01 01|FF`, and `03 6A 05 01 A6` on open.
 * Checksums follow `SendUtil.SendCmdLstToCanbus5AA5Header`: sum of the payload minus 1.
 */
class OemAmpTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** A full box frame as it arrives: `A5 5A A5 LEN A6 payload C1 C2`, C1 worked by hand. */
    @Test
    fun `a box frame decodes to the stock fields`() {
        // 07+A6+20+07+07+05+05+05+02 = 0xEC, minus 1 = 0xEB.
        val frame = bytes(0xA5, 0x5A, 0xA5, 0x07, 0xA6, 0x20, 0x07, 0x07, 0x05, 0x05, 0x05, 0x02, 0xEB, 0x00)

        val signal = HiworldCanDecoder.decodeFrame(frame) as CanSignal.OemAmp
        val amp = signal.state

        assertEquals(32, amp.volume)
        assertEquals(7, amp.balance)
        assertEquals(7, amp.fade)
        assertEquals(5, amp.bass)
        assertEquals(5, amp.mid)
        assertEquals(5, amp.treble)
        assertTrue(amp.asl)
        assertFalse(amp.surround)
    }

    @Test
    fun `volume above 63 is capped like stock`() {
        val amp = OemAmp.decode(bytes(0x50, 7, 7, 5, 5, 5, 0))!!
        assertEquals(63, amp.volume)
    }

    @Test
    fun `bit 0 is surround and bit 1 is ASL`() {
        val amp = OemAmp.decode(bytes(10, 7, 7, 5, 5, 5, 0x01))!!
        assertFalse(amp.asl)
        assertTrue(amp.surround)
    }

    /** No report of the full length is no amp: the page stays hidden. */
    @Test
    fun `a short report is not an amp`() {
        assertNull(OemAmp.decode(bytes(10, 7, 7)))
        assertTrue(HiworldCanDecoder.decodePayload(0xA6, bytes(10, 7, 7)) is CanSignal.Unknown)
    }

    /** Stock labels: balance L/R, fade R(ear)/F(ront), tone offset from 5. */
    @Test
    fun `labels read as stock shows them`() {
        assertEquals("L4", OemAmp.balanceLabel(3))
        assertEquals("0", OemAmp.balanceLabel(7))
        assertEquals("R3", OemAmp.balanceLabel(10))
        assertEquals("R5", OemAmp.fadeLabel(2))
        assertEquals("F2", OemAmp.fadeLabel(9))
        assertEquals("-5", OemAmp.toneLabel(0))
        assertEquals("0", OemAmp.toneLabel(5))
        assertEquals("+3", OemAmp.toneLabel(8))
    }

    @Test
    fun `query is the stock page's poll`() {
        // 03+6A+05+01+A6 = 0x119, minus 1 = 0x18.
        val expected = bytes(0x0D, 0x08, 0x5A, 0xA5, 0x03, 0x6A, 0x05, 0x01, 0xA6, 0x18)
        assertArrayEquals(expected, McuCommand.framed(OemAmp.QUERY))
    }

    @Test
    fun `a setting is 02 AD key value`() {
        // Bass +2 = raw 7: 02+AD+04+07 = 0xBA, minus 1 = 0xB9.
        val bass = McuCommand.framed(OemAmp.setPayload(OemAmpKey.BASS, 7))
        assertArrayEquals(bytes(0x0D, 0x08, 0x5A, 0xA5, 0x02, 0xAD, 0x04, 0x07, 0xB9), bass)

        // ASL on: 02+AD+07+01 = 0xB7, minus 1 = 0xB6.
        val asl = McuCommand.framed(OemAmp.setPayload(OemAmpKey.ASL, 1))
        assertArrayEquals(bytes(0x0D, 0x08, 0x5A, 0xA5, 0x02, 0xAD, 0x07, 0x01, 0xB6), asl)

        assertArrayEquals(intArrayOf(0x02, 0xAD, 0x02, 0x00), OemAmp.setPayload(OemAmpKey.BALANCE, 0))
        assertArrayEquals(intArrayOf(0x02, 0xAD, 0x03, 0x0E), OemAmp.setPayload(OemAmpKey.FADE, 14))
        assertArrayEquals(intArrayOf(0x02, 0xAD, 0x05, 0x0A), OemAmp.setPayload(OemAmpKey.MID, 10))
        assertArrayEquals(intArrayOf(0x02, 0xAD, 0x06, 0x00), OemAmp.setPayload(OemAmpKey.TREBLE, 0))
        assertArrayEquals(intArrayOf(0x02, 0xAD, 0x08, 0x01), OemAmp.setPayload(OemAmpKey.SURROUND, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a value outside the key's range is refused`() {
        OemAmp.setPayload(OemAmpKey.BASS, 11)
    }

    /** Stock never writes an absolute volume: only a step. */
    @Test(expected = IllegalArgumentException::class)
    fun `volume is never written as a value`() {
        OemAmp.setPayload(OemAmpKey.VOLUME, 20)
    }

    @Test
    fun `volume steps are 01 and FF with stock's limits`() {
        // Up: 02+AD+01+01 = 0xB1, minus 1 = 0xB0. Down: 02+AD+01+FF = 0x1AF, minus 1 = 0xAE.
        val up = McuCommand.framed(OemAmp.volumeStep(20, VolumeStep.UP)!!)
        val down = McuCommand.framed(OemAmp.volumeStep(20, VolumeStep.DOWN)!!)
        assertArrayEquals(bytes(0x0D, 0x08, 0x5A, 0xA5, 0x02, 0xAD, 0x01, 0x01, 0xB0), up)
        assertArrayEquals(bytes(0x0D, 0x08, 0x5A, 0xA5, 0x02, 0xAD, 0x01, 0xFF, 0xAE), down)

        assertNull(OemAmp.volumeStep(63, VolumeStep.UP))
        assertNull(OemAmp.volumeStep(0, VolumeStep.DOWN))
    }

    @Test
    fun `owner frame carries the query`() {
        val expected = McuSerial.encode(0x0D, bytes(0x08, 0x5A, 0xA5, 0x03, 0x6A, 0x05, 0x01, 0xA6, 0x18))
        assertArrayEquals(expected, McuOwnerProtocol.oemAmp(OemAmp.QUERY))
    }
}
