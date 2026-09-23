package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The last station across boots. The vendor's gateway process never dies, so its `mRadio*`
 * fields survive a radio-app restart; ours die with the launcher and come back from here.
 */
class RadioMemoryTest {

    /** The station list round-trips through one string, empty slots included. */
    @Test
    fun stationListRoundTrips() {
        val list = RadioZone.of(1).defaultStations.toMutableList().also { it[7] = 9630 }

        assertEquals(list, RadioMemory.decodeStations(RadioMemory.encodeStations(list)))
    }

    /** A corrupt or foreign string is no list: the zone defaults take over. */
    @Test
    fun garbageDecodesToNothing() {
        assertNull(RadioMemory.decodeStations(""))
        assertNull(RadioMemory.decodeStations("8750,abc"))
        assertNull(RadioMemory.decodeStations("1,2,3"))
    }

    /** No memory: the zone's `initRadioZone` list and its FM floor (`mRadioCurFreq = 8750`, EventService.java:255). */
    @Test
    fun firstBootIsZoneDefaults() {
        val state = RadioMemory.restore(zone = 1, band = null, freq = null, stations = null)

        assertEquals(1, state.zone)
        assertEquals(0, state.band)
        assertEquals(8750, state.freq)
        assertEquals(RadioZone.of(1).defaultStations, state.stationList)
        assertEquals(0L, state.updatedAt)
    }

    /** A remembered station comes back as heard, so the screen shows it before the MCU repeats it. */
    @Test
    fun rememberedStationIsRestored() {
        val stations = RadioZone.of(1).defaultStations.toMutableList().also { it[3] = 10150 }
        val state = RadioMemory.restore(zone = 1, band = 3, freq = 1010, stations = RadioMemory.encodeStations(stations))

        assertEquals(3, state.band)
        assertEquals(1010, state.freq)
        assertEquals(10150, state.stationList[3])
    }
}
