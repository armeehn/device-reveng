package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the HBCP contract from `btsuite/BTUtils.java`: fixed DATA_INT / DATA_STR extras, the
 * HSHF table (>= 3 connected, > 3 in call), and that anything outside the table only stamps
 * the timestamp.
 */
class VendorBtDecodeTest {

    private val start = VendorBtState()
    private fun action(suffix: String) = CarEvents.HBCP_ACTION_PREFIX + suffix
    private fun ints(value: Int) = mapOf(VendorBtDecode.EXTRA_INT to value)
    private fun hshf(state: Int, prev: VendorBtState = start, at: Long = 1L) =
        VendorBtDecode.apply(prev, action(VendorBtDecode.EVT_HSHF), ints(state), at)

    @Test
    fun unknownEventOnlyStampsTheTimestamp() {
        val next = VendorBtDecode.apply(start, action("PAIR_STATUS"), ints(1), 42L)
        assertEquals(start.copy(lastEventMs = 42L), next)
    }

    @Test
    fun powerOnAndOff() {
        val on = VendorBtDecode.apply(start, action(VendorBtDecode.EVT_POWER), ints(1), 1L)
        assertEquals(true, on.powered)
        assertNull(on.connected) // power on says nothing about a phone

        val live = on.copy(connected = true, inCall = true)
        val off = VendorBtDecode.apply(live, action(VendorBtDecode.EVT_POWER), ints(0), 2L)
        assertEquals(false, off.powered)
        assertEquals(false, off.connected)
        assertEquals(false, off.inCall)
    }

    @Test
    fun hshfTableDrivesConnectedAndInCall() {
        val expected = mapOf(
            VendorBtDecode.HSHF_INITIALISING to (false to false),
            VendorBtDecode.HSHF_READY to (false to false),
            VendorBtDecode.HSHF_CONNECTING to (false to false),
            VendorBtDecode.HSHF_CONNECTED to (true to false),
            VendorBtDecode.HSHF_OUTGOING_CALL to (true to true),
            VendorBtDecode.HSHF_INCOMING_CALL to (true to true),
            VendorBtDecode.HSHF_ACTIVE_CALL to (true to true),
        )
        for ((state, verdict) in expected) {
            val next = hshf(state)
            assertEquals("hshf=$state connected", verdict.first, next.connected)
            assertEquals("hshf=$state inCall", verdict.second, next.inCall)
            assertEquals(state, next.hshf)
        }
    }

    @Test
    fun hshfGetStatusIsTheSameTable() {
        val next = VendorBtDecode.apply(
            start, action(VendorBtDecode.EVT_HSHF_GET), ints(VendorBtDecode.HSHF_ACTIVE_CALL), 3L,
        )
        assertEquals(true, next.inCall)
    }

    @Test
    fun hshfOutOfRangeOrMissingIsIgnored() {
        assertNull(hshf(7).connected)
        assertNull(hshf(-1).connected)
        val missing = VendorBtDecode.apply(start, action(VendorBtDecode.EVT_HSHF), emptyMap(), 4L)
        assertNull(missing.connected)
        assertEquals(4L, missing.lastEventMs)
    }

    @Test
    fun hshfDropLowersConnected() {
        val connected = hshf(VendorBtDecode.HSHF_CONNECTED)
        val ready = hshf(VendorBtDecode.HSHF_READY, prev = connected, at = 2L)
        assertEquals(false, ready.connected)
    }

    @Test
    fun deviceNameOnlyRaisesConnected() {
        val named = VendorBtDecode.apply(
            start, action(VendorBtDecode.EVT_DEVICE_NAME),
            mapOf(VendorBtDecode.EXTRA_STR to "Pixel 9", VendorBtDecode.EXTRA_INT to 0), 5L,
        )
        assertEquals("Pixel 9", named.deviceName)
        assertEquals(true, named.connected)

        // A blank name is a re-send with nothing stored, not a disconnect.
        val blank = VendorBtDecode.apply(
            named, action(VendorBtDecode.EVT_DEVICE_NAME),
            mapOf(VendorBtDecode.EXTRA_STR to "", VendorBtDecode.EXTRA_INT to 0), 6L,
        )
        assertEquals(true, blank.connected)
        assertEquals("", blank.deviceName)
    }

    @Test
    fun avStatusCarriesPlayStateAndTitle() {
        val playing = VendorBtDecode.apply(
            start, action(VendorBtDecode.EVT_AV_STATUS),
            mapOf(VendorBtDecode.EXTRA_INT to VendorBtDecode.AV_PLAYING, VendorBtDecode.EXTRA_STR to "Song"),
            7L,
        )
        assertEquals(true, playing.avPlaying)
        assertEquals("Song", playing.avTitle)
        assertNull(playing.connected) // AV status is not a connection verdict

        val paused = VendorBtDecode.apply(
            playing, action(VendorBtDecode.EVT_AV_STATUS), ints(VendorBtDecode.AV_PAUSED), 8L,
        )
        assertEquals(false, paused.avPlaying)
    }

    @Test
    fun speakingTimeIntArrayIsNotMisreadAsAnInt() {
        val next = VendorBtDecode.apply(
            start, action(VendorBtDecode.EVT_SPEAKING_TIME),
            mapOf(VendorBtDecode.EXTRA_INT to intArrayOf(1, 30)), 9L,
        )
        assertEquals(start.copy(lastEventMs = 9L), next)
    }

    @Test
    fun byteCarrierDecodesLikeAnInt() {
        val next = VendorBtDecode.apply(
            start, action(VendorBtDecode.EVT_POWER), mapOf(VendorBtDecode.EXTRA_INT to 1.toByte()), 10L,
        )
        assertEquals(true, next.powered)
    }
}
