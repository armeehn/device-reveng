package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * btsuite's reconnect policy (`BTService.java:1078-1088`, `:229-249`): arm 2 s after start
 * unless a phone is on or CarPlay is up, then retry each time a connect attempt falls back to
 * READY, four attempts in all (`mAutoConnectBtCount = 4`, `:120`).
 */
class BtAutoConnectTest {

    @Test
    fun armsOnlyWhenIdleAndNoCarPlay() {
        assertTrue(BtAutoConnect().arm(HfpState.READY, carPlay = false))
        assertTrue(BtAutoConnect().arm(null, carPlay = false))
        assertFalse(BtAutoConnect().arm(HfpState.CONNECTED, carPlay = false))
        assertFalse(BtAutoConnect().arm(HfpState.ACTIVE_CALL, carPlay = false))
        assertFalse(BtAutoConnect().arm(HfpState.READY, carPlay = true))
    }

    @Test
    fun retriesThreeTimesAfterTheFirstAttemptThenStops() {
        val policy = BtAutoConnect()
        assertTrue(policy.arm(HfpState.READY, carPlay = false))

        repeat(BtAutoConnect.ATTEMPTS - 1) {
            assertFalse(policy.onState(HfpState.CONNECTING))
            assertTrue(policy.onState(HfpState.READY))
        }
        assertFalse(policy.onState(HfpState.CONNECTING))
        assertFalse(policy.onState(HfpState.READY))
    }

    /** A READY that no CONNECTING preceded is not a failed attempt (`:239`). */
    @Test
    fun readyWithoutConnectingIsNotAFailure() {
        val policy = BtAutoConnect()
        policy.arm(HfpState.READY, carPlay = false)
        assertFalse(policy.onState(HfpState.READY))
        assertFalse(policy.onState(HfpState.READY))
    }

    /** A phone on the link ends the campaign (`:236-237`); later drops start nothing. */
    @Test
    fun connectedDisarms() {
        val policy = BtAutoConnect()
        policy.arm(HfpState.READY, carPlay = false)
        assertFalse(policy.onState(HfpState.CONNECTED))
        assertFalse(policy.onState(HfpState.CONNECTING))
        assertFalse(policy.onState(HfpState.READY))
    }

    @Test
    fun unarmedIgnoresStates() {
        val policy = BtAutoConnect()
        assertFalse(policy.onState(HfpState.CONNECTING))
        assertFalse(policy.onState(HfpState.READY))
    }
}
