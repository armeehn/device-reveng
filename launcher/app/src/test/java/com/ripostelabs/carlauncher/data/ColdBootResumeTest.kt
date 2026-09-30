package com.ripostelabs.carlauncher.data

import com.ripostelabs.carlauncher.carlib.McuOwnerProtocol.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColdBootResumeTest {

    // RAV4-170: each resumable source opens the suite app that plays it.
    @Test
    fun mapsEachSourceToItsSuiteApp() {
        assertEquals("com.ripostelabs.radio", ColdBootResume.appFor(Mode.RADIO))
        assertEquals("com.ripostelabs.music", ColdBootResume.appFor(Mode.MUSIC))
        assertEquals("com.ripostelabs.bluetooth", ColdBootResume.appFor(Mode.BT_MUSIC))
        assertEquals("com.ripostelabs.video", ColdBootResume.appFor(Mode.MOVIE))
        assertNull(ColdBootResume.appFor(Mode.AUX))
        assertNull(ColdBootResume.appFor(null))
    }

    // Once per boot: a launcher restart or an ACC wake in the same boot must not relaunch.
    @Test
    fun resumesOncePerBoot() {
        assertTrue(ColdBootResume.due(bootCount = 12, lastResumed = 11))
        assertFalse(ColdBootResume.due(bootCount = 12, lastResumed = 12))
        assertFalse(ColdBootResume.due(bootCount = null, lastResumed = 11))
    }

    // Only the players take a play key; the radio plays once its app claims the tuner.
    @Test
    fun sendsPlayOnlyToPlayers() {
        assertTrue(ColdBootResume.needsPlay(Mode.MUSIC))
        assertTrue(ColdBootResume.needsPlay(Mode.BT_MUSIC))
        assertFalse(ColdBootResume.needsPlay(Mode.RADIO))
    }
}
