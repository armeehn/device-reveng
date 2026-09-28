package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 360 view's per-tile verdict. The XS9922B streams in its four-channel mode with no
 * detection (os/CAMERA_360.md), so an empty channel still delivers frames: a flat blue one
 * (bench 2026-09-28, UYVY d4 24 74 24, about RGB 0x0038E6 after conversion).
 */
class SurroundSignalTest {

    private fun frame(size: Int, pixel: (Int) -> Int) = IntArray(size) { pixel(it) }

    private fun grey(level: Int) = (0xFF shl 24) or (level shl 16) or (level shl 8) or level

    @Test
    fun theDecodersNoVideoFrameIsFlat() {
        val noVideo = frame(SAMPLES) { 0xFF0038E6.toInt() }

        assertTrue(SurroundSignal.isFlat(noVideo))
    }

    @Test
    fun aFisheyePictureIsNotFlat() {
        // Black corners round a lit middle, the bench camera's frame in brief.
        val fisheye = frame(SAMPLES) { if (it % 4 == 0) grey(10) else grey(180) }

        assertFalse(SurroundSignal.isFlat(fisheye))
    }

    @Test
    fun aFewHotPixelsDoNotMakeAFlatFrameLive() {
        val specks = frame(SAMPLES) { if (it < SAMPLES / 50) grey(255) else grey(40) }

        assertTrue(SurroundSignal.isFlat(specks))
    }

    @Test
    fun anEmptySampleIsFlat() {
        assertTrue(SurroundSignal.isFlat(IntArray(0)))
    }

    @Test
    fun noCountYetIsWaiting() {
        assertEquals(SurroundSignal.Tile.WAITING, SurroundSignal.judge(before = null, now = null, flat = false))
    }

    @Test
    fun framesAdvancingOverAPictureIsLive() {
        assertEquals(SurroundSignal.Tile.LIVE, SurroundSignal.judge(before = 25, now = 50, flat = false))
        assertEquals(SurroundSignal.Tile.LIVE, SurroundSignal.judge(before = null, now = 3, flat = false))
    }

    @Test
    fun aFlatPictureIsNoSignalEvenWhileFramesArrive() {
        assertEquals(SurroundSignal.Tile.NO_SIGNAL, SurroundSignal.judge(before = 25, now = 50, flat = true))
    }

    @Test
    fun aStalledCountIsNoSignal() {
        assertEquals(SurroundSignal.Tile.NO_SIGNAL, SurroundSignal.judge(before = 50, now = 50, flat = false))
    }

    @Test
    fun tappingATileFocusesItAndTappingAgainReturnsToTheGrid() {
        assertEquals(2, SurroundSignal.focus(current = null, tapped = 2))
        assertNull(SurroundSignal.focus(current = 2, tapped = 2))
        assertEquals(1, SurroundSignal.focus(current = 3, tapped = 1))
    }

    private companion object {
        const val SAMPLES = 32 * 18
    }
}
