package com.ripostelabs.carlauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Analog face angles, in degrees clockwise from twelve. */
class ClockHandsTest {

    @Test
    fun `three o'clock points right`() {
        assertEquals(90f, ClockHands.hour(15, 0), 0f)
        assertEquals(0f, ClockHands.minute(0, 0), 0f)
    }

    @Test
    fun `the hour hand creeps with the minutes`() {
        // 10:30 sits halfway between 10 and 11.
        assertEquals(315f, ClockHands.hour(10, 30), 0f)
    }

    @Test
    fun `the minute hand creeps with the seconds`() {
        assertEquals(93f, ClockHands.minute(15, 30), 0f)
    }

    @Test
    fun `midnight and noon are both straight up`() {
        assertEquals(0f, ClockHands.hour(0, 0), 0f)
        assertEquals(0f, ClockHands.hour(12, 0), 0f)
    }
}
