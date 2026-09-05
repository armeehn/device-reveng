package com.ripostelabs.carlauncher.data

import com.ripostelabs.carlauncher.carlib.HfpState
import com.ripostelabs.carlauncher.carlib.VendorBtState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the launcher knocks down btsuite's in-call floating window. */
class CallPopupGuardTest {

    private val idle = VendorBtState(hshf = HfpState.CONNECTED.code, inCall = false, connected = true)
    private val ringing = idle.copy(hshf = HfpState.INCOMING_CALL.code, inCall = true)
    private val active = ringing.copy(hshf = HfpState.ACTIVE_CALL.code)

    @Test
    fun neverOutsideACall() {
        assertFalse(CallPopupGuard.wants(VendorBtState(), VendorBtState()))
        assertFalse(CallPopupGuard.wants(ringing, idle))
        assertFalse(CallPopupGuard.wants(VendorBtState(), idle))
    }

    @Test
    fun callStartAndStateStepsHide() {
        assertTrue(CallPopupGuard.wants(idle, ringing))
        assertTrue(CallPopupGuard.wants(VendorBtState(), ringing)) // resumed mid-call
        assertTrue(CallPopupGuard.wants(ringing, active))
    }

    @Test
    fun timerTickHidesButAnUnchangedStateDoesNot() {
        assertFalse(CallPopupGuard.wants(active, active))
        assertFalse(CallPopupGuard.wants(active, active.copy(callerName = "Alice")))
        assertTrue(CallPopupGuard.wants(active, active.copy(speakingSec = 1)))
        assertTrue(CallPopupGuard.wants(active.copy(speakingSec = 1), active.copy(speakingSec = 2)))
    }
}
