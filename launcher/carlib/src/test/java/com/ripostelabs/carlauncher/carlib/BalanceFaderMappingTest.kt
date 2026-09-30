package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The balance/fader domain is 0..20 with centre 10: the stock DSP app sends `2F lr fr` in that
 * range (BalanceModel_two.java:56-69, Constants.java:200-201 default 10, EasyFieldFragment_two_2
 * clamps at 20). eventcenter's 7 default belongs to the other sound chip. The launcher shows a
 * centred -10..10 slider, so "Centre" (0) must map to 10: amp 7 was 3 steps left and front
 * (RAV4-163), and amp 0 was the 2026-08-30 "centre is L only" bug.
 */
class BalanceFaderMappingTest {
    @Test fun centreMapsToTen() {
        assertEquals(10, displayToAmp(0))    // Centre -> DSP centre, NOT 7 and NOT 0
        assertEquals(0, ampToDisplay(10))
    }

    @Test fun extremesMapToZeroAndTwenty() {
        assertEquals(0, displayToAmp(-10))   // full left / front
        assertEquals(20, displayToAmp(10))   // full right / rear
        assertEquals(-10, ampToDisplay(0))
        assertEquals(10, ampToDisplay(20))
    }

    @Test fun roundTripIsIdentityAcrossTheDomain() {
        for (amp in 0..20) {
            assertEquals(amp, displayToAmp(ampToDisplay(amp)))
        }
    }

    @Test fun outOfRangeInputsClampInsteadOfWrapping() {
        assertEquals(0, displayToAmp(-99))
        assertEquals(20, displayToAmp(99))
        // Stale/garbage reads (e.g. an old signed -8 or its 248 echo) clamp to an endpoint.
        assertEquals(-10, ampToDisplay(-8))
        assertEquals(10, ampToDisplay(248))
    }
}
