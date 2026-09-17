package com.ripostelabs.carlauncher.tuner

import com.ripostelabs.carlauncher.carlib.McuOwnerProtocol
import com.ripostelabs.carlauncher.carlib.RadioState
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RadioState → the parcel the suite radio reads (RAV4-97): field for field, the vendor's names. */
class TunerStateTest {

    @Test
    fun mapsEveryField() {
        val list = List(McuOwnerProtocol.RADIO_FREQ_LIST_SIZE) { 0 }.toMutableList().also { it[2] = 9630 }
        val state = RadioState(
            band = 3, freq = 1010, presetNumber = 4, stationName = "CBC R1",
            rds = true, ta = true, af = false, stereo = true, stMono = true, dxLoc = false,
            stationList = list, updatedAt = 7,
        )

        val t = TunerState.of(state)

        assertEquals(3, t.band)
        assertEquals(1010, t.freq)
        assertEquals(4, t.preset)
        assertEquals("CBC R1", t.stationName)
        assertTrue(t.rds && t.ta && t.stereo && t.stMono)
        assertFalse(t.af || t.dxLoc)
        assertEquals(McuOwnerProtocol.RADIO_FREQ_LIST_SIZE, t.stationList.size)
        assertEquals(9630, t.stationList[2])
    }

    /** Nothing heard yet: preset is "none", not slot 0, and the list is 42 empty slots. */
    @Test
    fun unknownTunerHasNoPreset() {
        val t = TunerState.of(RadioState())

        assertEquals(TunerState.NO_PRESET, t.preset)
        assertEquals(0, t.freq)
        assertArrayEquals(IntArray(McuOwnerProtocol.RADIO_FREQ_LIST_SIZE), t.stationList)
    }
}
