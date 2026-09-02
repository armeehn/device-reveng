package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [CarPlayDecode] to the status vocabulary the gateway switches on
 * (`ZlinkManage.java:205-300`) and the telephone int it sends (`:552`).
 */
class CarPlayDecodeTest {

    private val t0 = 1_000L

    /** Replay a `status` sequence, every broadcast one tick apart. */
    private fun replay(vararg statuses: Pair<String, String?>): CarPlayState =
        statuses.foldIndexed(CarPlayState()) { i, state, (status, mode) ->
            CarPlayDecode.applyStatus(state, status, mode, t0 + i)
        }

    @Test
    fun connectedCarriesThePhoneMode() {
        val state = replay("CONNECTED" to "carplay_wireless")

        assertTrue(state.connected)
        assertEquals("carplay_wireless", state.phoneMode)
        assertEquals(CarPlayState.Link.WIRELESS, state.link)
        assertEquals(t0, state.lastEventMs)
    }

    @Test
    fun disconnectClearsEverything() {
        val state = replay(
            "CONNECTED" to "carplay_wired",
            "PHONE_CALL_ON" to null,
            "DISCONNECT" to null,
        )

        assertEquals(CarPlayState(lastEventMs = t0 + 2), state)
    }

    @Test
    fun exitKeepsTheSession() {
        // The gateway only leaves SRC_CARPLAY on EXIT; the phone is still attached.
        assertTrue(replay("CONNECTED" to "auto_wired", "EXIT" to null).connected)
    }

    @Test
    fun callOnAndOffToggleInCallOnly() {
        val on = replay("CONNECTED" to "carplay_wireless", "PHONE_CALL_ON" to null)
        assertTrue(on.inCall)
        assertTrue(on.connected)

        val off = CarPlayDecode.applyStatus(on, "PHONE_CALL_OFF", null, t0 + 9)
        assertFalse(off.inCall)
        assertTrue(off.connected)
        assertEquals("carplay_wireless", off.phoneMode)
    }

    @Test
    fun mainAudioStartCountsAsConnected() {
        val state = replay("MAIN_AUDIO_START" to null)

        assertTrue(state.connected)
        assertNull(state.phoneMode)
        assertNull(state.link)
    }

    @Test
    fun pageAndAudioStopChangeNothingButTheStamp() {
        val before = replay("CONNECTED" to "carplay_wireless")
        val after = replay(
            "CONNECTED" to "carplay_wireless",
            "MAIN_PAGE_SHOW" to null,
            "MAIN_PAGE_HIDDEN" to null,
            "MAIN_AUDIO_STOP" to null,
        )

        assertEquals(before.copy(lastEventMs = t0 + 3), after)
    }

    @Test
    fun blankPhoneModeKeepsTheLastKnown() {
        val state = replay("CONNECTED" to "carplay_wired", "CONNECTED" to "")

        assertEquals("carplay_wired", state.phoneMode)
        assertEquals(CarPlayState.Link.WIRED, state.link)
    }

    @Test
    fun telephoneEventDrivesInCall() {
        val connected = replay("CONNECTED" to "carplay_wireless")

        val inCall = CarPlayDecode.applyTelephone(connected, 1, t0 + 5)
        assertTrue(inCall.inCall)
        assertEquals(t0 + 5, inCall.lastEventMs)

        val idle = CarPlayDecode.applyTelephone(inCall, 0, t0 + 6)
        assertFalse(idle.inCall)
        assertTrue(idle.connected)
    }

    @Test
    fun unknownTelephoneValueIsIgnored() {
        val connected = replay("CONNECTED" to "carplay_wireless")

        assertEquals(connected, CarPlayDecode.applyTelephone(connected, 7, t0 + 5))
    }

    @Test
    fun linkNeedsAKnownSuffix() {
        assertNull(CarPlayState(phoneMode = "carplay").link)
        assertNull(CarPlayState(phoneMode = "").link)
        assertEquals(CarPlayState.Link.WIRELESS, CarPlayState(phoneMode = "android_mirror_wireless").link)
    }
}
