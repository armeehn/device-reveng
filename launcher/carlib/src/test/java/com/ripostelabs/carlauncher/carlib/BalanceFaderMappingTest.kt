package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The amp balance/fader domain is 0..14 with centre 7 (verified in the vendor EventService:
 * mBALVal/mFADVal default to 7 and boot ships {0x2F,7,7}). The launcher shows a centred -7..7
 * slider, so "Centre" (0) must map to amp 7 — not amp 0, which is full left/front and was the
 * 2026-08-30 "centre is L only" bug. Regression pins the mapping and its clamping.
 */
class BalanceFaderMappingTest {
    @Test fun centreMapsToSeven() {
        assertEquals(7, displayToAmp(0))     // Centre -> amp centre, NOT amp 0
        assertEquals(0, ampToDisplay(7))
    }

    @Test fun extremesMapToZeroAndFourteen() {
        assertEquals(0, displayToAmp(-7))    // full left / front
        assertEquals(14, displayToAmp(7))    // full right / rear
        assertEquals(-7, ampToDisplay(0))
        assertEquals(7, ampToDisplay(14))
    }

    @Test fun roundTripIsIdentityAcrossTheDomain() {
        for (amp in 0..14) assertEquals(amp, displayToAmp(ampToDisplay(amp)))
    }

    @Test fun outOfRangeInputsClampInsteadOfWrapping() {
        assertEquals(0, displayToAmp(-99))
        assertEquals(14, displayToAmp(99))
        // Stale/garbage reads (e.g. an old signed -8 or its 248 echo) clamp to an endpoint.
        assertEquals(-7, ampToDisplay(-8))
        assertEquals(7, ampToDisplay(248))
    }
}
