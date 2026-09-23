package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Which way the reverse picture comes: the AIS client where the OS owns the car, camera2 elsewhere. */
class ReverseFeedPathTest {

    @Test
    fun stockSlotStaysOnCamera2WithoutProbingTheLib() {
        var probed = false

        val path = ReverseFeedPath.choose(ownerEnabled = false) { probed = true; null }

        assertEquals(ReverseFeedPath.CAMERA2, path)
        assertFalse(probed)
    }

    @Test
    fun ownerWithTheClientLibTakesAis() {
        assertEquals(ReverseFeedPath.AIS, ReverseFeedPath.choose(ownerEnabled = true) { null })
    }

    @Test
    fun ownerWithoutTheClientLibFallsBackToCamera2() {
        val path = ReverseFeedPath.choose(ownerEnabled = true) { "dlopen failed: not found" }

        assertEquals(ReverseFeedPath.CAMERA2, path)
    }
}
