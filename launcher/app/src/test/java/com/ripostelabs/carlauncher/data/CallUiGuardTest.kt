package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** RAV4-278: Android's call screen over a CarPlay session gives the panel back to CarPlay. */
class CallUiGuardTest {

    private fun top(component: String) = "  topResumedActivity=ActivityRecord{9f1c2 u0 $component t41}"

    private val inCall = top("com.android.dialer/com.android.incallui.InCallActivity")
    private val googleInCall = top("com.google.android.dialer/com.android.incallui.InCallActivity")
    private val dialerHome = top("com.android.dialer/.main.impl.MainActivity")
    private val carPlay = top("com.ripostelabs.projection/.CarPlayActivity")

    @Test
    fun callScreenOverCarPlayIsReclaimed() {
        assertTrue(CallUiGuard().onTop(inCall, CallUiGuard.Session.LIVE))
        assertTrue(CallUiGuard().onTop(googleInCall, CallUiGuard.Session.LIVE))
    }

    @Test
    fun noSessionLeavesTheCallScreen() {
        assertFalse(CallUiGuard().onTop(inCall, CallUiGuard.Session.NONE))
    }

    @Test
    fun theOwnersOwnDialerStays() {
        // The Phone tile opens the Dialer's main screen on purpose; only the call screen goes.
        assertFalse(CallUiGuard().onTop(dialerHome, CallUiGuard.Session.LIVE))
        assertFalse(CallUiGuard().onTop(carPlay, CallUiGuard.Session.LIVE))
        assertFalse(CallUiGuard().onTop(null, CallUiGuard.Session.LIVE))
    }

    @Test
    fun oneReclaimPerAppearance() {
        // A failed reclaim must not fire every poll; the next appearance fires again.
        val guard = CallUiGuard()
        assertTrue(guard.onTop(inCall, CallUiGuard.Session.LIVE))
        assertFalse(guard.onTop(inCall, CallUiGuard.Session.LIVE))
        assertFalse(guard.onTop(carPlay, CallUiGuard.Session.LIVE))
        assertTrue(guard.onTop(inCall, CallUiGuard.Session.LIVE))
    }
}
