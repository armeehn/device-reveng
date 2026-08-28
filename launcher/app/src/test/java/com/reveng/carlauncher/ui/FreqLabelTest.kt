package com.reveng.carlauncher.ui

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [formatFreqLabel] is shared by the home RadioCard and the radio settings screen precisely so the
 * same raw `getRadioFreq()` value cannot render two different ways on two screens — the bug that
 * put it in one place. These tests hold that line: they assert the label for every raw encoding
 * the heuristic claims to cover, and assert that the encodings of one station all agree.
 *
 * The units themselves are GUESSED (CAR_API §3.2). When a live capture settles them, the wrong
 * branches here become dead and can go; the agreement and non-positive cases stay.
 */
class FreqLabelTest {

    /**
     * `"%.1f".format()` follows the default locale, so a de_DE runner would print "87,5 MHz". That
     * is correct behaviour for the head unit and wrong for a fixed assertion — pin the locale so
     * the test measures the heuristic and not the runner's environment.
     */
    private lateinit var original: Locale

    /** Band ordinals either side of CarService.isAmBand's split. */
    private val fm = 0
    private val am = 3

    @Before
    fun pinLocale() {
        original = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun nonPositiveFreqIsUnknown() {
        // A tuner that has not reported yet must not render as "0.0 MHz".
        assertEquals("--", formatFreqLabel(fm, 0))
        assertEquals("--", formatFreqLabel(fm, -1))
        assertEquals("--", formatFreqLabel(am, 0))
    }

    @Test
    fun fmInTenKhzUnits() {
        assertEquals("87.5 MHz", formatFreqLabel(fm, 8750))
        assertEquals("99.9 MHz", formatFreqLabel(fm, 9990))
        assertEquals("108.0 MHz", formatFreqLabel(fm, 10800))
    }

    @Test
    fun fmInKhzUnits() {
        assertEquals("87.5 MHz", formatFreqLabel(fm, 87500))
        assertEquals("95.0 MHz", formatFreqLabel(fm, 95000))
        assertEquals("108.0 MHz", formatFreqLabel(fm, 108000))
    }

    @Test
    fun fmInHundredKhzUnits() {
        // The small-value fallback: 875 is 87.5 MHz in 100 kHz steps.
        assertEquals("87.5 MHz", formatFreqLabel(fm, 875))
        assertEquals("108.0 MHz", formatFreqLabel(fm, 1080))
    }

    @Test
    fun everyFmEncodingAgrees() {
        // The whole point of the shared helper. One station, three plausible raw encodings, one
        // label — if a screen ever re-implements this, this is the assertion that catches it.
        val labels = listOf(875, 8750, 87500).map { formatFreqLabel(fm, it) }.distinct()

        assertEquals(listOf("87.5 MHz"), labels)
    }

    @Test
    fun amInKhzUnits() {
        assertEquals("530 kHz", formatFreqLabel(am, 530))
        assertEquals("1710 kHz", formatFreqLabel(am, 1710))
    }

    @Test
    fun amInHzUnits() {
        // Above 30000 an AM raw is read as Hz and divided down; 1710 kHz must not print as
        // "1710000 kHz".
        assertEquals("1710 kHz", formatFreqLabel(am, 1710000))
        assertEquals("530 kHz", formatFreqLabel(am, 530000))
    }

    @Test
    fun bandChoosesTheUnit() {
        // Same raw value, opposite bands: the AM/FM split must reach the label, not just the
        // "AM"/"FM" caption next to it.
        assertEquals("8750 kHz", formatFreqLabel(am, 8750))
        assertEquals("87.5 MHz", formatFreqLabel(fm, 8750))
    }
}
