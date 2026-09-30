package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the car settings frames to can-integration/docs/TOYOTA_CUSTOMIZATION.md, byte for byte.
 *
 * Set: `5A A5 03 6A group key value CK`, CK = (sum after `5A A5`) - 1, behind `0D 08`.
 * Report: cmd 0x62, stock `bArr[n]` = payload `p[n - 2]`, field = `(p >> shift) & mask`.
 */
class CarSettingsTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun hex(data: ByteArray) = data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    /** The doc's per-setting table: command bytes after `6A`. */
    @Test
    fun `each setting carries the doc's group and key`() {
        val table = mapOf(
            CarSetting.AUTO_LOCK_SPEED to (0x01 to 0x01),
            CarSetting.SMART_DOOR_UNLOCK to (0x01 to 0x02),
            CarSetting.AUTO_UNLOCK_DRIVER_DOOR to (0x01 to 0x03),
            CarSetting.AUTO_UNLOCK_INTO_P to (0x01 to 0x04),
            CarSetting.AUTO_LOCK_OUT_OF_P to (0x01 to 0x05),
            CarSetting.DAYTIME_LIGHTS to (0x01 to 0x0B),
            CarSetting.LOCK_FLASH to (0x02 to 0x01),
            CarSetting.REMOTE_UNLOCK_TWO_PRESS to (0x02 to 0x04),
            CarSetting.BUZZER_VOLUME to (0x02 to 0x05),
            CarSetting.LIGHT_SENSOR to (0x03 to 0x01),
            CarSetting.INTERIOR_LIGHT_OFF to (0x03 to 0x02),
            CarSetting.HEADLIGHT_OFF to (0x03 to 0x03),
            CarSetting.CLIMATE_AUTO_LINK to (0x01 to 0x06),
            CarSetting.RECIRC_AUTO_LINK to (0x01 to 0x07),
            CarSetting.RADAR_DISPLAY to (0x01 to 0x08),
            CarSetting.RADAR_VOLUME to (0x01 to 0x09),
            CarSetting.LEFT_SEAT_AUTO_TEMP to (0x01 to 0x0C),
            CarSetting.RIGHT_SEAT_AUTO_TEMP to (0x01 to 0x0D),
            CarSetting.SMOKE_SENSOR to (0x01 to 0x0E),
            CarSetting.STEERING_EXIT_MOVE to (0x01 to 0x0F),
            CarSetting.SEAT_EXIT_MOVE to (0x01 to 0x10),
            CarSetting.ACC_CUSTOM to (0x01 to 0x11),
            CarSetting.RECOMMENDATIONS to (0x01 to 0x12),
            CarSetting.DRIVE_SIDE to (0x01 to 0x13),
            CarSetting.FUEL_UNIT to (0x01 to 0x14),
            CarSetting.TEMP_UNIT to (0x01 to 0x15),
            CarSetting.SMART_LOCK to (0x02 to 0x02),
            CarSetting.KEY_TWICE_UNLOCK to (0x02 to 0x03),
        )
        val special = setOf(CarSetting.FRONT_RADAR_RANGE, CarSetting.REAR_RADAR_RANGE, CarSetting.LANGUAGE)

        assertEquals(table.keys + special, CarSetting.entries.toSet())
        table.forEach { (setting, groupKey) ->
            val (group, key) = groupKey
            val payload = CarSettings.setPayload(setting, setting.values.first)
            assertArrayEquals(setting.name, intArrayOf(0x03, 0x6A, group, key, setting.values.first), payload)
        }
    }

    /** The doc's worked example, `03 6A 02 05 03`: CK = 0x77 - 1. */
    @Test
    fun `buzzer volume 3 frames as the doc shows`() {
        val framed = McuCommand.framed(CarSettings.setPayload(CarSetting.BUZZER_VOLUME, 3))

        assertEquals("0D 08 5A A5 03 6A 02 05 03 76", hex(framed))
    }

    @Test
    fun `the owner path wraps the same frame for the MCU`() {
        val expected = McuSerial.encode(0x0D, bytes(0x08, 0x5A, 0xA5, 0x03, 0x6A, 0x01, 0x0B, 0x00, 0x78))

        assertArrayEquals(expected, McuOwnerProtocol.carSetting(CarSetting.DAYTIME_LIGHTS, 0))
    }

    @Test
    fun `the state request is the stock 0x62 query`() {
        assertEquals("0D 08 5A A5 03 6A 05 01 62 D4", hex(McuCommand.framed(CarSettings.QUERY)))

        val expected = McuSerial.encode(0x0D, bytes(0x08, 0x5A, 0xA5, 0x03, 0x6A, 0x05, 0x01, 0x62, 0xD4))
        assertArrayEquals(expected, McuOwnerProtocol.carSettingsQuery())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a value outside the doc's set is refused`() {
        CarSettings.setPayload(CarSetting.INTERIOR_LIGHT_OFF, 4)
    }

    /**
     * Stock sends a fixed value for the radar ranges, whatever is picked: key 0x0A value 1 for
     * the front, 2 for the rear (`HiworldToyotaSetConfig.java:46-47`, `CarSetAdapter.java:515-517`).
     */
    @Test
    fun `the radar ranges send stock's fixed toggle value`() {
        assertArrayEquals(intArrayOf(0x03, 0x6A, 0x01, 0x0A, 0x01), CarSettings.setPayload(CarSetting.FRONT_RADAR_RANGE, 2))
        assertArrayEquals(intArrayOf(0x03, 0x6A, 0x01, 0x0A, 0x02), CarSettings.setPayload(CarSetting.REAR_RADAR_RANGE, 1))
    }

    /** Language is its own command, `getSendToCanByteArray4`: `02 9A 01 value` (`:27`, `:67`). */
    @Test
    fun `language is the stock 02 9A frame`() {
        assertArrayEquals(intArrayOf(0x02, 0x9A, 0x01, 0x05), CarSettings.setPayload(CarSetting.LANGUAGE, 5))
        assertEquals("0D 08 5A A5 02 9A 01 05 A1", hex(McuCommand.framed(CarSettings.setPayload(CarSetting.LANGUAGE, 5))))
    }

    /** The 0x62 report carries no fuel unit, temperature unit or language. */
    @Test
    fun `units and language are never read from the report`() {
        val state = CarSettings.decode(ByteArray(8) { 0xFF.toByte() })

        assertNull(state[CarSetting.FUEL_UNIT])
        assertNull(state[CarSetting.TEMP_UNIT])
        assertNull(state[CarSetting.LANGUAGE])
    }

    /**
     * The rest of `OnHandleCarSetInfoCmd` (`HiworldCanParseToyota.java:644-674`):
     *   bArr[2] = p[0] 0x9A: b7 radar display 1, b4..6 radar volume 1, b2..3 front range 2, b0..1 rear range 2
     *   bArr[3] = p[1] 0x02: b1 climate link 1, b0 recirc link 0
     *   bArr[4] = p[2] 0x2C: b5 smart lock 1, b4 key twice 0, b1..3 smoke 6
     *   bArr[6] = p[4] 0x8B: b5..7 left seat 4, b2..4 right seat 2, b0..1 steering 3
     *   bArr[8] = p[6] 0xAC: b6..7 seat exit 2, b4..5 recommendations 2, b3 ACC 1, b2 drive side 1
     */
    @Test
    fun `the 0x62 report decodes the stock-only fields`() {
        val p = bytes(0x9A, 0x02, 0x2C, 0x00, 0x8B, 0x00, 0xAC)

        val state = (HiworldCanDecoder.decodePayload(0x62, p) as CanSignal.CarSettings).state

        assertEquals(1, state[CarSetting.RADAR_DISPLAY])
        assertEquals(1, state[CarSetting.RADAR_VOLUME])
        assertEquals(2, state[CarSetting.FRONT_RADAR_RANGE])
        assertEquals(2, state[CarSetting.REAR_RADAR_RANGE])
        assertEquals(1, state[CarSetting.CLIMATE_AUTO_LINK])
        assertEquals(0, state[CarSetting.RECIRC_AUTO_LINK])
        assertEquals(1, state[CarSetting.SMART_LOCK])
        assertEquals(0, state[CarSetting.KEY_TWICE_UNLOCK])
        assertEquals(6, state[CarSetting.SMOKE_SENSOR])
        assertEquals(4, state[CarSetting.LEFT_SEAT_AUTO_TEMP])
        assertEquals(2, state[CarSetting.RIGHT_SEAT_AUTO_TEMP])
        assertEquals(3, state[CarSetting.STEERING_EXIT_MOVE])
        assertEquals(2, state[CarSetting.SEAT_EXIT_MOVE])
        assertEquals(2, state[CarSetting.RECOMMENDATIONS])
        assertEquals(1, state[CarSetting.ACC_CUSTOM])
        assertEquals(1, state[CarSetting.DRIVE_SIDE])
    }

    /** Radar volume 0 is outside stock's 1..5, so it reads as unknown, not as a value. */
    @Test
    fun `an out-of-set radar volume is unknown`() {
        assertNull(CarSettings.decode(bytes(0x00))[CarSetting.RADAR_VOLUME])
    }

    /**
     * Report bytes, stock index → payload index:
     *   bArr[3] = p[1]: b6 speed lock, b5 smart unlock, b4 driver door, b3 into P, b2 out of P
     *   bArr[4] = p[2]: b7 lock flash, b6 remote 2-press
     *   bArr[5] = p[3]: b7 DRL, b5..6 headlight off, b3..4 interior off, b0..2 light sensor
     *   bArr[7] = p[5]: b0..2 buzzer volume
     */
    @Test
    fun `the 0x62 report decodes every field from its bits`() {
        val p = bytes(0x00, 0x54, 0x80, 0xDC, 0x00, 0xFD)

        val signal = HiworldCanDecoder.decodePayload(0x62, p) as CanSignal.CarSettings
        val state = signal.state

        assertEquals(1, state[CarSetting.AUTO_LOCK_SPEED])
        assertEquals(0, state[CarSetting.SMART_DOOR_UNLOCK])
        assertEquals(1, state[CarSetting.AUTO_UNLOCK_DRIVER_DOOR])
        assertEquals(0, state[CarSetting.AUTO_UNLOCK_INTO_P])
        assertEquals(1, state[CarSetting.AUTO_LOCK_OUT_OF_P])
        assertEquals(1, state[CarSetting.LOCK_FLASH])
        assertEquals(0, state[CarSetting.REMOTE_UNLOCK_TWO_PRESS])
        assertEquals(1, state[CarSetting.DAYTIME_LIGHTS])
        assertEquals(2, state[CarSetting.HEADLIGHT_OFF])
        assertEquals(3, state[CarSetting.INTERIOR_LIGHT_OFF])
        assertEquals(4, state[CarSetting.LIGHT_SENSOR])
        assertEquals(5, state[CarSetting.BUZZER_VOLUME])
    }

    @Test
    fun `a field past the end of a short report is unknown`() {
        val state = CarSettings.decode(bytes(0x00, 0x40))

        assertEquals(1, state[CarSetting.AUTO_LOCK_SPEED])
        assertNull(state[CarSetting.DAYTIME_LIGHTS])
        assertNull(state[CarSetting.BUZZER_VOLUME])
    }

    /** Light sensor has 3 report bits but 5 steps: 5..7 is not a value the page can show. */
    @Test
    fun `a report value outside the doc's set is unknown`() {
        val state = CarSettings.decode(bytes(0x00, 0x00, 0x00, 0x07, 0x00, 0x00))

        assertNull(state[CarSetting.LIGHT_SENSOR])
        assertEquals(0, state[CarSetting.DAYTIME_LIGHTS])
    }
}
