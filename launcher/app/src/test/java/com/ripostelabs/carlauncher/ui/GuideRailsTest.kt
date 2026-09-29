package com.ripostelabs.carlauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The reverse screens draw one set of guide lines: the stock port. A missing steering reading
 * (no CAN frame yet, dynamic lines off) must give the same rails held straight, not a second
 * static drawing. Two drawings alternated on the Test camera screen (owner report, 2026-09-29).
 */
class GuideRailsTest {

    @Test
    fun noSteeringDrawsStraightStockRails() {
        assertEquals(GuideTrajectory.rails(0.0, mirrored = false), guideRails(null, mirrored = false))
        assertEquals(GuideTrajectory.rails(0.0, mirrored = true), guideRails(null, mirrored = true))
    }

    @Test
    fun steeringFollowsTheAngle() {
        assertEquals(GuideTrajectory.rails(200.0, mirrored = false), guideRails(200.0, mirrored = false))
    }
}
