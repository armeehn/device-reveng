package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unprotected `MCU_KEY_INFOR` path is the only wheel input a NON-ROOT install gets, and on a
 * rooted unit its events co-arrive with the protected capture. So this pins two things: the
 * MCU_KEY → CAR_KEY table (`EventUtils.java:1458-1656`) and the canonical form that lets
 * ProtectedEventDedupe drop the cross-carrier duplicate.
 */
class SwcFallbackTest {

    // ---- MCU_KEY table ----------------------------------------------------------

    @Test
    fun vendorCodesMapToCarKeys() {
        assertEquals(CarEvents.CAR_KEY_HOME, SwcFallback.mcuKey(SwcFallback.MCU_KEY_MENU))
        assertEquals(CarEvents.CAR_KEY_BACK, SwcFallback.mcuKey(SwcFallback.MCU_KEY_RETURN))
        assertEquals(CarEvents.CAR_KEY_NEXT, SwcFallback.mcuKey(SwcFallback.MCU_KEY_NEXT))
        assertEquals(CarEvents.CAR_KEY_PREV, SwcFallback.mcuKey(SwcFallback.MCU_KEY_PREV))
        assertEquals(CarEvents.CAR_KEY_PHONE, SwcFallback.mcuKey(SwcFallback.MCU_KEY_TALK))
        assertEquals(CarEvents.CAR_KEY_MEDIA, SwcFallback.mcuKey(SwcFallback.MCU_KEY_MODE))
    }

    @Test
    fun vendorTableValuesAreTheDecompiledOnes() {
        // The numbers themselves are the contract; a typo here is a wheel that does nothing.
        assertEquals(9, SwcFallback.MCU_KEY_MENU)
        assertEquals(85, SwcFallback.MCU_KEY_RETURN)
        assertEquals(2, SwcFallback.MCU_KEY_NEXT)
        assertEquals(3, SwcFallback.MCU_KEY_PREV)
        assertEquals(23, SwcFallback.MCU_KEY_TALK)
        assertEquals(16, SwcFallback.MCU_KEY_MODE)
        assertEquals(18, SwcFallback.MCU_KEY_VOL_ADD)
        assertEquals(19, SwcFallback.MCU_KEY_VOL_SUB)
        assertEquals(17, SwcFallback.MCU_KEY_MUTE)
    }

    @Test
    fun carKeyIndicesAreNotPassedThrough() {
        // MCU codes 1..14 are POWER/NEXT/PREV/… in the vendor table, not CAR_KEY_* indices.
        // CAR_KEY_HOME (2) arriving raw is MCU_KEY_NEXT and must map as such, not as HOME.
        assertEquals(CarEvents.CAR_KEY_NEXT, SwcFallback.mcuKey(CarEvents.CAR_KEY_HOME))
        assertNull(SwcFallback.mcuKey(CarEvents.CAR_KEY_L_TUNE_L))
    }

    @Test
    fun audioAndUnmappedCodesAreDropped() {
        // The gateway already applied volume/mute to the amp before broadcasting.
        assertNull(SwcFallback.mcuKey(SwcFallback.MCU_KEY_VOL_ADD))
        assertNull(SwcFallback.mcuKey(SwcFallback.MCU_KEY_VOL_SUB))
        assertNull(SwcFallback.mcuKey(SwcFallback.MCU_KEY_MUTE))
        // No CAR_KEY twin.
        assertNull(SwcFallback.mcuKey(SwcFallback.MCU_KEY_POWER))
        assertNull(SwcFallback.mcuKey(SwcFallback.MCU_KEY_PLAYPAUSE))
        assertNull(SwcFallback.mcuKey(SwcFallback.MCU_KEY_HANGUP))
        assertNull(SwcFallback.mcuKey(SwcFallback.MCU_KEY_VOICE))
        assertNull(SwcFallback.mcuKey(SwcFallback.MCU_KEY_TASK_LIST))
        assertNull(SwcFallback.mcuKey(-1))
        assertNull(SwcFallback.mcuKey(null))
    }

    // ---- dedupe interaction -------------------------------------------------

    private val now = 10_000L

    /** The map the protected direct/root path dedupes on: full extras incl. voltage, reduced. */
    private fun protectedKey(index: Int, state: Int): Map<String, Int> =
        CarEvents.swcDedupeInts(
            CarEvents.STEER_WHEEL_INFOR,
            mapOf(
                CarEvents.EXTRA_SWC_LPARAM to index,
                CarEvents.EXTRA_SWC_WPARAM to state,
                CarEvents.EXTRA_SWC_VOLTAGE to 512, // the fallback never carries this
            ),
        )

    @Test
    fun protectedThenFallbackCopyIsDropped() {
        val dedupe = ProtectedEventDedupe()
        val down = SwcFallback.Edge(CarEvents.CAR_KEY_NEXT, down = true)

        assertTrue(
            dedupe.accept(
                CarEvents.STEER_WHEEL_INFOR,
                protectedKey(CarEvents.CAR_KEY_NEXT, CarEvents.SWC_STATE_DOWN),
                now,
            ),
        )
        // The unprotected copy lands a few ms later with no voltage extra — same canonical map.
        assertFalse(
            dedupe.accept(CarEvents.STEER_WHEEL_INFOR, SwcFallback.canonicalInts(down), now + 3),
        )
    }

    @Test
    fun fallbackThenProtectedCopyIsDropped() {
        // Delivery order is not guaranteed; the dedupe must work both ways round.
        val dedupe = ProtectedEventDedupe()
        val down = SwcFallback.Edge(CarEvents.CAR_KEY_NEXT, down = true)

        assertTrue(
            dedupe.accept(CarEvents.STEER_WHEEL_INFOR, SwcFallback.canonicalInts(down), now),
        )
        assertFalse(
            dedupe.accept(
                CarEvents.STEER_WHEEL_INFOR,
                protectedKey(CarEvents.CAR_KEY_NEXT, CarEvents.SWC_STATE_DOWN),
                now + 3,
            ),
        )
    }

    @Test
    fun releaseIsADifferentEventFromPress() {
        val dedupe = ProtectedEventDedupe()
        assertTrue(
            dedupe.accept(
                CarEvents.STEER_WHEEL_INFOR,
                SwcFallback.canonicalInts(SwcFallback.Edge(CarEvents.CAR_KEY_NEXT, down = true)),
                now,
            ),
        )
        assertTrue(
            dedupe.accept(
                CarEvents.STEER_WHEEL_INFOR,
                SwcFallback.canonicalInts(SwcFallback.Edge(CarEvents.CAR_KEY_NEXT, down = false)),
                now + 5,
            ),
        )
    }

    @Test
    fun dedupeIntsLeavesOtherActionsAlone() {
        val ints = mapOf("whatever" to 1)
        assertEquals(ints, CarEvents.swcDedupeInts(CarEvents.ACTION_BACKCAR_START, ints))
    }
}
