package com.reveng.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Presets survive a reboot as "band:freq" strings in a DataStore string set, so [RadioPreset]'s
 * codec is the only thing standing between a saved station and a lost one. The set is also
 * user-visible storage that a future format change, a partial write, or a hand-edited backup can
 * corrupt — [RadioPreset.decode] must return null for anything it does not fully understand,
 * because the store drops nulls silently and a thrown exception would take the whole strip down.
 */
class RadioPresetTest {

    @Test
    fun encodeUsesColonToken() {
        assertEquals("0:8750", RadioPreset(band = 0, freq = 8750).encode())
    }

    @Test
    fun roundTripPreservesBoth() {
        val presets = listOf(
            RadioPreset(band = 0, freq = 8750),
            RadioPreset(band = 3, freq = 1710),
            RadioPreset(band = 0, freq = 0),
        )

        presets.forEach { preset ->
            assertEquals(preset, RadioPreset.decode(preset.encode()))
        }
    }

    @Test
    fun bandAndFreqDoNotSwap() {
        // Asymmetric values: a transposed decode would still round-trip a symmetric pair.
        val decoded = RadioPreset.decode("3:1710")

        assertEquals(3, decoded?.band)
        assertEquals(1710, decoded?.freq)
    }

    @Test
    fun wrongFieldCountIsNull() {
        assertNull(RadioPreset.decode(""))
        assertNull(RadioPreset.decode("8750"))
        assertNull(RadioPreset.decode("0:8750:1"))
        assertNull(RadioPreset.decode(":"))
    }

    @Test
    fun nonNumericFieldIsNull() {
        assertNull(RadioPreset.decode("FM:8750"))
        assertNull(RadioPreset.decode("0:eight"))
        assertNull(RadioPreset.decode("0:"))
        // Whitespace is not trimmed — a padded token is corruption, not a valid preset.
        assertNull(RadioPreset.decode(" 0:8750"))
        assertNull(RadioPreset.decode("0:8750 "))
    }

    @Test
    fun overflowingFreqIsNull() {
        // Larger than Int.MAX_VALUE. toIntOrNull returns null rather than throwing, and the
        // whole token must be rejected instead of silently wrapping to a negative frequency.
        assertNull(RadioPreset.decode("0:99999999999"))
    }

    @Test
    fun negativeValuesStillDecode() {
        // decode() is a token parser, not a validator: it reports what the string says. Plausibility
        // belongs downstream — formatFreqLabel already renders a non-positive freq as "--".
        assertEquals(RadioPreset(band = -1, freq = -5), RadioPreset.decode("-1:-5"))
    }
}
