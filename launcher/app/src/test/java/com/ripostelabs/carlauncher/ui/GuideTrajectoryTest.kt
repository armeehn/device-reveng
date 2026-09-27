package com.ripostelabs.carlauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dynamic guide lines are where the rear corners go when the car reverses at the current
 * steering angle. Straight ahead they must sit exactly on the static lines drivers already know.
 */
class GuideTrajectoryTest {

    private val tolerance = 0.002f

    @Test
    fun straightWheelsDrawTheStaticRails() {
        val rails = GuideTrajectory.rails(steeringDeg = 0.0, mirrored = false)

        // The static rails: 0.22 / 0.78 of the width at the bumper (0.98 high), 0.36 / 0.64 at the top.
        assertEquals(0.22f, rails.left.first().x, tolerance)
        assertEquals(0.98f, rails.left.first().y, tolerance)
        assertEquals(0.78f, rails.right.first().x, tolerance)
        assertEquals(0.36f, rails.left.last().x, tolerance)
        assertEquals(0.45f, rails.left.last().y, tolerance)
    }

    @Test
    fun oppositeAnglesBendOppositeWays() {
        val left = GuideTrajectory.rails(steeringDeg = 200.0, mirrored = false)
        val right = GuideTrajectory.rails(steeringDeg = -200.0, mirrored = false)

        val straightTop = GuideTrajectory.rails(steeringDeg = 0.0, mirrored = false).left.last().x
        val sideA = left.left.last().x - straightTop
        val sideB = right.left.last().x - straightTop
        assertTrue("both must bend: $sideA / $sideB", sideA != 0f && sideB != 0f)
        assertTrue("they bend opposite ways: $sideA / $sideB", sideA * sideB < 0f)
    }

    @Test
    fun moreLockBendsHarder() {
        val straightTop = GuideTrajectory.rails(steeringDeg = 0.0, mirrored = false).left.last().x
        val some = GuideTrajectory.rails(steeringDeg = 90.0, mirrored = false).left.last().x - straightTop
        val more = GuideTrajectory.rails(steeringDeg = 360.0, mirrored = false).left.last().x - straightTop

        assertTrue("360 deg must bend further than 90 deg: $more vs $some", kotlin.math.abs(more) > kotlin.math.abs(some))
    }

    @Test
    fun aMirroredFeedMirrorsTheLines() {
        val plain = GuideTrajectory.rails(steeringDeg = 200.0, mirrored = false)
        val mirrored = GuideTrajectory.rails(steeringDeg = 200.0, mirrored = true)

        assertEquals(1f - plain.left.last().x, mirrored.right.last().x, tolerance)
    }
}
