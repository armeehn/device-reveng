package com.ripostelabs.carlauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dynamic guide lines are the stock head unit's (eventcenter `ReverseCarTrackView`), fed the
 * way the stock app feeds them. Expected points come from a line-by-line transliteration of the
 * decompiled Java (`GetXYListFinal` + `drawarcLine`, 1920x720 hdpi resource set), as fractions of
 * the OEM's 800x720 drawing box.
 */
class GuideTrajectoryTest {

    private val tolerance = 0.0005f

    /** Wheel degrees, OEM track index, then (x, y) of the left rail at points 7, 30, 50 and the right rail. */
    private val oem = listOf(
        Case(0.0, 0, 0.1584, 0.8221, 0.3370, 0.5248, 0.3668, 0.4754, 0.8416, 0.8221, 0.6630, 0.5248, 0.6332, 0.4754),
        Case(90.0, -3, 0.1699, 0.8179, 0.3655, 0.5232, 0.4100, 0.4744, 0.8534, 0.8265, 0.6919, 0.5267, 0.6773, 0.4767),
        Case(-90.0, 3, 0.1466, 0.8265, 0.3081, 0.5267, 0.3227, 0.4767, 0.8301, 0.8179, 0.6345, 0.5232, 0.5900, 0.4744),
        Case(270.0, -9, 0.1927, 0.8098, 0.4214, 0.5207, 0.4949, 0.4735, 0.8777, 0.8358, 0.7520, 0.5313, 0.7697, 0.4804),
        Case(-270.0, 9, 0.1223, 0.8358, 0.2480, 0.5313, 0.2303, 0.4804, 0.8073, 0.8098, 0.5786, 0.5207, 0.5051, 0.4735),
        Case(540.0, -19, 0.2306, 0.7969, 0.5146, 0.5185, 0.6374, 0.4752, 0.9219, 0.8538, 0.8630, 0.5425, 0.9454, 0.4917),
        Case(-540.0, 19, 0.0781, 0.8538, 0.1370, 0.5425, 0.0546, 0.4917, 0.7694, 0.7969, 0.4854, 0.5185, 0.3626, 0.4752),
    )

    @Test
    fun wheelDegreesMapToTheOemTrackIndex() {
        for (c in oem) {
            assertEquals("index at ${c.wheel}", c.index, GuideTrajectory.trackIndex(c.wheel))
        }

        // Past full lock the OEM clamps at 19; the negative side loses one count (0xFFFF - raw).
        assertEquals(-19, GuideTrajectory.trackIndex(900.0))
        assertEquals(0, GuideTrajectory.trackIndex(-28.0))
        assertEquals(-1, GuideTrajectory.trackIndex(28.0))
    }

    @Test
    fun railsMatchTheOemCurveAtEachAngle() {
        for (c in oem) {
            val rails = GuideTrajectory.rails(c.wheel, mirrored = false)
            for ((k, i) in SAMPLED.withIndex()) {
                val left = rails.left[i]
                val right = rails.right[i]
                assertEquals("left x @${c.wheel}/$i", c.left[2 * k].toFloat(), left.x, tolerance)
                assertEquals("left y @${c.wheel}/$i", c.left[2 * k + 1].toFloat(), left.y, tolerance)
                assertEquals("right x @${c.wheel}/$i", c.right[2 * k].toFloat(), right.x, tolerance)
                assertEquals("right y @${c.wheel}/$i", c.right[2 * k + 1].toFloat(), right.y, tolerance)
            }
        }
    }

    @Test
    fun fullLockBendsTheFarEndAQuarterOfTheWay() {
        // The owner's complaint: the old lines barely moved. At full lock the OEM's far end sits
        // over a quarter of the box away from where it sits straight ahead.
        val straight = GuideTrajectory.rails(0.0, mirrored = false).left.last().x
        val lock = GuideTrajectory.rails(540.0, mirrored = false).left.last().x
        assertTrue("far end moved ${lock - straight}", lock - straight > 0.25f)
    }

    @Test
    fun zonesAndTicksFollowTheOemMarks() {
        // Points 0..7 red, 8..30 yellow, 31..50 green.
        assertEquals(GuideTrajectory.Zone.NEAR, GuideTrajectory.zoneOf(7))
        assertEquals(GuideTrajectory.Zone.MID, GuideTrajectory.zoneOf(8))
        assertEquals(GuideTrajectory.Zone.MID, GuideTrajectory.zoneOf(30))
        assertEquals(GuideTrajectory.Zone.FAR, GuideTrajectory.zoneOf(31))

        // One inward tick per rail at points 7, 15, 30 and 50, shrinking with distance.
        val ticks = GuideTrajectory.rails(0.0, mirrored = false).ticks
        assertEquals(8, ticks.size)
        assertEquals(
            listOf(GuideTrajectory.Zone.NEAR, GuideTrajectory.Zone.MID, GuideTrajectory.Zone.MID, GuideTrajectory.Zone.FAR),
            ticks.filterIndexed { i, _ -> i % 2 == 0 }.map { it.zone },
        )

        // Straight ahead the first left tick is 80 OEM px long, pointing right.
        val first = ticks.first()
        assertEquals(80f / 800f, first.to.x - first.from.x, tolerance)
        assertEquals(first.from.y, first.to.y, tolerance)
    }

    @Test
    fun aMirroredFeedMirrorsTheLines() {
        val plain = GuideTrajectory.rails(270.0, mirrored = false)
        val mirrored = GuideTrajectory.rails(270.0, mirrored = true)

        assertEquals(1f - plain.left.last().x, mirrored.right.last().x, tolerance)
        assertEquals(1f - plain.ticks.first().to.x, mirrored.ticks[1].to.x, tolerance)
    }

    private class Case(val wheel: Double, val index: Int, vararg xy: Double) {
        val left = xy.copyOfRange(0, 6)
        val right = xy.copyOfRange(6, 12)
    }

    private companion object {
        val SAMPLED = listOf(7, 30, 50)
    }
}
