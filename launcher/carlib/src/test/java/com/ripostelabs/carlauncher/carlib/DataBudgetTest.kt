package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Tethered bytes are the owner's phone plan. The budget must stop at its limit, count protocol
 * overhead, start over each month, and never charge an unmetered network.
 */
class DataBudgetTest {

    private class MemStore : DataBudget.Store {
        override var month: String = ""
        override var usedBytes: Long = 0
    }

    private val zone = ZoneId.of("America/Vancouver")
    private var now = Instant.parse("2026-10-15T12:00:00Z")
    private val mb = 1024L * 1024

    private fun budget(store: DataBudget.Store = MemStore(), limitMb: Long = 200) =
        DataBudget(store, limitBytes = { limitMb * mb }, clock = { now }, zone = zone)

    @Test
    fun `unmetered Wi-Fi is free and never charged`() {
        val b = budget()
        assertTrue(b.allows(DataBudget.Link.UNMETERED, 10_000 * mb))
        b.charge(DataBudget.Link.UNMETERED, 500 * mb)
        assertEquals(0, b.usedBytes())
    }

    @Test
    fun `no network allows nothing`() {
        assertFalse(budget().allows(DataBudget.Link.NONE, 1))
    }

    @Test
    fun `tethered bytes count with overhead and stop at the limit`() {
        val b = budget(limitMb = 10)
        b.charge(DataBudget.Link.METERED, 9 * mb)
        assertEquals((9 * mb * DataBudget.OVERHEAD).toLong(), b.usedBytes())
        assertTrue(b.allows(DataBudget.Link.METERED, 100 * 1024))
        assertFalse(b.allows(DataBudget.Link.METERED, 1 * mb))
    }

    @Test
    fun `a new month starts from zero`() {
        val store = MemStore()
        val b = budget(store, limitMb = 10)
        b.charge(DataBudget.Link.METERED, 9 * mb)
        now = Instant.parse("2026-11-01T08:00:01Z")   // 01:00 on Nov 1 in Vancouver
        assertEquals(0, b.usedBytes())
        assertTrue(b.allows(DataBudget.Link.METERED, 9 * mb))
        assertEquals("2026-11", store.month)
    }

    @Test
    fun `the month follows local time, not UTC`() {
        val b = budget(limitMb = 10)
        b.charge(DataBudget.Link.METERED, 9 * mb)
        now = Instant.parse("2026-11-01T06:00:00Z")   // still Oct 31, 23:00 in Vancouver
        assertTrue(b.usedBytes() > 0)
    }

    @Test
    fun `usage survives a restart through the store`() {
        val store = MemStore()
        budget(store).charge(DataBudget.Link.METERED, 5 * mb)
        assertEquals((5 * mb * DataBudget.OVERHEAD).toLong(), budget(store).usedBytes())
    }

    @Test
    fun `a zero limit means tethered uploads are off`() {
        assertFalse(budget(limitMb = 0).allows(DataBudget.Link.METERED, 1))
    }
}
