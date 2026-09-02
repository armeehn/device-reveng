package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dedupe sits between two delivery paths and the focus ring, so it is wrong in two directions:
 * too loose and one wheel press moves the ring two steps, too tight and a genuine fast double-press
 * becomes one. These pin both, plus the property that suppression cannot chain.
 */
class ProtectedEventDedupeTest {

    private val action = CarEvents.STEER_WHEEL_INFOR
    private val down = mapOf(CarEvents.EXTRA_SWC_LPARAM to 4, CarEvents.EXTRA_SWC_WPARAM to 3)
    private val up = mapOf(CarEvents.EXTRA_SWC_LPARAM to 4, CarEvents.EXTRA_SWC_WPARAM to 4)

    @Test
    fun firstArrivalIsAlwaysAccepted() {
        val dedupe = ProtectedEventDedupe()
        assertTrue(dedupe.accept(action, down, 0L))
    }

    @Test
    fun sameEventFromTheOtherPathIsRejected() {
        val dedupe = ProtectedEventDedupe()
        dedupe.accept(action, down, 1_000L)
        // The two paths land within single-digit milliseconds of each other.
        assertFalse(dedupe.accept(action, down, 1_003L))

        // The maps production actually builds are asymmetric: the in-process receiver fills an
        // absent extra with 0, the root helper skips it. Same event, so it must still dedupe.
        val inProcess = mapOf(
            CarEvents.EXTRA_SWC_LPARAM to 4,
            CarEvents.EXTRA_SWC_WPARAM to 3,
            CarEvents.EXTRA_SWC_VOLTAGE to 0,
        )
        val rootPath = mapOf(CarEvents.EXTRA_SWC_LPARAM to 4, CarEvents.EXTRA_SWC_WPARAM to 3)
        val real = ProtectedEventDedupe()
        real.accept(action, inProcess, 1_000L)
        assertFalse(real.accept(action, rootPath, 1_003L))
    }

    @Test
    fun rejectionEndsAtTheWindowEdge() {
        val dedupe = ProtectedEventDedupe()
        dedupe.accept(action, down, 1_000L)
        assertFalse(dedupe.accept(action, down, 1_000L + ProtectedEventDedupe.WINDOW_MS - 1))

        val later = ProtectedEventDedupe()
        later.accept(action, down, 1_000L)
        assertTrue(later.accept(action, down, 1_000L + ProtectedEventDedupe.WINDOW_MS))
    }

    @Test
    fun fastDoublePressStillRegistersTwice() {
        val dedupe = ProtectedEventDedupe()
        // A 60 ms press/release/press — far faster than a driver can actually tap, and well inside
        // the window. The release between the two presses is a different event, so neither press
        // is swallowed.
        assertTrue(dedupe.accept(action, down, 0L))
        assertTrue(dedupe.accept(action, up, 20L))
        assertTrue(dedupe.accept(action, down, 40L))
        assertTrue(dedupe.accept(action, up, 60L))
    }

    @Test
    fun windowIsShorterThanADeliberateDoubleTap() {
        // Guards the constant itself: a window this side of ~250 ms cannot eat a real double-tap.
        assertTrue(ProtectedEventDedupe.WINDOW_MS < 250L)
    }

    @Test
    fun suppressionDoesNotChainPastTheWindow() {
        val dedupe = ProtectedEventDedupe()
        assertTrue(dedupe.accept(action, down, 0L))
        assertFalse(dedupe.accept(action, down, 100L))
        // Anchored to the first arrival, not the rejected one: 130 ms is a new press.
        assertTrue(dedupe.accept(action, down, 130L))
    }

    @Test
    fun differentActionsDoNotDedupeEachOther() {
        val dedupe = ProtectedEventDedupe()
        assertTrue(dedupe.accept(CarEvents.ACTION_BACKCAR_START, emptyMap(), 0L))
        assertTrue(dedupe.accept(CarEvents.ACTION_BACKCAR_END, emptyMap(), 5L))
    }

    @Test
    fun differentExtrasDoNotDedupeEachOther() {
        val dedupe = ProtectedEventDedupe()
        val otherKey = mapOf(CarEvents.EXTRA_SWC_LPARAM to 7, CarEvents.EXTRA_SWC_WPARAM to 3)
        assertTrue(dedupe.accept(action, down, 0L))
        assertTrue(dedupe.accept(action, otherKey, 5L))
    }
}
