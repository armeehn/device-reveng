package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The desk half of the 0.2 phone link: the AOSP car-kit proxies' state -> the btsuite-shaped
 * [VendorBtState] the Phone screen reads, and the Doctor row. The proxies themselves need a
 * phone and the car.
 */
class BtCarKitMapTest {

    private val allBound = BtCarKitSnapshot(adapterOn = true, bound = BtCarKit.PROFILES, settled = true)
    private val phone = allBound.copy(phoneName = "Pixel 9", hfConnected = true, sinkConnected = true)

    @Test
    fun profileIdsAreTheAospValues() {
        assertEquals(11, BtCarKit.PROFILE_A2DP_SINK)
        assertEquals(12, BtCarKit.PROFILE_AVRCP_CONTROLLER)
        assertEquals(16, BtCarKit.PROFILE_HEADSET_CLIENT)
        assertEquals(17, BtCarKit.PROFILE_PBAP_CLIENT)
    }

    /** The HF link's STATE_CONNECTING broadcast reads as CONNECTING until a device is on it. */
    @Test
    fun hfLinkConnectingReadsAsConnecting() {
        assertEquals(HfpState.CONNECTING, BtCarKitMap.hfp(allBound.copy(hfLink = HfLink.CONNECTING)))
        assertEquals(HfpState.READY, BtCarKitMap.hfp(allBound.copy(hfLink = HfLink.DISCONNECTED)))
        assertEquals(HfpState.CONNECTED, BtCarKitMap.hfp(phone.copy(hfLink = HfLink.CONNECTED)))
    }

    @Test
    fun profilesOnNeedsEveryProxyAndWaitsForTheBindWindow() {
        assertTrue(BtCarKitMap.profilesOn(allBound) == true)
        val missing = allBound.copy(bound = setOf(BtCarKit.PROFILE_A2DP_SINK))
        assertNull(BtCarKitMap.profilesOn(missing.copy(settled = false)))
        assertEquals(false, BtCarKitMap.profilesOn(missing))
    }

    @Test
    fun hfpStateFollowsTheLeadCall() {
        assertEquals(HfpState.READY, BtCarKitMap.hfp(allBound))
        assertEquals(HfpState.READY, BtCarKitMap.hfp(phone.copy(adapterOn = false)))
        assertEquals(HfpState.CONNECTED, BtCarKitMap.hfp(phone))
        assertEquals(HfpState.INCOMING_CALL, BtCarKitMap.hfp(phone.copy(calls = listOf(HfCall(HfCallState.INCOMING)))))
        assertEquals(HfpState.INCOMING_CALL, BtCarKitMap.hfp(phone.copy(calls = listOf(HfCall(HfCallState.WAITING)))))
        assertEquals(HfpState.OUTGOING_CALL, BtCarKitMap.hfp(phone.copy(calls = listOf(HfCall(HfCallState.DIALING)))))
        assertEquals(HfpState.OUTGOING_CALL, BtCarKitMap.hfp(phone.copy(calls = listOf(HfCall(HfCallState.ALERTING)))))
        assertEquals(HfpState.ACTIVE_CALL, BtCarKitMap.hfp(phone.copy(calls = listOf(HfCall(HfCallState.ACTIVE)))))
        assertEquals(HfpState.ACTIVE_CALL, BtCarKitMap.hfp(phone.copy(calls = listOf(HfCall(HfCallState.HELD)))))
        // A terminated call is history, not a state.
        assertEquals(HfpState.CONNECTED, BtCarKitMap.hfp(phone.copy(calls = listOf(HfCall(HfCallState.TERMINATED)))))
    }

    @Test
    fun aRingingCallLeadsOverAnActiveOne() {
        val calls = listOf(HfCall(HfCallState.ACTIVE, "111"), HfCall(HfCallState.WAITING, "222"))
        assertEquals("222", BtCarKitMap.leadCall(calls)?.number)
        assertEquals(HfpState.INCOMING_CALL, BtCarKitMap.hfp(phone.copy(calls = calls)))
    }

    @Test
    fun vendorViewCarriesPhoneCallAndSinkState() {
        val ringing = phone.copy(calls = listOf(HfCall(HfCallState.INCOMING, "+16045551234")), audioPlaying = true)
        val v = BtCarKitMap.vendorView(ringing, nowMs = 1_000L)
        assertEquals(true, v.powered)
        assertEquals(true, v.connected)
        assertEquals(true, v.inCall)
        assertEquals(HfpState.INCOMING_CALL, v.hfp)
        assertEquals("Pixel 9", v.deviceName)
        assertEquals("+16045551234", v.callerNumber)
        assertEquals(true, v.avPlaying)
        assertNull(v.speakingSec)
        assertEquals(1_000L, v.lastEventMs)
    }

    @Test
    fun vendorViewIdleAndSinkOnly() {
        val idle = BtCarKitMap.vendorView(phone, nowMs = 5L)
        assertEquals(false, idle.inCall)
        assertNull(idle.callerNumber)
        assertEquals(false, idle.avPlaying)

        val musicOnly = BtCarKitMap.vendorView(phone.copy(hfConnected = false), nowMs = 5L)
        assertEquals(true, musicOnly.connected)
        assertEquals(HfpState.READY, musicOnly.hfp)

        val nothing = BtCarKitMap.vendorView(allBound, nowMs = 5L)
        assertEquals(false, nothing.connected)
        assertNull(nothing.avPlaying)
        assertNull(nothing.deviceName)
    }

    @Test
    fun speakingTimerRunsFromTheFirstActiveTick() {
        val active = listOf(HfCall(HfCallState.ACTIVE))
        assertNull(BtCarKitMap.activeSince(null, listOf(HfCall(HfCallState.INCOMING)), 10L))
        assertEquals(10L, BtCarKitMap.activeSince(null, active, 10L))
        assertEquals(10L, BtCarKitMap.activeSince(10L, active, 99L))
        assertNull(BtCarKitMap.activeSince(10L, listOf(HfCall(HfCallState.TERMINATED)), 99L))

        val v = BtCarKitMap.vendorView(phone.copy(calls = active, activeSinceMs = 10_000L), nowMs = 75_500L)
        assertEquals(65, v.speakingSec)
    }

    @Test
    fun doctorReading() {
        val vendor = BtCarKitMap.reading(null)
        assertTrue(vendor.ok)
        assertEquals(BtCarKitMap.VENDOR_DETAIL, vendor.detail)

        assertFalse(BtCarKitMap.reading(allBound.copy(adapterOn = false)).ok)
        assertFalse(BtCarKitMap.reading(BtCarKitSnapshot(adapterOn = true)).ok)

        val on = BtCarKitMap.reading(phone)
        assertTrue(on.ok)
        assertEquals(BtCarKitMap.TITLE, on.title)
        assertEquals("HFP client on, A2DP sink on, AVRCP controller on, PBAP client on. Phone: Pixel 9 connected.", on.detail)

        val off = BtCarKitMap.reading(allBound.copy(bound = setOf(BtCarKit.PROFILE_A2DP_SINK)))
        assertFalse(off.ok)
        assertEquals("HFP client OFF, A2DP sink on, AVRCP controller OFF, PBAP client OFF. Phone: no phone connected.", off.detail)
    }
}
