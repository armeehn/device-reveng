package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SunTimesTest {

    private fun at(utc: String) = Instant.parse(utc).toEpochMilli()

    // RAV4-169: Kelowna, BC. Summer sunrise is near 04:50 PDT (11:50 UTC), sunset near
    // 21:05 PDT (04:05 UTC next day); winter sunset near 16:25 PST (00:25 UTC).
    @Test
    fun kelownaDayAndNight() {
        assertFalse(SunTimes.isNight(at("2026-06-21T20:00:00Z"), KELOWNA_LAT, KELOWNA_LON))
        assertTrue(SunTimes.isNight(at("2026-06-21T08:00:00Z"), KELOWNA_LAT, KELOWNA_LON))
        assertTrue(SunTimes.isNight(at("2026-06-21T11:30:00Z"), KELOWNA_LAT, KELOWNA_LON))
        assertFalse(SunTimes.isNight(at("2026-06-21T12:15:00Z"), KELOWNA_LAT, KELOWNA_LON))
        assertFalse(SunTimes.isNight(at("2026-12-21T20:00:00Z"), KELOWNA_LAT, KELOWNA_LON))
        assertTrue(SunTimes.isNight(at("2026-12-22T01:00:00Z"), KELOWNA_LAT, KELOWNA_LON))
    }

    // The elevation test needs no sunrise at all, so polar night and midnight sun just work.
    @Test
    fun polarNightAndMidnightSun() {
        assertTrue(SunTimes.isNight(at("2026-12-21T12:00:00Z"), TROMSO_LAT, TROMSO_LON))
        assertFalse(SunTimes.isNight(at("2026-06-21T23:00:00Z"), TROMSO_LAT, TROMSO_LON))
    }

    // Solar noon at the equinox on the Greenwich meridian: the sun is near 90° minus latitude.
    @Test
    fun noonElevationAtTheEquinox() {
        val elevation = SunTimes.elevation(at("2026-03-20T12:07:00Z"), latDeg = 0.0, lonDeg = 0.0)
        assertEquals(90.0, elevation, 1.0)
    }

    private companion object {
        const val KELOWNA_LAT = 49.888
        const val KELOWNA_LON = -119.496
        const val TROMSO_LAT = 69.65
        const val TROMSO_LON = 18.96
    }
}
