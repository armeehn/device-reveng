package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CarService.isAmBand] is a one-line GUESSED split of the `getRadioBand()` ordinal, but two
 * screens and the frequency formatter all branch on it, so an accidental `>` / `>=` edit would
 * relabel every FM3 station as AM without any other symptom.
 */
class RadioBandTest {

    @Test
    fun fmOrdinalsAreNotAm() {
        assertFalse(CarService.isAmBand(0))
        assertFalse(CarService.isAmBand(1))
        assertFalse(CarService.isAmBand(2))
    }

    @Test
    fun amStartsAtThree() {
        assertTrue(CarService.isAmBand(3))
        assertTrue(CarService.isAmBand(4))
        assertTrue(CarService.isAmBand(5))
    }
}
