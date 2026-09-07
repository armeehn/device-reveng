package com.ripostelabs.carlauncher.carlib

import com.ripostelabs.carlauncher.carlib.HiworldCanDecoder.SwcAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the steering-wheel button map recovered from the OEM's `OnHandleCanKeyCmd` on 2026-09-07.
 *
 * The raw id on its own is not usable: two pairs of ids mean the same thing because two physical
 * controls share an action, one id is context dependent, and three are unhandled on this car.
 */
class HiworldSwcActionTest {

    private fun basicStatus(buttonId: Int, pressed: Boolean = true): CanSignal.BasicStatus {
        val p = ByteArray(8)
        p[2] = buttonId.toByte()
        p[3] = if (pressed) 1 else 0
        return HiworldCanDecoder.decodePayload(0x11, p) as CanSignal.BasicStatus
    }

    @Test
    fun `every id this car uses resolves to an action`() {
        assertEquals(SwcAction.VOLUME_UP, HiworldCanDecoder.swcAction(1))
        assertEquals(SwcAction.VOLUME_DOWN, HiworldCanDecoder.swcAction(2))
        assertEquals(SwcAction.MUTE, HiworldCanDecoder.swcAction(3))
        assertEquals(SwcAction.VOICE, HiworldCanDecoder.swcAction(4))
        assertEquals(SwcAction.CALL, HiworldCanDecoder.swcAction(5))
        assertEquals(SwcAction.HANGUP, HiworldCanDecoder.swcAction(6))
        assertEquals(SwcAction.MODE, HiworldCanDecoder.swcAction(12))
        assertEquals(SwcAction.PLAY_PAUSE, HiworldCanDecoder.swcAction(15))
        assertEquals(SwcAction.BACK, HiworldCanDecoder.swcAction(16))
    }

    /** 13 and 14 duplicate 8 and 9 — two physical controls wired to one action. */
    @Test
    fun `duplicate ids resolve to the same action`() {
        assertEquals(SwcAction.PREV, HiworldCanDecoder.swcAction(8))
        assertEquals(SwcAction.PREV, HiworldCanDecoder.swcAction(13))
        assertEquals(SwcAction.NEXT, HiworldCanDecoder.swcAction(9))
        assertEquals(SwcAction.NEXT, HiworldCanDecoder.swcAction(14))
    }

    /**
     * Id 5 hangs up during a call and starts one otherwise. The decoder reports CALL and leaves
     * that decision to the caller, which knows the call state; guessing would be wrong half the
     * time. Pinned so nobody "helpfully" resolves it here.
     */
    @Test
    fun `the context-dependent call button is not resolved in the decoder`() {
        assertEquals(SwcAction.CALL, HiworldCanDecoder.swcAction(5))
        assertEquals(SwcAction.HANGUP, HiworldCanDecoder.swcAction(6))
    }

    @Test
    fun `ids this car does not use are UNKNOWN rather than a wrong guess`() {
        assertEquals(SwcAction.UNKNOWN, HiworldCanDecoder.swcAction(7))
        assertEquals(SwcAction.UNKNOWN, HiworldCanDecoder.swcAction(10))
        assertEquals(SwcAction.UNKNOWN, HiworldCanDecoder.swcAction(11))
        assertEquals(SwcAction.UNKNOWN, HiworldCanDecoder.swcAction(0))
        assertEquals(SwcAction.UNKNOWN, HiworldCanDecoder.swcAction(200))
    }

    @Test
    fun `the decoded frame carries both the raw id and the action`() {
        val s = basicStatus(15)
        assertEquals(15, s.swcButtonId)
        assertEquals(SwcAction.PLAY_PAUSE, s.swcAction)
        assertEquals(true, s.swcPressed)
        assertEquals(false, basicStatus(15, pressed = false).swcPressed)
    }
}
