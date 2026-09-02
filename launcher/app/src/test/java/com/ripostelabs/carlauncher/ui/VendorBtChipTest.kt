package com.ripostelabs.carlauncher.ui

import com.ripostelabs.carlauncher.carlib.VendorBtState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The vendor HBCP verdict is folded into the BT chip ADDITIVELY only: its decode is
 * unconfirmed, so it may claim a connection Android cannot see (the vendor bt module owns the
 * phone link) but must never erase one Android has confirmed.
 */
class VendorBtChipTest {

    private val absent = BtStatus(present = false, on = false, connectedCount = 0)
    private val onIdle = BtStatus(present = true, on = true, connectedCount = 0)
    private val onConnected = BtStatus(present = true, on = true, connectedCount = 2)

    @Test
    fun allNullVendorStateChangesNothing() {
        val vendor = VendorBtState(lastEventMs = 123L)
        assertEquals(absent, applyVendorBt(absent, vendor))
        assertEquals(onConnected, applyVendorBt(onConnected, vendor))
    }

    @Test
    fun vendorPowerRaisesTheChipWhereAndroidSeesNoAdapter() {
        val vendor = VendorBtState(powered = true, lastEventMs = 1L)
        assertEquals(
            BtStatus(present = true, on = true, connectedCount = 0),
            applyVendorBt(absent, vendor),
        )
    }

    @Test
    fun vendorConnectionShowsWhereAndroidSeesNone() {
        val vendor = VendorBtState(connected = true, lastEventMs = 1L)
        assertEquals(
            BtStatus(present = true, on = true, connectedCount = 1),
            applyVendorBt(onIdle, vendor),
        )
        // Even with no visible Android adapter at all — the vendor module owns the phone link.
        assertEquals(
            BtStatus(present = true, on = true, connectedCount = 1),
            applyVendorBt(absent, vendor),
        )
    }

    @Test
    fun vendorNeverLowersAnAndroidConfirmedState() {
        val vendor = VendorBtState(powered = false, connected = false, lastEventMs = 1L)
        assertEquals(onConnected, applyVendorBt(onConnected, vendor))
        assertEquals(onIdle, applyVendorBt(onIdle, vendor))
    }

    @Test
    fun vendorConnectionDoesNotInflateAnAndroidCount() {
        val vendor = VendorBtState(connected = true, lastEventMs = 1L)
        assertEquals(onConnected, applyVendorBt(onConnected, vendor))
    }
}
