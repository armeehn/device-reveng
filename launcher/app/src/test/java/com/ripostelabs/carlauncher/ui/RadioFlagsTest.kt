package com.ripostelabs.carlauncher.ui

import com.ripostelabs.carlauncher.ui.RadioTuning.Flag
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The owner could not tell what RDS, TA, AF and TP do (RAV4-147). In North America none of them
 * serves the driver, and TA made seek skip every station, so the tuner screen shows them only
 * where they work, or while one is on so it can be switched off.
 */
class RadioFlagsTest {

    private val northAmerica = 1
    private val europe = 0

    @Test
    fun northAmericaShowsStereoOnly() {
        assertEquals(listOf(Flag.STEREO), RadioTuning.flags(northAmerica, ta = false, af = false))
    }

    /** A TA or AF left on from before stays reachable, so one tap switches it off. */
    @Test
    fun northAmericaKeepsALitToggle() {
        assertEquals(listOf(Flag.TA, Flag.STEREO), RadioTuning.flags(northAmerica, ta = true, af = false))
        assertEquals(listOf(Flag.AF, Flag.STEREO), RadioTuning.flags(northAmerica, ta = null, af = true))
    }

    @Test
    fun europeShowsTheRdsSet() {
        assertEquals(Flag.entries, RadioTuning.flags(europe, ta = false, af = false))
    }

    /** No zone (the vendor gateway's tuner): as before, everything. */
    @Test
    fun unknownZoneShowsAll() {
        assertEquals(Flag.entries, RadioTuning.flags(null, ta = false, af = false))
    }

    /** Words, not codes: the chip says what it is. */
    @Test
    fun labelsAreWords() {
        assertEquals("Stereo", Flag.STEREO.label)
        assertEquals("Traffic", Flag.TA.label)
    }
}
