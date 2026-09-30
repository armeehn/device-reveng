package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The suite weather app's row, as the home card reads it. */
class WeatherFeedTest {

    private val hourMs = 3_600_000L
    private val now = 1_790_000_000_000L

    private val row = mapOf<String, Any?>(
        WeatherFeed.COL_TEMP to 14.5,
        WeatherFeed.COL_HIGH to 18.0,
        WeatherFeed.COL_LOW to 6.0,
        WeatherFeed.COL_UNIT to "C",
        WeatherFeed.COL_CODE to 3L,
        WeatherFeed.COL_AQI to 42L,
        WeatherFeed.COL_PLACE to "Kelowna",
        WeatherFeed.COL_UPDATED to now - hourMs,
    )

    @Test
    fun `a full row parses`() {
        val w = WeatherFeed.parse(row::get)!!

        assertEquals(14.5, w.temp, 0.0)
        assertEquals(18.0, w.high!!, 0.0)
        assertEquals(TempUnit.CELSIUS, w.unit)
        assertEquals(3, w.code)
        assertEquals(42, w.aqi)
        assertEquals("Kelowna", w.place)
    }

    @Test
    fun `no temperature means no reading`() {
        assertNull(WeatherFeed.parse((row - WeatherFeed.COL_TEMP)::get))
    }

    @Test
    fun `optional columns may be missing`() {
        val bare = mapOf<String, Any?>(WeatherFeed.COL_TEMP to 60L, WeatherFeed.COL_UNIT to "F")
        val w = WeatherFeed.parse(bare::get)!!

        assertEquals(TempUnit.FAHRENHEIT, w.unit)
        assertNull(w.high)
        assertNull(w.aqi)
        assertEquals("", w.place)
    }

    @Test
    fun `an unknown unit reads as Celsius`() {
        val w = WeatherFeed.parse((row + (WeatherFeed.COL_UNIT to "K"))::get)!!

        assertEquals(TempUnit.CELSIUS, w.unit)
    }

    @Test
    fun `a reading older than six hours is stale`() {
        val w = WeatherFeed.parse(row::get)!!

        assertTrue(WeatherFeed.isFresh(w, now))
        assertFalse(WeatherFeed.isFresh(w.copy(updatedMs = now - 7 * hourMs), now))
    }

    @Test
    fun `a reading from the future is not trusted`() {
        val w = WeatherFeed.parse(row::get)!!.copy(updatedMs = now + 2 * hourMs)

        assertFalse(WeatherFeed.isFresh(w, now))
    }

    @Test
    fun `WMO codes map to a sky`() {
        assertEquals(Sky.CLEAR, WeatherFeed.sky(0))
        assertEquals(Sky.CLEAR, WeatherFeed.sky(1))
        assertEquals(Sky.CLOUDY, WeatherFeed.sky(3))
        assertEquals(Sky.FOG, WeatherFeed.sky(45))
        assertEquals(Sky.RAIN, WeatherFeed.sky(61))
        assertEquals(Sky.RAIN, WeatherFeed.sky(81))
        assertEquals(Sky.SNOW, WeatherFeed.sky(73))
        assertEquals(Sky.STORM, WeatherFeed.sky(95))
        assertEquals(Sky.CLOUDY, WeatherFeed.sky(999))
    }

    @Test
    fun `the label rounds to a whole degree`() {
        val w = WeatherFeed.parse(row::get)!!

        assertEquals("15°", WeatherFeed.degrees(w.temp))
        assertEquals("-3°", WeatherFeed.degrees(-2.6))
    }
}
