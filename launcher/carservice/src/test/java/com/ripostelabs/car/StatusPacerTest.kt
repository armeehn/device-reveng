package com.ripostelabs.car

import com.ripostelabs.carlauncher.carlib.McuOwner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusPacerTest {

    private fun running(frames: Long, acked: Boolean = true) = McuOwner.Status.Running(acked, frames, 0, 0)

    @Test
    fun firstStatusGoesOut() {
        assertTrue(StatusPacer(1_000).due(McuOwner.Status.Idle, 0))
    }

    @Test
    fun counterOnlyChangesWaitForTheGap() {
        val p = StatusPacer(1_000)
        assertTrue(p.due(running(1), 0))
        assertFalse(p.due(running(2), 500))
        assertTrue(p.due(running(3), 1_000))
    }

    @Test
    fun stateChangesGoOutAtOnce() {
        val p = StatusPacer(1_000)
        assertTrue(p.due(running(1, acked = false), 0))
        assertTrue(p.due(running(2, acked = true), 10))
        assertTrue(p.due(McuOwner.Status.Failed("link dead"), 20))
        assertTrue(p.due(McuOwner.Status.Failed("link closed"), 30))
    }
}
