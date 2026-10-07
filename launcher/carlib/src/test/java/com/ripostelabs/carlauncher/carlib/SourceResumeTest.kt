package com.ripostelabs.carlauncher.carlib

import com.ripostelabs.carlauncher.carlib.McuOwnerProtocol.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceResumeTest {

    // RAV4-170: only a source a person picked is worth resuming; the owner's own modes
    // (SRC_NULL, power, idle, reverse) must never overwrite it.
    @Test
    fun keepsOnlyPlayableSources() {
        val kept = Mode.entries.filter(SourceResume::keeps).toSet()

        assertEquals(setOf(Mode.RADIO, Mode.BT_MUSIC, Mode.MOVIE, Mode.MUSIC, Mode.CARPLAY), kept)
        assertFalse(SourceResume.keeps(Mode.NULL))
        assertFalse(SourceResume.keeps(Mode.BACKCAR))
    }

    // CarPlay is a pick too: storing it stops an older Music choice from reopening after a
    // CarPlay drive. The launcher maps it to no app, since the phone reconnects by itself.
    @Test
    fun carPlayIsStoredSoItReplacesOlderSource() {
        assertEquals(Mode.CARPLAY, SourceResume.parse(Mode.CARPLAY.code))
    }

    @Test
    fun parsesAStoredCode() {
        assertEquals(Mode.RADIO, SourceResume.parse(Mode.RADIO.code))
        assertEquals(null, SourceResume.parse(-1))
        assertEquals(null, SourceResume.parse(Mode.POWER_OFF.code))
        assertTrue(SourceResume.parse(Mode.MUSIC.code) == Mode.MUSIC)
    }
}
