package com.ripostelabs.carlauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RAV4-65: the JVM half of the status-indicator invariant. The Compose suite in
 * app/src/androidTest pins the rendering, but it only runs on the single KVM runner; this runs
 * in `./gradlew test` on every push, with no emulator.
 *
 * It asserts the RULE, never a fixed picture: for a given set of sources, exactly the chips
 * whose source answers are visible, and the rest are ABSENT. Absence is asserted positively —
 * whole-set equality, so a chip that starts rendering unconditionally turns this red. Making
 * every chip unconditional is the one "fix" that would destroy the property being protected.
 */
class StatusIndicatorVisibilityTest {

    private val btPresent = BtStatus(present = true, on = true, connectedCount = 1)
    private val btAbsent = BtStatus(present = false, on = false, connectedCount = 0)
    private val volumeReadable = VolumeStatus(available = true, level = 12, muted = false)
    private val volumeUnbound = VolumeStatus(available = false, level = 0, muted = false)
    private val brightnessReadable = 60

    // The four the ROADMAP holds stable from v3.1 to v4.0.
    private val allFour = setOf(
        StatusIndicatorTags.WIFI,
        StatusIndicatorTags.BLUETOOTH,
        StatusIndicatorTags.VOLUME,
        StatusIndicatorTags.BRIGHTNESS,
    )

    @Test
    fun everySourceAnsweringShowsAllFour() {
        assertEquals(allFour, visibleIndicators(btPresent, volumeReadable, brightnessReadable))
    }

    /**
     * The emulator's own shape: no root and no WRITE_SETTINGS, no vendor EventService, no car.
     * Two chips are legitimately absent, and a test demanding four here would be wrong.
     */
    @Test
    fun emulatorLosesVolumeAndBrightness() {
        val shown = visibleIndicators(btPresent, volumeUnbound, brightnessPercent = null)

        assertEquals(setOf(StatusIndicatorTags.WIFI, StatusIndicatorTags.BLUETOOTH), shown)
        assertFalse(StatusIndicatorTags.VOLUME in shown)
        assertFalse(StatusIndicatorTags.BRIGHTNESS in shown)
    }

    @Test
    fun bluetoothNeedsAnAdapter() {
        val shown = visibleIndicators(btAbsent, volumeReadable, brightnessReadable)

        assertEquals(allFour - StatusIndicatorTags.BLUETOOTH, shown)
    }

    @Test
    fun volumeNeedsABoundEventService() {
        val shown = visibleIndicators(btPresent, volumeUnbound, brightnessReadable)

        assertEquals(allFour - StatusIndicatorTags.VOLUME, shown)
    }

    @Test
    fun brightnessNeedsAReadableBacklight() {
        val shown = visibleIndicators(btPresent, volumeReadable, brightnessPercent = null)

        assertEquals(allFour - StatusIndicatorTags.BRIGHTNESS, shown)
    }

    /**
     * Wi-Fi is the floor: the framework answers even with the radio off, so the chip greys
     * rather than vanishing. Losing it would leave the driver unable to tell "off" from "broken".
     */
    @Test
    fun wifiSurvivesEveryOtherSourceFailing() {
        val shown = visibleIndicators(btAbsent, volumeUnbound, brightnessPercent = null)

        assertEquals(setOf(StatusIndicatorTags.WIFI), shown)
    }

    /** Muted and level 0 are readings, not a dead source: the chip stays. */
    @Test
    fun aMutedHeadUnitStillReportsVolume() {
        val muted = VolumeStatus(available = true, level = 0, muted = true)

        assertTrue(StatusIndicatorTags.VOLUME in visibleIndicators(btPresent, muted, brightnessReadable))
    }

    /** Brightness 0% is a reading too — only an unreadable backlight drops the chip. */
    @Test
    fun zeroPercentIsAReadingNotAnAbsence() {
        assertTrue(
            StatusIndicatorTags.BRIGHTNESS in visibleIndicators(btPresent, volumeReadable, 0),
        )
    }

    /** Every tag is distinct: two chips sharing one identity would hide a dropped chip. */
    @Test
    fun theFourTagsAreDistinct() {
        assertEquals(4, allFour.size)
    }
}
