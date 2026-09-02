package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins the `calllist` row shape (`btsuite/CallRecManager.java:129-135`) and its mapping. */
class VendorCallLogTest {

    private fun row(vararg cols: Pair<String, String?>): VendorCallLog.Row {
        val map = cols.toMap()
        return VendorCallLog.Row { map[it] }
    }

    @Test
    fun fullRowMaps() {
        val entry = VendorCallLog.entry(
            row("name" to "Alice", "num" to "+16041234567", "date" to "2026-09-02",
                "time" to "14:05:09", "calltype" to "4"),
        )
        assertEquals(
            VendorCallLog.Entry("Alice", "+16041234567", "2026-09-02", "14:05:09",
                VendorCallLog.CallType.MISSED),
            entry,
        )
        assertEquals("Alice", entry?.label)
    }

    @Test
    fun blankNameFallsBackToNumber() {
        val entry = VendorCallLog.entry(row("name" to "", "num" to "911", "calltype" to "3"))
        assertEquals("911", entry?.label)
        assertEquals(VendorCallLog.CallType.DIALED, entry?.type)
        assertEquals("", entry?.date)
    }

    @Test
    fun rowWithoutNumberIsDropped() {
        assertNull(VendorCallLog.entry(row("name" to "Ghost", "num" to " ")))
        assertNull(VendorCallLog.entry(row("name" to "Ghost")))
    }

    @Test
    fun unknownOrSelectorTypeMapsToNull() {
        assertNull(VendorCallLog.entry(row("num" to "1", "calltype" to "5"))?.type)
        assertNull(VendorCallLog.entry(row("num" to "1", "calltype" to "x"))?.type)
        assertNull(VendorCallLog.entry(row("num" to "1"))?.type)
    }

    @Test
    fun providerAddress() {
        assertEquals("content://com.szchoiceway.btsuite.CallListProvider/query", VendorCallLog.QUERY_URI)
        assertEquals(5, VendorCallLog.CallType.ALL.code)
        assertEquals(2, VendorCallLog.CallType.RECEIVED.code)
    }
}
