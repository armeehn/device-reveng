package com.ripostelabs.carlauncher.ui

import com.ripostelabs.carlauncher.carlib.HfpState
import com.ripostelabs.carlauncher.carlib.VendorCallLog
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

    @Test
    fun `pad sends tones only during an active call`() {
        assertEquals(PhoneLogic.PadMode.TONES, PhoneLogic.padMode(HfpState.ACTIVE_CALL))
        assertEquals(PhoneLogic.PadMode.DIAL, PhoneLogic.padMode(HfpState.CONNECTED))
        assertEquals(PhoneLogic.PadMode.DIAL, PhoneLogic.padMode(HfpState.OUTGOING_CALL))
        assertEquals(PhoneLogic.PadMode.DIAL, PhoneLogic.padMode(HfpState.INCOMING_CALL))
        assertEquals(PhoneLogic.PadMode.DIAL, PhoneLogic.padMode(null))
    }

    @Test
    fun `call tabs keep only their own rows`() {
        fun e(n: String, t: VendorCallLog.CallType?) = VendorCallLog.Entry(null, n, "", "", t)
        val rows = listOf(
            e("1", VendorCallLog.CallType.MISSED),
            e("2", VendorCallLog.CallType.DIALED),
            e("3", VendorCallLog.CallType.RECEIVED),
            e("4", null),
        )
        assertEquals(rows, PhoneLogic.filter(rows, PhoneLogic.CallTab.ALL))
        assertEquals(listOf("1"), PhoneLogic.filter(rows, PhoneLogic.CallTab.MISSED)?.map { it.number })
        assertEquals(listOf("2"), PhoneLogic.filter(rows, PhoneLogic.CallTab.DIALED)?.map { it.number })
        assertNull(PhoneLogic.filter(null, PhoneLogic.CallTab.MISSED))
    }

    @Test
    fun `a contact's number is stripped to what the pad dials`() {
        assertEquals("+16041234567", PhoneLogic.telNumber("+1 (604) 123-4567"))
        assertEquals("6041234567", PhoneLogic.telNumber("604.123.4567"))
        assertEquals("*67#", PhoneLogic.telNumber("*67#"))
        assertNull(PhoneLogic.telNumber(""))
        assertNull(PhoneLogic.telNumber(null))
        assertNull(PhoneLogic.telNumber("ext"))
        assertNull(PhoneLogic.telNumber("1-800-FLOWERS"))   // letters: not dialable, never half-dialled
    }

    @Test
    fun `an empty number turns Call into Redial where the car kit can redial`() {
        assertEquals(PhoneLogic.CallKey.REDIAL, PhoneLogic.callKey(HfpState.CONNECTED, "", PhoneLogic.Redial.SUPPORTED))
        assertEquals(PhoneLogic.CallKey.CALL, PhoneLogic.callKey(HfpState.CONNECTED, "", PhoneLogic.Redial.UNSUPPORTED))
        assertEquals(PhoneLogic.CallKey.CALL, PhoneLogic.callKey(HfpState.CONNECTED, "911", PhoneLogic.Redial.SUPPORTED))
        assertEquals("Redial", PhoneLogic.CallKey.REDIAL.label)
        assertEquals("Call", PhoneLogic.CallKey.CALL.label)
    }

    @Test
    fun `redial needs a connected idle phone, call a dialable number too`() {
        assertTrue(PhoneLogic.callEnabled(HfpState.CONNECTED, "", PhoneLogic.CallKey.REDIAL))
        assertFalse(PhoneLogic.callEnabled(HfpState.READY, "", PhoneLogic.CallKey.REDIAL))
        assertFalse(PhoneLogic.callEnabled(HfpState.ACTIVE_CALL, "", PhoneLogic.CallKey.REDIAL))
        assertFalse(PhoneLogic.callEnabled(HfpState.CONNECTED, "", PhoneLogic.CallKey.CALL))
        assertTrue(PhoneLogic.callEnabled(HfpState.CONNECTED, "911", PhoneLogic.CallKey.CALL))
    }
}
