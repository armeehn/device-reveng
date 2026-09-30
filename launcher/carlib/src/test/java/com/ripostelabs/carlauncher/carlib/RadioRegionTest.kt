package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The radio region picker and the RDS switch (RAV4-188), byte for byte as stock sends them.
 *
 *     zone   SetView.java:99-148      key 13 if scanning, key 30 if on AM, then changeSetup
 *            EventService.java:4822   changeSetup(zone) clamps to 0..4, sends `05 01 z`
 *     RDS    EventService.java:4837   changeSetup(SYS_RDS_OnOff, "1") sends `05 00 00`
 */
class RadioRegionTest {

    /** `05 00 v`: RDS on is a ZERO. */
    @Test
    fun rdsOnIsZeroOffIsOne() {
        assertArrayEquals(McuSerial.encode(0x05, byteArrayOf(0x00, 0x00)), McuOwnerProtocol.rds(true))
        assertArrayEquals(McuSerial.encode(0x05, byteArrayOf(0x00, 0x01)), McuOwnerProtocol.rds(false))
    }

    @Test
    fun zoneChangeOnFmIsTheSetupAlone() {
        val frames = McuOwnerProtocol.zoneChange(0, onAm = false, scanning = false)

        assertEquals(1, frames.size)
        assertArrayEquals(McuSerial.encode(0x05, byteArrayOf(0x01, 0x00)), frames[0])
    }

    /** On AM the vendor leaves for FM first: the AM grids differ per zone. */
    @Test
    fun zoneChangeOnAmMovesToFmFirst() {
        val frames = McuOwnerProtocol.zoneChange(4, onAm = true, scanning = false)

        assertEquals(2, frames.size)
        assertArrayEquals(McuSerial.encode(0x02, byteArrayOf(0x1E)), frames[0])
        assertArrayEquals(McuSerial.encode(0x05, byteArrayOf(0x01, 0x04)), frames[1])
    }

    /** A running preset scan is stopped with key 13 before anything else. */
    @Test
    fun zoneChangeStopsAScanFirst() {
        val frames = McuOwnerProtocol.zoneChange(1, onAm = true, scanning = true)

        assertEquals(3, frames.size)
        assertArrayEquals(McuSerial.encode(0x02, byteArrayOf(0x0D)), frames[0])
        assertArrayEquals(McuSerial.encode(0x02, byteArrayOf(0x1E)), frames[1])
        assertArrayEquals(McuSerial.encode(0x05, byteArrayOf(0x01, 0x01)), frames[2])
    }

    /** The radio app's radio_zone array: 3 is Russia (OIRT 65-74), 4 is Japan (76-90). */
    @Test
    fun regionsFollowTheRadioAppOrder() {
        assertEquals(listOf(0, 1, 2, 3, 4), RadioZone.Region.entries.map { it.id })
        assertEquals(RadioZone.Region.NORTH_AMERICA, RadioZone.Region.of(RadioZone.NORTH_AMERICA))
        assertEquals(6500, RadioZone.of(RadioZone.Region.RUSSIA.id).fm.min)
        assertEquals(7600, RadioZone.of(RadioZone.Region.JAPAN.id).fm.min)
    }

    /** The gateway clamps a zone above 4 to 4 (EventService.java:4825-4830); below 0 reads as Europe. */
    @Test
    fun regionClampsLikeTheGateway() {
        assertEquals(RadioZone.Region.JAPAN, RadioZone.Region.of(5))
        assertEquals(RadioZone.Region.EUROPE, RadioZone.Region.of(-1))
    }

    /** A new zone reseeds the preset list, as `initRadioZone` does before the MCU reports. */
    @Test
    fun zoneChangeReseedsTheStationList() {
        val holder = RadioStateHolder()
        holder.seed(RadioState(zone = 1, stationList = RadioZone.of(1).defaultStations))

        holder.setZone(3)

        assertEquals(3, holder.state.value.zone)
        assertEquals(RadioZone.of(3).defaultStations, holder.state.value.stationList)
    }

    /** The owner's defaults stay put: North America with RDS off, for a car in Canada. */
    @Test
    fun defaultsStayNorthAmericaRdsOff() {
        assertEquals(RadioZone.NORTH_AMERICA, RadioMemory.DEFAULT_ZONE)
        assertEquals(false, RadioMemory.DEFAULT_RDS)
    }
}
