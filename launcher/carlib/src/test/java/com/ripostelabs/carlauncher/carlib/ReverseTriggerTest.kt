package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ReverseTrigger] is eventcenter's decision on when the reverse picture goes up and comes down
 * (onCmdSysEvent, EventService.java:2354-2366; HANDLER_BACKCAR_START :662-693; getBackCarSpeedThreshold
 * :9004-9016). The speed gate runs on the rising edge only, and nothing but the line coming down
 * ends the picture.
 */
class ReverseTriggerTest {

    private val trigger = ReverseTrigger()

    @Test
    fun lineUpWithNoThresholdEngages() {
        assertTrue(trigger.onLine(reverseBit = true, awake = true, speedKmh = 40, thresholdKmh = 0))
        assertTrue(trigger.engaged)
    }

    @Test
    fun lineDownEnds() {
        trigger.onLine(reverseBit = true, awake = true, speedKmh = 0, thresholdKmh = 0)
        assertFalse(trigger.onLine(reverseBit = false, awake = true, speedKmh = 0, thresholdKmh = 0))
    }

    @Test
    fun tooFastOnTheEdgeStaysDown() {
        assertFalse(trigger.onLine(reverseBit = true, awake = true, speedKmh = 40, thresholdKmh = 30))
        // The message fired once; slowing down with the line still up does not fire it again.
        assertFalse(trigger.onLine(reverseBit = true, awake = true, speedKmh = 10, thresholdKmh = 30))
    }

    @Test
    fun speedAtThresholdPasses() {
        assertTrue(trigger.onLine(reverseBit = true, awake = true, speedKmh = 30, thresholdKmh = 30))
    }

    @Test
    fun speedNeverEndsAnEngagedPicture() {
        trigger.onLine(reverseBit = true, awake = true, speedKmh = 5, thresholdKmh = 30)
        assertTrue(trigger.onLine(reverseBit = true, awake = true, speedKmh = 90, thresholdKmh = 30))
    }

    @Test
    fun unknownSpeedPassesLikeTheVendorsZeroDefault() {
        assertTrue(trigger.onLine(reverseBit = true, awake = true, speedKmh = GpsSpeedSource.SPEED_UNKNOWN, thresholdKmh = 30))
    }

    @Test
    fun asleepIgnoresTheBit() {
        assertFalse(trigger.onLine(reverseBit = true, awake = false, speedKmh = 0, thresholdKmh = 0))
        // Waking with the bit still high is the rising edge the vendor sees (mAccOpenState && bit).
        assertTrue(trigger.onLine(reverseBit = true, awake = true, speedKmh = 0, thresholdKmh = 0))
    }

    @Test
    fun fallingAsleepEnds() {
        trigger.onLine(reverseBit = true, awake = true, speedKmh = 0, thresholdKmh = 0)
        assertFalse(trigger.onLine(reverseBit = true, awake = false, speedKmh = 0, thresholdKmh = 0))
    }

    @Test
    fun aRetriggerAfterASlowDownWorks() {
        trigger.onLine(reverseBit = true, awake = true, speedKmh = 40, thresholdKmh = 30)
        trigger.onLine(reverseBit = false, awake = true, speedKmh = 10, thresholdKmh = 30)
        assertTrue(trigger.onLine(reverseBit = true, awake = true, speedKmh = 10, thresholdKmh = 30))
    }

    /** Sys_Backcar_speed_threshold 0/1/2/3 → off/30/50/80 km/h; anything else is off. */
    @Test
    fun thresholdSettingMapsToKmh() {
        assertEquals(0, ReverseTrigger.thresholdKmh(0))
        assertEquals(30, ReverseTrigger.thresholdKmh(1))
        assertEquals(50, ReverseTrigger.thresholdKmh(2))
        assertEquals(80, ReverseTrigger.thresholdKmh(3))
        assertEquals(0, ReverseTrigger.thresholdKmh(7))
    }

    /** The log line fires on edges only: a speed tick with the line unchanged is silent. */
    @Test
    fun edgeNamesWhatTheCallDid() {
        assertEquals(ReverseTrigger.Edge.NONE, trigger.lastEdge)
        trigger.onLine(reverseBit = true, awake = true, speedKmh = 40, thresholdKmh = 30)
        assertEquals(ReverseTrigger.Edge.UP_TOO_FAST, trigger.lastEdge)
        trigger.onLine(reverseBit = true, awake = true, speedKmh = 10, thresholdKmh = 30)
        assertEquals(ReverseTrigger.Edge.NONE, trigger.lastEdge)
        trigger.onLine(reverseBit = false, awake = true, speedKmh = 10, thresholdKmh = 30)
        assertEquals(ReverseTrigger.Edge.DOWN, trigger.lastEdge)
        trigger.onLine(reverseBit = true, awake = true, speedKmh = 10, thresholdKmh = 30)
        assertEquals(ReverseTrigger.Edge.UP, trigger.lastEdge)
    }
}
