package com.ripostelabs.carlauncher.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** RAV4-193: the software version page's value rules. */
class SoftwareInfoTest {

    @Test
    fun firstKnownVersionWins() {
        // The owner's own frame beats the AIDL, which beats the SysVar mirror.
        assertEquals("RL78-2.1", SoftwareInfo.firstKnown("RL78-2.1", "V1", "old"))
        assertEquals("V1", SoftwareInfo.firstKnown(null, "V1", "old"))
        assertEquals("old", SoftwareInfo.firstKnown("", " ", "old"))
        assertEquals(SoftwareInfo.UNKNOWN, SoftwareInfo.firstKnown(null, ""))
    }

    @Test
    fun sizesReadInBinaryUnits() {
        assertEquals("512 MB", SoftwareInfo.size(512L * 1024 * 1024))
        assertEquals("2.0 GB", SoftwareInfo.size(2L * 1024 * 1024 * 1024))
        assertEquals("29.1 GB", SoftwareInfo.size(31_250_000_000L))
    }

    @Test
    fun capacityShowsFreeOfTotal() {
        assertEquals(
            "1.5 GB free of 4.0 GB",
            SoftwareInfo.capacity(total = 4L * 1024 * 1024 * 1024, free = 1536L * 1024 * 1024),
        )
    }
}
