package com.ripostelabs.carlauncher.carlib

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class NavVolumeTest {

    // RAV4-177: a nav bar key is one Android volume step, which AmpVolumeKeys turns into one
    // amp level. Raise and lower, never a mute toggle or an absolute level.
    @Test
    fun eachKeyIsOneStep() {
        assertEquals(AudioManager.ADJUST_RAISE, NavVolume.direction(NavVolume.Step.UP))
        assertEquals(AudioManager.ADJUST_LOWER, NavVolume.direction(NavVolume.Step.DOWN))
    }

    // The launcher's own popup shows the level from the MCU's report, so Android's dialog stays off.
    @Test
    fun theSystemDialogStaysHidden() {
        assertEquals(0, NavVolume.FLAGS)
    }
}
