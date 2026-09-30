package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RAV4-164: auto-answer arms once per ring and fires only if the call is still ringing. */
class AutoAnswerTest {

    @Test
    fun `a new ring arms the chosen delay`() {
        assertEquals(0L, AutoAnswer.NOW.arm(prev = null, lead = HfCallState.INCOMING))
        assertEquals(3_000L, AutoAnswer.AFTER_3S.arm(prev = null, lead = HfCallState.INCOMING))
        assertEquals(5_000L, AutoAnswer.AFTER_5S.arm(prev = HfCallState.ACTIVE, lead = HfCallState.INCOMING))
    }

    @Test
    fun `off, a ring already seen, or no ring arms nothing`() {
        assertNull(AutoAnswer.OFF.arm(prev = null, lead = HfCallState.INCOMING))
        assertNull(AutoAnswer.NOW.arm(prev = HfCallState.INCOMING, lead = HfCallState.INCOMING))
        assertNull(AutoAnswer.NOW.arm(prev = null, lead = HfCallState.DIALING))
        assertNull(AutoAnswer.NOW.arm(prev = null, lead = null))
        // A waiting call beside an active one is the driver's choice, never answered for them.
        assertNull(AutoAnswer.NOW.arm(prev = HfCallState.ACTIVE, lead = HfCallState.WAITING))
    }

    @Test
    fun `it fires only while the call still rings and CarPlay holds no call`() {
        assertTrue(AutoAnswer.due(lead = HfCallState.INCOMING, carPlayCall = false))
        assertFalse(AutoAnswer.due(lead = HfCallState.ACTIVE, carPlayCall = false))   // the driver answered
        assertFalse(AutoAnswer.due(lead = null, carPlayCall = false))                  // rejected or gone
        assertFalse(AutoAnswer.due(lead = HfCallState.INCOMING, carPlayCall = true))
    }

    @Test
    fun `stored names read back, anything else is off`() {
        assertEquals(AutoAnswer.AFTER_3S, AutoAnswer.of("AFTER_3S"))
        assertEquals(AutoAnswer.OFF, AutoAnswer.of(null))
        assertEquals(AutoAnswer.OFF, AutoAnswer.of("sometimes"))
    }
}
