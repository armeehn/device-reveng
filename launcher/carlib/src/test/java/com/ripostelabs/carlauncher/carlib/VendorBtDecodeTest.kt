package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The HBCP payload encoding is unconfirmed, so the decoder's contract is "never fabricate":
 * only an unambiguous 0/1 moves a boolean, a device identity only ever raises `connected`,
 * and an undecodable event still proves the channel is alive via lastEventMs.
 */
class VendorBtDecodeTest {

    private val start = VendorBtState()
    private fun power(name: String = "POWER_STATUS") = CarEvents.HBCP_ACTION_PREFIX + name
    private fun connect(name: String = "CONNECT_STATUS") = CarEvents.HBCP_ACTION_PREFIX + name

    @Test
    fun undecodableEventOnlyStampsTheTimestamp() {
        val next = VendorBtDecode.apply(start, power(), mapOf("blob" to "???"), 42L)
        assertEquals(VendorBtState(powered = null, connected = null, lastEventMs = 42L), next)
    }

    @Test
    fun powerStatusOneMeansOn() {
        val next = VendorBtDecode.apply(start, power(), mapOf("PowerStatus" to 1), 1L)
        assertEquals(true, next.powered)
        assertNull(next.connected) // power on says nothing about connections
    }

    @Test
    fun powerOffAlsoClearsConnected() {
        val on = VendorBtState(powered = true, connected = true, lastEventMs = 1L)
        val next = VendorBtDecode.apply(on, power(), mapOf("PowerStatus" to 0), 2L)
        assertEquals(false, next.powered)
        assertEquals(false, next.connected)
    }

    @Test
    fun nonBinaryStatusValueIsIgnored() {
        // Could be an enum (e.g. 2=connecting) — refusing to map it is the whole point.
        val next = VendorBtDecode.apply(start, connect(), mapOf("ConnectState" to 2), 3L)
        assertNull(next.connected)
        assertEquals(3L, next.lastEventMs)
    }

    @Test
    fun connectStatusMapsZeroAndOne() {
        val on = VendorBtDecode.apply(start, connect(), mapOf("ConnectStatus" to 1), 4L)
        assertEquals(true, on.connected)
        val off = VendorBtDecode.apply(on, connect(), mapOf("ConnectStatus" to 0), 5L)
        assertEquals(false, off.connected)
    }

    @Test
    fun hshfStatusCountsAsConnectionEvidence() {
        val next = VendorBtDecode.apply(start, connect("HSHF_STATUS"), mapOf("HshfState" to 1), 6L)
        assertEquals(true, next.connected)
    }

    @Test
    fun byteAndBooleanStatusCarriersDecodeToo() {
        val b = VendorBtDecode.apply(start, power(), mapOf("PowerStatus" to 1.toByte()), 7L)
        assertEquals(true, b.powered)
        val bool = VendorBtDecode.apply(start, power(), mapOf("PowerState" to false), 8L)
        assertEquals(false, bool.powered)
    }

    @Test
    fun deviceNameOnlyRaisesConnected() {
        val next = VendorBtDecode.apply(
            start, connect("CONNECTED_DEVICE"), mapOf("DeviceName" to "Pixel 9"), 9L,
        )
        assertEquals(true, next.connected)

        // A blank name is NOT evidence of a disconnect — it stays whatever it was.
        val already = VendorBtState(connected = true, lastEventMs = 9L)
        val blank = VendorBtDecode.apply(
            already, connect("CONNECTED_DEVICE"), mapOf("DeviceName" to ""), 10L,
        )
        assertEquals(true, blank.connected)
    }

    @Test
    fun twoNumericExtrasWithoutHintAreAmbiguous() {
        val next = VendorBtDecode.apply(start, connect(), mapOf("a" to 1, "b" to 0), 11L)
        assertNull(next.connected)
    }

    @Test
    fun singleUnhintedNumericExtraStillDecodes() {
        val next = VendorBtDecode.apply(start, power(), mapOf("x" to 1), 12L)
        assertEquals(true, next.powered)
    }
}
