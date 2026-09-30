package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stock's "Restore defaults" in the sound gain list (`ProviderHelps.setSysPSounds`,
 * com.szchoiceway.settings, :284-292): every source gain and the nav prompt level back to 28.
 * Frames summed by hand: ck = 0xFF - (len + body).
 */
class SourceGainDefaultsTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun restoreSetsEveryGainAndNavTo28() {
        val loud = McuSetup(
            gains = McuSetup.SourceGains(movie = 40, other = 3, radio = 12, usb = 0),
            navVolume = 5,
            balance = 4,
        )
        val restored = loud.withGainsRestored()

        assertEquals(List(10) { 28 }, restored.gains.asList())
        assertEquals(28, restored.navVolume)
        assertEquals(4, restored.balance)
    }

    /** `49 08` + ten 0x1C (sendVolumeGain, EventService.java:9662). Sum 0x176, ck 0x89. */
    @Test
    fun restoredGainsFrame() {
        val expected = bytes(0x0D, 0x0A, 0x0D, 0x49, 0x08, *IntArray(10) { 0x1C }, 0x89, 0x00)

        assertArrayEquals(expected, McuSetupProtocol.sourceGains(McuSetup().withGainsRestored().gains))
    }

    /** `26 1C` (sendGPSVol, :9533). 03+26+1C = 0x45, ck 0xBA. */
    @Test
    fun restoredNavFrame() {
        assertArrayEquals(bytes(0x0D, 0x0A, 0x03, 0x26, 0x1C, 0xBA, 0x00), McuSetupProtocol.navVolume(28))
    }

    /** The video and other gains ride the boot burst in the vendor's slots 3 and 10. */
    @Test
    fun videoAndOtherAreSentAtBoot() {
        val setup = McuSetup(gains = McuSetup.SourceGains(movie = 33, other = 7))
        val frame = McuSetupProtocol.boot(setup).first { it[3] == 0x49.toByte() && it[4] == 0x08.toByte() }

        assertEquals(33, frame[7].toInt())
        assertEquals(7, frame[14].toInt())
    }
}
