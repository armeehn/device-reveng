package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory rows: what SharedPreferences holds on the device. */
class MapRows : SysVarLocalStore.Rows {
    val map = mutableMapOf<String, String>()
    var refuse = false

    override fun readAll(): Map<String, String> = map.toMap()

    override fun write(key: String, value: String): Boolean {
        if (refuse) {
            return false
        }
        map[key] = value
        return true
    }
}

/**
 * On Riposte OS 0.2 the launcher keeps the SysVar rows itself. A saved row must come back
 * after a restart (a new store over the same rows) and every row that sticks is announced.
 */
class SysVarLocalStoreTest {

    private val rows = MapRows()
    private val announced = mutableListOf<String>()
    private val store = SysVarLocalStore(rows) { k, v -> announced += "$k=$v" }

    @Test
    fun putRoundTripsThroughTheRows() {
        assertTrue(store.put("Sys_backcar_Video_Type", "3"))

        assertEquals("3", store.readAll()["Sys_backcar_Video_Type"])
        assertEquals("3", SysVarLocalStore(rows).readAll()["Sys_backcar_Video_Type"])
        assertEquals(listOf("Sys_backcar_Video_Type=3"), announced)
    }

    @Test
    fun refusedWriteIsNotAnnounced() {
        rows.refuse = true

        assertFalse(store.put("K", "1"))
        assertEquals(emptyList<String>(), announced)
    }

    @Test
    fun republishAnnouncesEverySavedRow() {
        rows.map["A"] = "1"
        rows.map["B"] = "2"

        store.republish()

        assertEquals(listOf("A=1", "B=2"), announced)
    }
}
