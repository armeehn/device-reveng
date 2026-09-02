package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The power screen's domains are transcribed from the vendor settings app, and the guard around
 * them is what stops a stored value we did not expect from being coerced and written back.
 */
class PowerOptionsTest {

    @Test
    fun sleepTimeIsTheVendorEnum() {
        // 1/2/3, default 2 (EventService.java:9361-9371, :6540). Not minutes.
        assertEquals(listOf(1, 2, 3), PowerOptions.SLEEP_TIME.map { it.first })
        assertEquals(2, PowerOptions.SLEEP_TIME_DEFAULT)
    }

    @Test
    fun screenTimeoutsAreTheVendorSeconds() {
        // ItemTextRightCheckBoxView.java:644-692: never / 1 / 5 / 10 / 30 min.
        assertEquals(listOf(0, 60, 300, 600, 1800), PowerOptions.SCREEN_TIMEOUT.map { it.first })
    }

    @Test
    fun accOnDelayIsZeroToSevenSeconds() {
        assertEquals(0..7, PowerOptions.ACC_ON_DELAY_SECONDS)
    }

    @Test
    fun storedOptionRoundTrips() {
        PowerOptions.SCREEN_TIMEOUT.forEach { (raw, _) ->
            assertEquals(raw, PowerOptions.rawOrNull(raw.toString(), PowerOptions.SCREEN_TIMEOUT))
        }
        assertEquals(3, PowerOptions.rawOrNull(" 3 ", PowerOptions.SLEEP_TIME))
    }

    @Test
    fun storedValueOutsideTheDomainIsNull() {
        // "10" was the old launcher's default for a minutes slider; it must not be coerced.
        assertNull(PowerOptions.rawOrNull("10", PowerOptions.SLEEP_TIME))
        assertNull(PowerOptions.rawOrNull("120", PowerOptions.SCREEN_TIMEOUT))
        assertNull(PowerOptions.rawOrNull("8", PowerOptions.ACC_ON_DELAY_SECONDS))
        assertNull(PowerOptions.rawOrNull("-1", PowerOptions.ACC_ON_DELAY_SECONDS))
        assertNull(PowerOptions.rawOrNull("abc", PowerOptions.SLEEP_TIME))
        assertNull(PowerOptions.rawOrNull("", PowerOptions.ACC_DELAY_SECONDS))
    }

    @Test
    fun accDelayFormatsAsMinutesSeconds() {
        // The gateway sends {0x49, 0x17, v / 60, v % 60}.
        assertEquals("0:00", PowerOptions.minutesSeconds(0))
        assertEquals("0:05", PowerOptions.minutesSeconds(5))
        assertEquals("1:30", PowerOptions.minutesSeconds(90))
        assertEquals("10:00", PowerOptions.minutesSeconds(600))
    }
}
