package com.ripostelabs.carlauncher.data

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The bug from the car: on Riposte OS 0.2 the vendor gateway is unbound and its provider is
 * gone, so every SysVar write FAILED and "Couldn't save … rolled back" appeared. On the owner
 * path the launcher's own store takes the write, the snapshot keeps the value, and the value
 * seeds the snapshot on the next start.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CarSettingsControllerOwnerTest {

    /** No vendor provider behind it, as on 0.2; the controller must not touch the resolver. */
    private class StubContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private val key = SettingKeys.BACKCAR_VIDEO_TYPE

    @Test
    fun ownerPathWriteSticksAndIsNotRolledBack() = runTest {
        val rows = MapRows()
        val controller = CarSettingsController(
            StubContext(),
            this,
            localStore = SysVarLocalStore(rows),
            io = StandardTestDispatcher(testScheduler),
            rootProbe = { false },
        )
        val event = async { controller.writeEvents.first() }
        runCurrent()

        controller.setString(key, "3")
        advanceUntilIdle()

        assertEquals("3", controller.getString(key))
        assertEquals("3", rows.map[key])
        assertEquals(CarSettingsController.WriteEvent(key, "3", ok = true), event.await())
    }

    @Test
    fun ownerPathSnapshotSeedsFromTheStore() = runTest {
        val rows = MapRows().apply { map[key] = "4" }
        val controller = CarSettingsController(
            StubContext(),
            this,
            localStore = SysVarLocalStore(rows),
            io = StandardTestDispatcher(testScheduler),
            rootProbe = { false },
        )

        advanceUntilIdle()

        assertEquals("4", controller.getString(key))
    }
}
