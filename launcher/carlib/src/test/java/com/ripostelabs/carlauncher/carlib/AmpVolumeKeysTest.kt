package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android STREAM_MUSIC moves as amp steps: the volume keys, the dialog, `input keyevent`. */
class AmpVolumeKeysTest {

    private companion object {
        /** ro.config.media_vol_steps on Riposte OS (os/overlay/system/bin/rw-system.sh). */
        const val STREAM_MAX = 25
        const val PIN = STREAM_MAX - 1
        const val START_LEVEL = 12
    }

    private val sent = mutableListOf<Int>()
    private val keys = AmpVolumeKeys(setAmp = { sent += it }, startLevel = START_LEVEL)

    private fun report(level: Int) = keys.onMainVolume(McuOwnerProtocol.MainVolume(level, silent = false))

    @Test
    fun pinsOneBelowTheTop() {
        assertEquals(PIN, AmpVolumeKeys.pinIndex(STREAM_MAX))
    }

    @Test
    fun upStepsTheAmpAndAsksForThePinBack() {
        assertTrue(keys.onStreamMoved(from = PIN, to = STREAM_MAX, max = STREAM_MAX))
        assertEquals(listOf(START_LEVEL + 1), sent)
    }

    @Test
    fun downStepsTheAmp() {
        assertTrue(keys.onStreamMoved(from = PIN, to = PIN - 1, max = STREAM_MAX))
        assertEquals(listOf(START_LEVEL - 1), sent)
    }

    @Test
    fun theMoveBackToThePinIsNotAStep() {
        assertFalse(keys.onStreamMoved(from = STREAM_MAX, to = PIN, max = STREAM_MAX))
        assertFalse(keys.onStreamMoved(from = 8, to = PIN, max = STREAM_MAX))
        assertEquals(emptyList<Int>(), sent)
    }

    @Test
    fun quickPressesBeforeTheReportAccumulate() {
        keys.onStreamMoved(from = PIN, to = STREAM_MAX, max = STREAM_MAX)
        keys.onStreamMoved(from = PIN, to = STREAM_MAX, max = STREAM_MAX)
        assertEquals(listOf(START_LEVEL + 1, START_LEVEL + 2), sent)
    }

    @Test
    fun theMcuReportIsTheBase() {
        report(20)
        keys.onStreamMoved(from = PIN, to = PIN - 1, max = STREAM_MAX)
        assertEquals(listOf(19), sent)
    }

    @Test
    fun aDialogDragMovesByItsDistance() {
        keys.onStreamMoved(from = PIN, to = PIN - 3, max = STREAM_MAX)
        assertEquals(listOf(START_LEVEL - 3), sent)
    }

    @Test
    fun theLevelStopsAtTheAmpRange() {
        report(CarService.MAX_VOLUME)
        keys.onStreamMoved(from = PIN, to = STREAM_MAX, max = STREAM_MAX)
        keys.onStreamMoved(from = PIN, to = PIN - 1, max = STREAM_MAX)
        assertEquals(listOf(CarService.MAX_VOLUME, CarService.MAX_VOLUME - 1), sent)
    }
}
