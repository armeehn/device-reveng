package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class SystemTimeTest {

    // RAV4-175: the two automatic switches are Settings.Global rows, written by the root shell.
    @Test
    fun autoCommands() {
        assertEquals("settings put global auto_time 1", SystemTime.autoCommand(SystemTime.Auto.TIME, SystemTime.Switch.ON))
        assertEquals("settings put global auto_time_zone 0", SystemTime.autoCommand(SystemTime.Auto.ZONE, SystemTime.Switch.OFF))
    }

    // Only a zone from the picker's own list reaches the shell: nothing else is ever run.
    @Test
    fun zoneCommandTakesOnlyKnownZones() {
        assertEquals("cmd alarm set-timezone America/Vancouver", SystemTime.zoneCommand("America/Vancouver"))
        assertNull(SystemTime.zoneCommand("America/Vancouver; reboot"))
        assertNull(SystemTime.zoneCommand("Etc/GMT+8"))
    }

    @Test
    fun zonesAreRegionNames() {
        val zones = SystemTime.zones()

        assertTrue("America/Vancouver" in zones)
        assertTrue("Europe/Paris" in zones)
        assertTrue(zones.none { it.startsWith("Etc/") || it.startsWith("SystemV/") })
        assertEquals(zones.sorted(), zones)
    }

    // DST follows the zone, as the image keeps a real one: Vancouver moves, Regina never does.
    @Test
    fun daylightSavingFollowsTheZone() {
        val july = Instant.parse("2026-07-01T12:00:00Z")
        val january = Instant.parse("2026-01-15T12:00:00Z")

        assertEquals(SystemTime.Dst.IN_EFFECT, SystemTime.dst(ZoneId.of("America/Vancouver"), july))
        assertEquals(SystemTime.Dst.NOT_NOW, SystemTime.dst(ZoneId.of("America/Vancouver"), january))
        assertEquals(SystemTime.Dst.NEVER, SystemTime.dst(ZoneId.of("America/Regina"), july))
    }

    // A zone change moves the local reading the MCU RTC holds, so it is pushed again at once.
    @Test
    fun zoneChangeRepushesAnyLiveClock() {
        assertTrue(McuClock.shouldPushZone(year = 2026))
        assertFalse(McuClock.shouldPushZone(year = 2000))
    }
}
