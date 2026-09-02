package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sendRadioKey values the MCU actually understands, from the vendor radio app's key
 * handlers (decompiled com.szchoiceway.radio, MainActivity.OnKeyEvent). The launcher shipped
 * guesses for a year — 0 / 1 / 2 — which the MCU read as nothing / preset 1 / preset 2, so
 * "seek" recalled a preset and "band" did nothing. This pins the table to the decompile.
 */
class RadioOpcodeTest {

    @Test
    fun transportKeysMatchTheVendorTable() {
        assertEquals(16, CarService.RADIO_KEY_SEEK_DOWN)
        assertEquals(17, CarService.RADIO_KEY_SEEK_UP)
        assertEquals(14, CarService.RADIO_KEY_STEP_DOWN)
        assertEquals(15, CarService.RADIO_KEY_STEP_UP)
        assertEquals(13, CarService.RADIO_KEY_SCAN)
        assertEquals(18, CarService.RADIO_KEY_AUTO_STORE)
    }

    @Test
    fun bandKeysAreDirectNotACycle() {
        assertEquals(30, CarService.RADIO_KEY_BAND_FM)
        assertEquals(31, CarService.RADIO_KEY_BAND_AM)
        assertEquals(24, CarService.RADIO_KEY_BAND_CYCLE)
    }

    @Test
    fun rdsTogglesAreKeys() {
        assertEquals(21, CarService.RADIO_KEY_AF)
        assertEquals(22, CarService.RADIO_KEY_PTY_SEEK)
        assertEquals(23, CarService.RADIO_KEY_TA)
    }

    @Test
    fun tunerSourceIsSrcRadio() {
        assertEquals(1, CarService.SRC_RADIO)
    }
}
