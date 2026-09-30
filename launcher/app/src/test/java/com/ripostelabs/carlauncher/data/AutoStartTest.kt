package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoStartTest {

    // RAV4-176: the chosen app starts once per boot at launch, and on every ACC wake.
    @Test
    fun launchStartsOncePerBoot() {
        assertTrue(AutoStart.due(AutoStart.Trigger.LAUNCH, "com.maps", bootCount = 4, lastBoot = 3))
        assertFalse(AutoStart.due(AutoStart.Trigger.LAUNCH, "com.maps", bootCount = 4, lastBoot = 4))
    }

    @Test
    fun everyWakeStarts() {
        assertTrue(AutoStart.due(AutoStart.Trigger.WAKE, "com.maps", bootCount = 4, lastBoot = 4))
    }

    // Blank means off, the default: nothing starts, and an unreadable boot count is not a boot.
    @Test
    fun blankOrUnknownStartsNothing() {
        assertFalse(AutoStart.due(AutoStart.Trigger.WAKE, "", bootCount = 4, lastBoot = 3))
        assertFalse(AutoStart.due(AutoStart.Trigger.LAUNCH, "com.maps", bootCount = null, lastBoot = 3))
    }

    // Blank voice choice is the system assistant; anything else is that app.
    @Test
    fun voiceTarget() {
        assertEquals(VoiceKey.Target.Assistant, VoiceKey.target(""))
        assertEquals(VoiceKey.Target.App("com.spotify.music"), VoiceKey.target("com.spotify.music"))
    }
}
