package com.ripostelabs.carlauncher.ui

import android.os.Environment
import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RAV4-198: the SIM and USB chips follow the same rule as the rest: no source, no chip. */
class StatusExtrasTest {

    @Test
    fun `a ready SIM shows its bars`() {
        assertEquals(3, simBars(TelephonyManager.SIM_STATE_READY, level = 3))
    }

    @Test
    fun `a ready SIM with no reading shows zero bars`() {
        assertEquals(0, simBars(TelephonyManager.SIM_STATE_READY, level = null))
    }

    @Test
    fun `no SIM or a locked SIM shows no chip`() {
        assertNull(simBars(TelephonyManager.SIM_STATE_ABSENT, level = 4))
        assertNull(simBars(TelephonyManager.SIM_STATE_UNKNOWN, level = 4))
        assertNull(simBars(TelephonyManager.SIM_STATE_PIN_REQUIRED, level = 4))
    }

    @Test
    fun `bars are clamped to the four the icon has`() {
        assertEquals(4, simBars(TelephonyManager.SIM_STATE_READY, level = 9))
        assertEquals(0, simBars(TelephonyManager.SIM_STATE_READY, level = -1))
    }

    @Test
    fun `a mounted removable volume is a stick`() {
        val volumes = listOf(
            StorageVolumeInfo(removable = false, state = Environment.MEDIA_MOUNTED),
            StorageVolumeInfo(removable = true, state = Environment.MEDIA_MOUNTED),
        )
        assertTrue(usbMounted(volumes))
    }

    @Test
    fun `internal storage alone or an ejected stick is no stick`() {
        assertFalse(usbMounted(listOf(StorageVolumeInfo(removable = false, state = Environment.MEDIA_MOUNTED))))
        assertFalse(usbMounted(listOf(StorageVolumeInfo(removable = true, state = Environment.MEDIA_UNMOUNTED))))
        assertFalse(usbMounted(emptyList()))
    }
}
