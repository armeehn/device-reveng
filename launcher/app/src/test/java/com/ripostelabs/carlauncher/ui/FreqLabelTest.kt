package com.ripostelabs.carlauncher.ui

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [formatFreqLabel] is shared by the home RadioCard and the radio settings screen precisely so the
 * same raw `getRadioFreq()` value cannot render two different ways on two screens — the bug that
 * put it in one place. The units are the vendor's (CAR_API §3.2): FM in 10 kHz units, AM in
 * kHz — the vendor radio formats the same raw value as "%d.%02d MHZ" / "%d KHZ".
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
    fun fmIsNotRescaledByMagnitude() {
        // The old magnitude heuristic read 875 as 87.5 MHz; the tuner never sends that. A raw
        // value is 10 kHz units, full stop: 875 is 8.75 MHz and prints as such.
        assertEquals("8.8 MHz", formatFreqLabel(fm, 875))
        assertEquals("875.0 MHz", formatFreqLabel(fm, 87500))
    }

    @Test
    fun amInKhzUnits() {
        assertEquals("530 kHz", formatFreqLabel(am, 530))
        assertEquals("1710 kHz", formatFreqLabel(am, 1710))
    }

    @Test
    fun bandChoosesTheUnit() {
        // Same raw value, opposite bands: the AM/FM split must reach the label, not just the
        // "AM"/"FM" caption next to it.
        assertEquals("8750 kHz", formatFreqLabel(am, 8750))
        assertEquals("87.5 MHz", formatFreqLabel(fm, 8750))
    }
}
