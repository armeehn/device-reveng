package com.reveng.carlauncher.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The session start must survive UI navigation and reset only on the ACC transition — holding
 * it inside the Dashboard's composition made every visit restart the timer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IgnitionSessionTest {

    @Test
    fun startsOnAccAlreadyOnAndHolds() = runTest {
        val accOn = MutableStateFlow(true)
        var now = 1_000L
        val session = IgnitionSession(backgroundScope, accOn, clock = { now })
        runCurrent()

        assertEquals(1_000L, session.startedAt.value)

        // Time passes, more collector churn: the start does not move.
        now = 9_000L
        accOn.value = true
        runCurrent()
        assertEquals(1_000L, session.startedAt.value)
    }

    @Test
    fun accOffClearsAndNextOnStartsFresh() = runTest {
        val accOn = MutableStateFlow(true)
        var now = 1_000L
        val session = IgnitionSession(backgroundScope, accOn, clock = { now })
        runCurrent()

        accOn.value = false
        runCurrent()
        assertNull(session.startedAt.value)

        now = 5_000L
        accOn.value = true
        runCurrent()
        assertEquals(5_000L, session.startedAt.value)
    }
}
