package com.ripostelabs.carlauncher.carlib

import com.ripostelabs.carlauncher.carlib.WheelKeySwallow.Edge
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wrong swallow is a lost press; a missed one is a long press that also skips a track. Both
 * paths, the window edge, and the "same release, two deliveries" case are pinned.
 */
class WheelKeySwallowTest {

    private val s = WheelKeySwallow()
    private val WINDOW = WheelKeySwallow.SWALLOW_WINDOW_MS

    @Test
    fun nothingArmedNothingSwallowed() {
        assertFalse(s.swallowKeyEvent(WheelKey.NEXT, Edge.DOWN, 0))
        assertFalse(s.swallowKeyEvent(WheelKey.NEXT, Edge.UP, 80))
        assertFalse(s.swallowBroadcast(WheelKey.NEXT, 0))
    }

    @Test
    fun anArmedKeyEatsItsDownAndUp() {
        s.arm(WheelKey.NEXT, 0)
        assertTrue(s.swallowKeyEvent(WheelKey.NEXT, Edge.DOWN, 100))
        assertTrue(s.swallowKeyEvent(WheelKey.NEXT, Edge.UP, 180))

        // The next pair is a real press.
        assertFalse(s.swallowKeyEvent(WheelKey.NEXT, Edge.DOWN, 500))
        assertFalse(s.swallowKeyEvent(WheelKey.NEXT, Edge.UP, 580))
    }

    @Test
    fun anUnarmedUpIsNeverEaten() {
        s.arm(WheelKey.NEXT, 0)
        assertFalse(s.swallowKeyEvent(WheelKey.PREV, Edge.UP, 100))
        assertTrue(s.swallowKeyEvent(WheelKey.NEXT, Edge.DOWN, 100))
        assertFalse(s.swallowKeyEvent(WheelKey.PREV, Edge.UP, 180))
    }

    @Test
    fun oneArmCoversBothDeliveriesOfTheSameRelease() {
        s.arm(WheelKey.PLAY_PAUSE, 0)
        assertTrue(s.swallowBroadcast(WheelKey.PLAY_PAUSE, 90))
        assertTrue(s.swallowKeyEvent(WheelKey.PLAY_PAUSE, Edge.DOWN, 110))
        assertTrue(s.swallowKeyEvent(WheelKey.PLAY_PAUSE, Edge.UP, 190))

        // Used up on both paths: a second press on either goes through.
        assertFalse(s.swallowBroadcast(WheelKey.PLAY_PAUSE, 400))
        assertFalse(s.swallowKeyEvent(WheelKey.PLAY_PAUSE, Edge.DOWN, 400))
    }

    @Test
    fun aPathIsOnlyEatenOncePerArm() {
        s.arm(WheelKey.NEXT, 0)
        assertTrue(s.swallowBroadcast(WheelKey.NEXT, 100))
        assertFalse(s.swallowBroadcast(WheelKey.NEXT, 200))
    }

    @Test
    fun anArmExpiresAtTheWindow() {
        s.arm(WheelKey.RETURN, 0)
        assertFalse(s.swallowKeyEvent(WheelKey.RETURN, Edge.DOWN, WINDOW + 1))
        assertFalse(s.swallowKeyEvent(WheelKey.RETURN, Edge.UP, WINDOW + 81))

        s.arm(WheelKey.RETURN, 0)
        assertTrue(s.swallowBroadcast(WheelKey.RETURN, WINDOW))
    }

    @Test
    fun otherKeysAreUntouched() {
        s.arm(WheelKey.NEXT, 0)
        assertFalse(s.swallowKeyEvent(WheelKey.PREV, Edge.DOWN, 100))
        assertFalse(s.swallowBroadcast(WheelKey.PREV, 100))
    }

    @Test
    fun reArmingResetsTheWindow() {
        s.arm(WheelKey.NEXT, 0)
        s.arm(WheelKey.NEXT, WINDOW)
        assertTrue(s.swallowBroadcast(WheelKey.NEXT, WINDOW + 100))
    }
}
