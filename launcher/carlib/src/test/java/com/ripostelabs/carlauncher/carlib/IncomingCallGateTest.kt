package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCallGateTest {

    private val number = "+12505550142"

    private fun snap(vararg calls: HfCall, audio: HfAudio = HfAudio.OFF) = BtCarKitSnapshot(
        adapterOn = true,
        hfConnected = true,
        calls = calls.toList(),
        hfAudio = audio,
    )

    private val ringing = snap(HfCall(HfCallState.INCOMING, number))
    private val idle = snap()
    private val noCarPlay = CarPlayState()

    @Test
    fun `idle phone shows nothing`() {
        val v = IncomingCallGate().decide(idle, noCarPlay, LauncherFront.ELSEWHERE)
        assertEquals(IncomingCallView.NONE, v)
    }

    @Test
    fun `ringing over another app shows the window and rings`() {
        val v = IncomingCallGate().decide(ringing, noCarPlay, LauncherFront.ELSEWHERE)
        assertTrue(v.show)
        assertTrue(v.ring)
        assertEquals(number, v.number)
    }

    @Test
    fun `ringing on the Phone screen rings but leaves the window to the screen`() {
        val v = IncomingCallGate().decide(ringing, noCarPlay, LauncherFront.PHONE_SCREEN)
        assertFalse(v.show)
        assertTrue(v.ring)
    }

    @Test
    fun `in-band ring from the phone silences the local ringtone`() {
        val inBand = snap(HfCall(HfCallState.INCOMING, number), audio = HfAudio.ON)
        val v = IncomingCallGate().decide(inBand, noCarPlay, LauncherFront.ELSEWHERE)
        assertTrue(v.show)
        assertFalse(v.ring)
    }

    @Test
    fun `waiting call shows the window without a ringtone over the live call`() {
        val waiting = snap(HfCall(HfCallState.ACTIVE, "1"), HfCall(HfCallState.WAITING, number))
        val v = IncomingCallGate().decide(waiting, noCarPlay, LauncherFront.ELSEWHERE)
        assertTrue(v.show)
        assertFalse(v.ring)
        assertEquals(number, v.number)
    }

    @Test
    fun `a CarPlay session owns the call, so no duplicate window`() {
        val gate = IncomingCallGate()
        val session = CarPlayState(connected = true)
        val call = CarPlayState(inCall = true)
        assertEquals(IncomingCallView.NONE, gate.decide(ringing, session, LauncherFront.ELSEWHERE))
        assertEquals(IncomingCallView.NONE, gate.decide(ringing, call, LauncherFront.ELSEWHERE))
    }

    @Test
    fun `answer or decline hides the window until that ring ends`() {
        val gate = IncomingCallGate()
        gate.decide(ringing, noCarPlay, LauncherFront.ELSEWHERE)
        gate.onAction()
        assertEquals(IncomingCallView.NONE, gate.decide(ringing, noCarPlay, LauncherFront.ELSEWHERE))

        // The ring ends, then the next call rings again.
        gate.decide(idle, noCarPlay, LauncherFront.ELSEWHERE)
        assertTrue(gate.decide(ringing, noCarPlay, LauncherFront.ELSEWHERE).show)
    }

    @Test
    fun `answered call closes the window`() {
        val gate = IncomingCallGate()
        gate.decide(ringing, noCarPlay, LauncherFront.ELSEWHERE)
        val active = snap(HfCall(HfCallState.ACTIVE, number))
        assertEquals(IncomingCallView.NONE, gate.decide(active, noCarPlay, LauncherFront.ELSEWHERE))
    }

    @Test
    fun `audio state extra maps to on only when connected`() {
        assertEquals(HfAudio.ON, HfAudio.of(HfAudio.STATE_CONNECTED))
        assertEquals(HfAudio.OFF, HfAudio.of(0))
        assertEquals(HfAudio.OFF, HfAudio.of(1))
    }
}
