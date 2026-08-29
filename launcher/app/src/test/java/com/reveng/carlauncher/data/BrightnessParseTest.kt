package com.reveng.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The status strip and the Quick Controls slider both hide themselves when the backlight level
 * cannot be read, and that decision rests entirely on [BrightnessController.parsePercent]
 * returning null. `screen_brightness` reads back as the literal string "null" when the key is
 * unset (that is what `settings get system screen_brightness` prints), and a provider that is
 * missing or hostile can return anything at all — every one of those must come back as null
 * rather than a number, because a fabricated "50 %" is exactly the lie the chip is meant to
 * avoid.
 */
class BrightnessParseTest {

    @Test
    fun unreadableValuesYieldNull() {
        val unreadable = listOf(null, "", "   ", "null", "abc", "12x", "50%", "1.5")

        unreadable.forEach { raw ->
            assertNull("expected null for <$raw>", BrightnessController.parsePercent(raw))
        }
    }

    @Test
    fun outOfRangeValuesYieldNull() {
        // The framework band is 0-255; anything outside it is not a backlight level.
        listOf("-1", "256", "99999").forEach { raw ->
            assertNull("expected null for <$raw>", BrightnessController.parsePercent(raw))
        }
    }

    @Test
    fun bandEdgesMapToTheFullSlider() {
        // The panel saturates well below 255, so everything above the usable band reads as 100 %.
        assertEquals(0, BrightnessController.parsePercent("0"))
        assertEquals(100, BrightnessController.parsePercent("255"))
    }

    @Test
    fun surroundingWhitespaceIsTolerated() {
        // A shell read arrives with its trailing newline still attached.
        assertEquals(BrightnessController.parsePercent("20"), BrightnessController.parsePercent(" 20\n"))
    }
}
