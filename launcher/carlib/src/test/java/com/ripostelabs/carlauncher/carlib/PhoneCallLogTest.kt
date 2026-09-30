package com.ripostelabs.carlauncher.carlib

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** RAV4-162: `CallLog.Calls` rows (written by the PBAP client) map onto the call list's entries. */
class PhoneCallLogTest {

    // 2026-09-02 14:05:09 UTC.
    private val when_ = 1_788_357_909_000L

    @Test
    fun `a missed row maps with its date and time`() {
        val entry = PhoneCallLog.entry(
            name = "Alice", number = "+16041234567", dateMs = when_, type = 3, zone = ZoneOffset.UTC,
        )
        assertEquals(
            VendorCallLog.Entry("Alice", "+16041234567", "2026-09-02", "14:05:09", VendorCallLog.CallType.MISSED),
            entry,
        )
    }

    @Test
    fun `call log types map onto the three tabs`() {
        fun type(code: Int) =
            PhoneCallLog.entry(null, "1", when_, code, ZoneOffset.UTC)?.type
        assertEquals(VendorCallLog.CallType.RECEIVED, type(1))
        assertEquals(VendorCallLog.CallType.DIALED, type(2))
        assertEquals(VendorCallLog.CallType.MISSED, type(3))
        assertEquals(VendorCallLog.CallType.MISSED, type(5))    // rejected: the driver still wants to call back
        assertEquals(VendorCallLog.CallType.RECEIVED, type(7))  // answered on another device
        assertNull(type(4))                                     // voicemail: no call to show a direction for
    }

    @Test
    fun `a row without a number is dropped`() {
        assertNull(PhoneCallLog.entry("Ghost", null, when_, 1, ZoneOffset.UTC))
        assertNull(PhoneCallLog.entry("Ghost", " ", when_, 1, ZoneOffset.UTC))
    }

    @Test
    fun `the time follows the car's zone`() {
        val entry = PhoneCallLog.entry(null, "1", when_, 1, ZoneOffset.ofHours(-7))
        assertEquals("2026-09-02", entry?.date)
        assertEquals("07:05:09", entry?.time)
    }
}
