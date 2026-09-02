package com.ripostelabs.carlauncher.ui

import com.ripostelabs.carlauncher.carlib.SteeringReading
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * RAV4-38: the dashboard steering tile must print the decoded angle while frames flow, and must
 * say "stale" — not keep the last number — once they stop.
 */
class SteeringTileTest {

    private lateinit var original: Locale

    @Before
    fun pinLocale() {
        original = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    private val at = 5_000_000L
    private val reading = SteeringReading(degrees = -12.5, atMs = at)

    @Test
    fun noFrameYet() {
        assertEquals("—", steeringValue(null, at))
        assertEquals("no reading yet", steeringNote(null, at))
    }

    @Test
    fun liveReadingShowsDegreesAndProvenance() {
        val now = at + SteeringReading.STALE_AFTER_MS - 1
        assertEquals("-12.5°", steeringValue(reading, now))
        assertEquals("CAN 0x11 · OEM scale · sign unconfirmed", steeringNote(reading, now))
    }

    @Test
    fun staleReadingNeverFreezesTheNumber() {
        val now = at + 7_000L
        assertEquals("—", steeringValue(reading, now))
        assertEquals("stale · no CAN frame for 7s", steeringNote(reading, now))
    }
}
