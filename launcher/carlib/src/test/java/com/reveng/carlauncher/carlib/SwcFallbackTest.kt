package com.reveng.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unprotected SWC fallback is the only wheel input a NON-ROOT install gets, and on a rooted
 * unit its events co-arrive with the protected capture. So this pins two things: the conservative
 * decode (unknown → dropped, never guessed) and the canonical form that lets ProtectedEventDedupe
 * drop the cross-carrier duplicate.
 */
class SwcFallbackTest {

    // ---- hostKey decode -----------------------------------------------------

    @Test
    fun hostKeyDecodesDocumentedDownUp() {
        assertEquals(
            SwcFallback.Edge(CarEvents.CAR_KEY_NEXT, down = true),
            SwcFallback.hostKey(CarEvents.CAR_KEY_NEXT, CarEvents.SWC_STATE_DOWN),
        )
        assertEquals(
            SwcFallback.Edge(CarEvents.CAR_KEY_NEXT, down = false),
            SwcFallback.hostKey(CarEvents.CAR_KEY_NEXT, CarEvents.SWC_STATE_UP),
        )
    }

    @Test
    fun hostKeyDropsUnknownStatusEncodings() {
        // 1/0 (and anything else) is an UNCONFIRMED encoding: a wrong edge guess would leave a
        // key held in KeyPump and fire a phantom long-press, so these must drop.
        assertNull(SwcFallback.hostKey(CarEvents.CAR_KEY_NEXT, 1))
        assertNull(SwcFallback.hostKey(CarEvents.CAR_KEY_NEXT, 0))
        assertNull(SwcFallback.hostKey(CarEvents.CAR_KEY_NEXT, 99))
        assertNull(SwcFallback.hostKey(CarEvents.CAR_KEY_NEXT, null))
    }

    @Test
    fun hostKeyDropsUnknownKeycodes() {
        assertNull(SwcFallback.hostKey(0, CarEvents.SWC_STATE_DOWN))
        assertNull(SwcFallback.hostKey(15, CarEvents.SWC_STATE_DOWN))
        assertNull(SwcFallback.hostKey(null, CarEvents.SWC_STATE_DOWN))
    }

    // ---- keycode normalisation ----------------------------------------------

    @Test
    fun carKeyRangePassesThrough() {
        for (code in CarEvents.CAR_KEY_POWER..CarEvents.CAR_KEY_R_TUNE_R) {
            assertEquals(code, SwcFallback.normalizeKey(code))
        }
    }

    @Test
    fun panelSysCodesTranslateToCarKeys() {
        assertEquals(CarEvents.CAR_KEY_HOME, SwcFallback.normalizeKey(SwcFallback.MCU_KEY_SYS_HOME))
        assertEquals(CarEvents.CAR_KEY_MENU, SwcFallback.normalizeKey(SwcFallback.MCU_KEY_SYS_MENU))
        assertEquals(CarEvents.CAR_KEY_BACK, SwcFallback.normalizeKey(SwcFallback.MCU_KEY_SYS_ESC))
    }

    @Test
    fun mcuKeyDropsWinceAndUnknownCodes() {
        assertNull(SwcFallback.mcuKey(79)) // MCU_KEY_SYS_WINCE — no CAR_KEY meaning
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
