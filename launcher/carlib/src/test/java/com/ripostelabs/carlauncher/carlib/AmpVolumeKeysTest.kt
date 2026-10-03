package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The launcher's volume keys as amp steps; every other STREAM_MUSIC move only goes back to the pin. */
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
    fun upStepsTheAmp() {
        keys.step(NavVolume.delta(NavVolume.Step.UP))
        assertEquals(listOf(START_LEVEL + 1), sent)
    }

    @Test
    fun downStepsTheAmp() {
        keys.step(NavVolume.delta(NavVolume.Step.DOWN))
        assertEquals(listOf(START_LEVEL - 1), sent)
    }

    // Car, 2026-10-02 07:09:30: the iPhone's AVRCP absolute volume moved the stream 24 → 25 and
    // the amp rose a level on its own. Only the launcher's keys may move the amp.
    @Test
    fun aPhoneVolumeMoveIsNotAStep() {
        assertTrue(keys.onStreamMoved(from = PIN, to = STREAM_MAX, max = STREAM_MAX))
        assertTrue(keys.onStreamMoved(from = PIN, to = PIN - 3, max = STREAM_MAX))
        assertEquals(emptyList<Int>(), sent)
    }

    @Test
    fun theMoveBackToThePinNeedsNoReset() {
        assertFalse(keys.onStreamMoved(from = STREAM_MAX, to = PIN, max = STREAM_MAX))
        assertFalse(keys.onStreamMoved(from = 8, to = PIN, max = STREAM_MAX))
        assertEquals(emptyList<Int>(), sent)
    }

    @Test
    fun quickPressesBeforeTheReportAccumulate() {
        keys.step(1)
        keys.step(1)
        assertEquals(listOf(START_LEVEL + 1, START_LEVEL + 2), sent)
    }

    @Test
    fun theMcuReportIsTheBase() {
        report(20)
        keys.step(-1)
        assertEquals(listOf(19), sent)
    }

    @Test
    fun theLevelStopsAtTheAmpRange() {
        report(CarService.MAX_VOLUME)
        keys.step(1)
        keys.step(-1)
        assertEquals(listOf(CarService.MAX_VOLUME, CarService.MAX_VOLUME - 1), sent)
    }
}
