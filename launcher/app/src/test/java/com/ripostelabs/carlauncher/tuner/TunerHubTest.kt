package com.ripostelabs.carlauncher.tuner

import com.ripostelabs.carlauncher.carlib.CarService
import com.ripostelabs.carlauncher.carlib.McuOwnerProtocol
import com.ripostelabs.carlauncher.carlib.McuSerial
import com.ripostelabs.carlauncher.carlib.RadioState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The binder against a recording port: every ITuner verb lands on the right CarService call,
 * and what the port would put on the wire is the frame McuOwnerProtocol encodes for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TunerHubTest {

    private class Port : TunerPort {
        override val state = MutableStateFlow(RadioState())
        override val sourceLost = MutableStateFlow(0L)
        val keys = mutableListOf<Int>()
        val tunes = mutableListOf<Pair<Int, Boolean>>()
        var held = false
        override fun claim(): Boolean { held = true; return true }
        override fun release() { held = false }
        override fun isClaimed() = held
        override fun sendKey(key: Int) { keys += key }
        override fun tune(freq: Int, fm: Boolean) { tunes += freq to fm }
    }

    private class Callback : ITunerCallback {
        val states = mutableListOf<TunerState>()
        var lost = 0
        override fun onState(state: TunerState) { states += state }
        override fun onSourceLost() { lost++ }
        override fun onReclaim() {}
        override fun asBinder(): android.os.IBinder? = null
    }

    private val port = Port()

    @After
    fun detach() = TunerHub.detach()

    @Test
    fun seekAndTuneReachThePortAsTheOwnerFrames() = runTest(UnconfinedTestDispatcher()) {
        TunerHub.attach(port, backgroundScope)

        TunerHub.binder.sendKey(CarService.RADIO_KEY_SEEK_UP)
        TunerHub.binder.tune(9630, true)

        assertEquals(listOf(CarService.RADIO_KEY_SEEK_UP), port.keys)
        assertEquals(listOf(9630 to true), port.tunes)
        // What CarService writes for those on the owner path: 02 11 and 0C 25 9E 00.
        assertArrayEquals(McuSerial.encode(0x02, byteArrayOf(0x11)), McuOwnerProtocol.radioKey(port.keys[0]))
        assertArrayEquals(
            McuSerial.encode(0x0C, byteArrayOf(0x25, 0x9E.toByte(), 0x00)),
            McuOwnerProtocol.userFreq(port.tunes[0].first, port.tunes[0].second),
        )
    }

    @Test
    fun claimReleaseFollowTheSource() = runTest(UnconfinedTestDispatcher()) {
        TunerHub.attach(port, backgroundScope)

        assertFalse(TunerHub.binder.isClaimed)
        assertTrue(TunerHub.binder.claim())
        assertTrue(TunerHub.binder.isClaimed)
        TunerHub.binder.release()
        assertFalse(TunerHub.binder.isClaimed)
    }

    @Test
    fun callbackGetsTheCacheThenEveryFold() = runTest(UnconfinedTestDispatcher()) {
        TunerHub.attach(port, backgroundScope)
        val cb = Callback()

        TunerHub.binder.registerCallback(cb)
        port.state.value = RadioState(band = 0, freq = 9630, stationName = "CBC R1", updatedAt = 1)
        port.sourceLost.update { it + 1 }
        TunerHub.binder.unregisterCallback(cb)
        port.state.value = RadioState(band = 3, freq = 530, updatedAt = 2)

        assertEquals(listOf(TunerState.NO_PRESET, 0), cb.states.map { it.preset })
        assertEquals(9630, cb.states.last().freq)
        assertEquals(530, TunerHub.binder.state.freq)
        assertEquals(1, cb.lost)
    }

    /** No HOME yet: the radio may bind first and must get idle answers, not a crash. */
    @Test
    fun unattachedHubAnswersIdle() {
        assertFalse(TunerHub.binder.claim())
        assertFalse(TunerHub.binder.isClaimed)
        assertEquals(TunerState.NO_PRESET, TunerHub.binder.state.preset)
        TunerHub.binder.sendKey(CarService.RADIO_KEY_SEEK_UP)
    }
}
