package com.ripostelabs.carlauncher.ui

import com.ripostelabs.carlauncher.carlib.HfpState
import com.ripostelabs.carlauncher.carlib.VendorBtState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RAV4-50: HFP state -> buttons, dial-number validation, the status chip text. */
class PhoneLogicTest {

    @Test
    fun buttonsFollowTheHfpTable() {
        val none = PhoneLogic.CallButtons.NONE
        assertEquals(none, PhoneLogic.buttons(null))
        assertEquals(none, PhoneLogic.buttons(HfpState.INITIALISING))
        assertEquals(none, PhoneLogic.buttons(HfpState.READY))
        assertEquals(none, PhoneLogic.buttons(HfpState.CONNECTING))
        assertEquals(none, PhoneLogic.buttons(HfpState.CONNECTED))
        assertEquals(PhoneLogic.CallButtons(answer = false, hangUp = true), PhoneLogic.buttons(HfpState.OUTGOING_CALL))
        assertEquals(PhoneLogic.CallButtons(answer = true, hangUp = true), PhoneLogic.buttons(HfpState.INCOMING_CALL))
        assertEquals(PhoneLogic.CallButtons(answer = false, hangUp = true), PhoneLogic.buttons(HfpState.ACTIVE_CALL))
    }

    @Test
    fun everyStateHasALabel() {
        assertEquals("No signal", PhoneLogic.stateLabel(null))
        for (state in HfpState.entries) {
            assertTrue(state.name, PhoneLogic.stateLabel(state).isNotBlank())
        }
        assertEquals("Incoming call", PhoneLogic.stateLabel(HfpState.INCOMING_CALL))
    }

    @Test
    fun dialableNumbers() {
        assertTrue(PhoneLogic.isDialable("911"))
        assertTrue(PhoneLogic.isDialable("+16041234567"))
        assertTrue(PhoneLogic.isDialable("*21#"))
        assertFalse(PhoneLogic.isDialable(""))
        assertFalse(PhoneLogic.isDialable("+"))
        assertFalse(PhoneLogic.isDialable("604 123"))
        assertFalse(PhoneLogic.isDialable("1+2"))
        assertFalse(PhoneLogic.isDialable("1".repeat(PhoneLogic.MAX_DIAL_LENGTH + 1)))
    }

    @Test
    fun canDialNeedsAnIdleConnectedPhone() {
        assertTrue(PhoneLogic.canDial(HfpState.CONNECTED, "911"))
        assertFalse(PhoneLogic.canDial(HfpState.ACTIVE_CALL, "911"))
        assertFalse(PhoneLogic.canDial(HfpState.READY, "911"))
        assertFalse(PhoneLogic.canDial(null, "911"))
        assertFalse(PhoneLogic.canDial(HfpState.CONNECTED, ""))
    }

    @Test
    fun appendAcceptsOnlyWhatTheNumberCanTake() {
        assertEquals("+", PhoneLogic.append("", '+'))
        assertEquals("1", PhoneLogic.append("1", '+')) // trunk prefix only leads
        assertEquals("1#", PhoneLogic.append("1", '#'))
        assertEquals("1", PhoneLogic.append("1", 'a'))
        val full = "1".repeat(PhoneLogic.MAX_DIAL_LENGTH)
        assertEquals(full, PhoneLogic.append(full, '2'))
        assertEquals("12", PhoneLogic.backspace("123"))
        assertEquals("", PhoneLogic.backspace(""))
    }

    @Test
    fun timerIsMinutesAndSeconds() {
        assertEquals("00:00", PhoneLogic.timer(0))
        assertEquals("01:30", PhoneLogic.timer(90))
        assertEquals("90:00", PhoneLogic.timer(90 * 60))
        assertEquals("00:00", PhoneLogic.timer(-5))
    }

    @Test
    fun chipOnlyWhileACallIsUp() {
        assertNull(PhoneLogic.callChip(VendorBtState()))
        assertNull(PhoneLogic.callChip(VendorBtState(hshf = HfpState.CONNECTED.code, inCall = false)))

        val ringing = VendorBtState(hshf = HfpState.INCOMING_CALL.code, inCall = true, callerNumber = "911")
        assertEquals("Incoming call · 911", PhoneLogic.callChip(ringing))

        val named = ringing.copy(callerName = "Alice")
        assertEquals("Incoming call · Alice", PhoneLogic.callChip(named))

        val ticking = named.copy(hshf = HfpState.ACTIVE_CALL.code, speakingSec = 75)
        assertEquals("01:15 · Alice", PhoneLogic.callChip(ticking))

        val anonymous = VendorBtState(hshf = HfpState.OUTGOING_CALL.code, inCall = true)
        assertEquals("Calling", PhoneLogic.callChip(anonymous))
    }
}
