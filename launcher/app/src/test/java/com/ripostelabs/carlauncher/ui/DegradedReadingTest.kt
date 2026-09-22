package com.ripostelabs.carlauncher.ui

import com.ripostelabs.carlauncher.carlib.VendorBtState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Absent readings must read as absent.
 *
 * Both mappings below used to collapse "the launcher cannot see this" onto a definite answer:
 * the BT chip said "nothing connected" when BLUETOOTH_CONNECT had been revoked (so the count
 * path was blind), and the ignition tile said "ACC on" on a unit that had never heard a single
 * vendor ACC broadcast, because the flow's fail-open default is `true`. Both are claims about
 * the car that the launcher is in no position to make.
 */
class DegradedReadingTest {

    // ---- Bluetooth chip -------------------------------------------------------------------

    @Test
    fun unreadableCountNeverClaimsNothingConnected() {
        val blind = BtStatus(present = true, on = true, connectedCount = null)
        assertEquals("Bluetooth on, connections unreadable", btLabel(blind))
    }

    @Test
    fun readableCountStillReadsPlainly() {
        assertEquals("Bluetooth off", btLabel(BtStatus(present = true, on = false, connectedCount = null)))
        assertEquals(
            "Bluetooth on, nothing connected",
            btLabel(BtStatus(present = true, on = true, connectedCount = 0)),
        )
        assertEquals(
            "Bluetooth: 2 connected",
            btLabel(BtStatus(present = true, on = true, connectedCount = 2)),
        )
    }

    @Test
    fun vendorConnectionResolvesAnUnreadableCount() {
        val blind = BtStatus(present = true, on = true, connectedCount = null)
        val vendor = VendorBtState(connected = true, lastEventMs = 1L)
        assertEquals(
            BtStatus(present = true, on = true, connectedCount = 1),
            applyVendorBt(blind, vendor),
        )
    }

    // ---- Ignition tile --------------------------------------------------------------------

    @Test
    fun unheardIgnitionReadsAsNoReading() {
        assertEquals(IGNITION_NO_READING, ignitionValue(null))
        assertEquals("no reading yet", ignitionNote(null))
    }

    @Test
    fun heardIgnitionReadsTheBroadcast() {
        assertEquals("ACC on", ignitionValue(true))
        assertEquals("ACC off", ignitionValue(false))
        assertEquals("vendor ACC broadcast", ignitionNote(true))
    }
}
