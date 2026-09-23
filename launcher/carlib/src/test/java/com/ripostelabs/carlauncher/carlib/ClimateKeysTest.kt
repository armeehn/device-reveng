package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The HVAC key frames the owner writes, byte for byte what canbus2 sends for the same button.
 *
 * Vendor path: `CarAirClickWithVoice` key → `HiworldCanParseToyota.verticalScreen`
 * (`HiworldCanParseToyota.java:1784-1941`) builds `02 3D code 01`, `sendKeyNeedActionUP`
 * (`CanDataParseBase.java:2186-2230`) sends it, sleeps 100 ms, zeroes the action byte and sends
 * again. `SendUtil.SendCmdLstToCanbus5AA5Header` (`SendUtil.java:60-85`) adds `5A A5` and the
 * sum-minus-one checksum, `sendDataToCanbus` (`:40-58`) the `0D 08` route.
 */
class ClimateKeysTest {

    private fun hex(data: ByteArray) = data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun outer(vararg body: Int): ByteArray =
        McuSerial.encode(0x0D, body.map { it.toByte() }.toByteArray())

    /** The `verticalScreen` table, key value in, box code out. */
    @Test
    fun `every button maps to the vendor's box code`() {
        val vendor = mapOf(
            ClimateButton.POWER to 0x01,
            ClimateButton.FAN_UP to 0x0B,
            ClimateButton.FAN_DOWN to 0x0C,
            ClimateButton.LEFT_TEMP_UP to 0x0D,
            ClimateButton.LEFT_TEMP_DOWN to 0x0E,
            ClimateButton.RIGHT_TEMP_UP to 0x0F,
            ClimateButton.RIGHT_TEMP_DOWN to 0x10,
            ClimateButton.AUTO to 0x04,
            ClimateButton.AC to 0x02,
            ClimateButton.AC_MAX to null,
            ClimateButton.DUAL to 0x29,
            ClimateButton.RECIRCULATE to 0x07,
            ClimateButton.FRONT_DEFROST to 0x05,
            ClimateButton.REAR_DEFROST to 0x06,
            ClimateButton.LEFT_SEAT_COOL to 0x17,
            ClimateButton.LEFT_SEAT_HEAT to 0x11,
            ClimateButton.RIGHT_SEAT_COOL to 0x18,
            ClimateButton.RIGHT_SEAT_HEAT to 0x12,
            ClimateButton.MODE to 0x15,
            ClimateButton.ECO to 0x23,
            ClimateButton.REAR_LOCK to 0x22,
            ClimateButton.SYNC to 0x03,
        )

        assertEquals(ClimateButton.entries.toSet(), vendor.keys)
        ClimateButton.entries.forEach { button ->
            assertEquals(button.name, vendor[button], ClimateKeys.boxCode(button))
        }
    }

    /** `02 3D 02 01` sums to 0x42, so the inner checksum is 0x41; the release frame's is 0x40. */
    @Test
    fun `AC is a press frame then a release frame`() {
        val press = ClimateKeys.press(ClimateButton.AC)!!

        assertArrayEquals(outer(0x08, 0x5A, 0xA5, 0x02, 0x3D, 0x02, 0x01, 0x41), press.down)
        assertArrayEquals(outer(0x08, 0x5A, 0xA5, 0x02, 0x3D, 0x02, 0x00, 0x40), press.up)
    }

    /** The route byte pair is the outer opcode `0D` and the payload's first byte `08`. */
    @Test
    fun `the frame is routed to the car through the 0D 08 prefix`() {
        val down = ClimateKeys.press(ClimateButton.FAN_UP)!!.down

        assertEquals(0x0D, down[3].toInt() and 0xFF)
        assertEquals("08 5A A5 02 3D 0B 01 4A", hex(down.copyOfRange(4, down.size - 2)))
    }

    /** `verticalScreen` leaves code 0 for AC MAX and sends nothing (`:1936`). */
    @Test
    fun `a button the Toyota table lacks sends nothing`() {
        assertNull(ClimateKeys.press(ClimateButton.AC_MAX))
    }

    @Test
    fun `the release gap is the vendor's 100 ms`() {
        assertEquals(100L, ClimateKeys.RELEASE_GAP_MS)
    }
}
