package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spot checks against `EventUtils.eSrcMode` (EventUtils.java:2005-2069). */
class SrcModeNamesTest {

    @Test
    fun knownModesResolve() {
        assertEquals("NONE", SrcModeNames.name(0))
        assertEquals("RADIO", SrcModeNames.name(1))
        assertEquals("MUSIC", SrcModeNames.name(11))
        assertEquals("CARPLAY", SrcModeNames.name(32))
        assertEquals("AUX", SrcModeNames.name(40))
        assertEquals("HOME", SrcModeNames.name(43))
        assertEquals("NULL", SrcModeNames.name(99))
        assertEquals("CARAIR", SrcModeNames.name(150))
        assertEquals("MORESETTING", SrcModeNames.name(159))
    }

    @Test
    fun gapsInTheEnumAreUnknown() {
        // 20..29 and 33 are unassigned in the vendor enum; valueOf() maps them to SRC_NONE, but a
        // display row should say it saw a number it cannot name rather than pretend.
        assertEquals(SrcModeNames.UNKNOWN, SrcModeNames.name(20))
        assertEquals(SrcModeNames.UNKNOWN, SrcModeNames.name(33))
        assertEquals(SrcModeNames.UNKNOWN, SrcModeNames.name(-1))
    }

    @Test
    fun labelsCarryTheRawValue() {
        assertEquals("RADIO (1)", SrcModeNames.label(1))
        assertEquals("unknown (77)", SrcModeNames.label(77))
        assertEquals(SrcModeNames.UNBOUND, SrcModeNames.label(null))
    }

    @Test
    fun audioSourcesEndAtAux() {
        assertTrue(SrcModeNames.isAudioSource(1))
        assertTrue(SrcModeNames.isAudioSource(40))
        assertFalse(SrcModeNames.isAudioSource(41))
    }
}
