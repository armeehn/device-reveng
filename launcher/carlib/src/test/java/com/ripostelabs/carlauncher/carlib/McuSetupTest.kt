package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Test

/** The rows are the vendor's SysVar names and encodings, so an export reads the same as stock. */
class McuSetupTest {

    @Test
    fun defaultRowsAreTheVendorDefaults() {
        val rows = McuSetup().toRows()

        assertEquals("7", rows["Set_BalanaceLR"])
        assertEquals("7", rows["Set_BalanaceFA"])
        assertEquals("7", rows["Set_Bass_Val"])
        assertEquals("0", rows["Set_Eq_Mode"])
        assertEquals("0", rows["Set_Loudness"])
        assertEquals("10", rows["Set_Subwoofer"])
        assertEquals("0", rows["Set_TouchBeep"])
        assertEquals("2", rows["SYS_SLEEP_TIME"])
        assertEquals("28", rows["Set_NavSoundVolume"])
        assertEquals("30", rows["Set_CarAmplifier_HostDefaultVolume"])
        assertEquals("28", rows["Sys_Radio_Volume_Gain"])
        assertEquals("28", rows["Sys_Other_Volume_Gain"])
        assertEquals("0", rows["Set_Dsp_Loud_On_Off_Key"])
    }

    @Test
    fun rowsRoundTrip() {
        val setup = McuSetup(
            balance = 3,
            fader = 11,
            tone = McuSetup.Tone(bass = 9, mid = 5, treble = 12, bassFreq = 1, midFreq = 2, trebleFreq = 3),
            eqMode = 4,
            loudness = true,
            subwoofer = 15,
            keyBeep = McuSetup.Beep.ON,
            sleepTime = 3,
            navVolume = 20,
            hostDefaultVolume = 25,
            gains = McuSetup.SourceGains(radio = 1, music = 2, movie = 3, btCall = 4, btMusic = 5, tv = 6, dvd = 7, aux = 8, usb = 9, other = 10),
            dspLoud = true,
        )

        assertEquals(setup, McuSetup.fromRows(setup.toRows()))
    }

    /** A missing, blank or garbled row keeps its default, as `getRecordInteger(key, default)` did. */
    @Test
    fun badRowsKeepDefaults() {
        val rows = mapOf(
            "Set_BalanaceLR" to "x",
            "Set_Eq_Mode" to "",
            "Set_Loudness" to "yes",
            "Set_TouchBeep" to "1",
        )

        assertEquals(McuSetup(keyBeep = McuSetup.Beep.ON), McuSetup.fromRows(rows))
    }
}
