package com.ripostelabs.carlauncher.ui

import com.ripostelabs.carlauncher.carlib.CarPlayState
import com.ripostelabs.carlauncher.media.SourceLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The media card's CarPlay stand-in: present while projected, play flag from MAIN_AUDIO_*. */
class CarPlayNowPlayingTest {

    private val idle = "Connected"

    @Test
    fun nothingWhenNoPhoneIsProjected() {
        assertNull(carPlayNowPlaying(CarPlayState(), "CarPlay", idle))
        assertNull(carPlayNowPlaying(CarPlayState(audioPlaying = true), "CarPlay", idle))
    }

    @Test
    fun playingRowNamesTheLinkAndOpensZlink() {
        val now = carPlayNowPlaying(
            CarPlayState(connected = true, phoneMode = "carplay_wireless", audioPlaying = true),
            "CarPlay wireless",
            idle,
        )!!

        assertEquals("CarPlay wireless", now.title)
        assertEquals("", now.artist)
        assertTrue(now.isPlaying)
        assertEquals("com.zjinnova.zlink", now.sourcePackage)
        assertEquals("CarPlay", now.sourceLabel)
        assertTrue(SourceLabels.isCarPlay(now.sourcePackage))
    }

    @Test
    fun idleRowHasNoTransportAndSaysSo() {
        val now = carPlayNowPlaying(CarPlayState(connected = true), "CarPlay", idle)!!

        assertFalse(now.isPlaying)
        assertEquals(idle, now.artist)
        assertFalse(now.hasPrev)
        assertFalse(now.hasNext)
        assertEquals(1, now.sessionCount)
    }
}

