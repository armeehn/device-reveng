package com.ripostelabs.carlauncher.ui.nav

import com.ripostelabs.carlauncher.carlib.Gear
import com.ripostelabs.carlauncher.carlib.VolumeReading
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the volume popup shows (RAV4-155). The amp volume is the MCU's: every change, from the
 * fascia keys, the wheel or our own slider, comes back as a `79`/`78` report, so the report is
 * the only trigger. Stock pops `showVolWnd` for 5 s on each one (EventService.java:3112-3124).
 */
class VolumePopupPolicyTest {

    private fun reading(level: Int, muted: Boolean = false, show: Boolean = true) =
        VolumeReading(level = level, muted = muted, showWindow = show, atMs = 0)

    @Test
    fun firstReportIsTheBootLevelAndStaysQuiet() {
        val p = VolumePopupPolicy()

        assertFalse(p.onReading(reading(12), Gear.DRIVE))
    }

    @Test
    fun everyLaterChangeShows() {
        val p = VolumePopupPolicy()
        p.onReading(reading(12), Gear.DRIVE)

        assertTrue("key up", p.onReading(reading(13), Gear.DRIVE))
        assertTrue("mute", p.onReading(reading(13, muted = true), Gear.DRIVE))
    }

    @Test
    fun ourSliderShowsEvenWhenTheMcuMarksItSilent() {
        val p = VolumePopupPolicy()
        p.onReading(reading(12), Gear.DRIVE)

        assertTrue(p.onReading(reading(20, show = false), Gear.DRIVE))
    }

    @Test
    fun keyAtTheLimitShowsTheSameLevelAgain() {
        val p = VolumePopupPolicy()
        p.onReading(reading(40), Gear.DRIVE)

        assertTrue("stock pops the window at max too", p.onReading(reading(40), Gear.DRIVE))
        assertFalse("a silent repeat is a replay, not a press", p.onReading(reading(40, show = false), Gear.DRIVE))
    }

    @Test
    fun reverseHidesItButKeepsTheLevel() {
        val p = VolumePopupPolicy()
        p.onReading(reading(12), Gear.DRIVE)

        assertFalse("the camera picture stays clear", p.onReading(reading(14), Gear.REVERSE))
        assertFalse("14 was seen in reverse, so a silent 14 is no change", p.onReading(reading(14, show = false), Gear.DRIVE))
    }

    @Test
    fun noReportNoPopup() {
        assertFalse(VolumePopupPolicy().onReading(null, Gear.DRIVE))
    }
}
