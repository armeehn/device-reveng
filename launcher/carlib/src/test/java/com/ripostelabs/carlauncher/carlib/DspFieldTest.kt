package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The DSP app's listening position (`model/bean/SoundFieldModel_Two.java`): time alignment
 * `4F 12 lf rf lr rr c` (sendDelay, :85-114) and channel gain `4F 13 00 lf rf lr rr c`
 * (sendChannelGains, :118-147). Frames summed by hand: ck = 0xFF - (len + body).
 */
class DspFieldTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** The defaults are the boot replay's `4F 12` and `4F 13` blocks (2026-09-18 strace). */
    @Test
    fun defaultsAreTheStraceBlocks() {
        val field = DspSound.Field()

        assertArrayEquals(
            bytes(0x0D, 0x0A, 0x08, 0x4F, 0x12, 0x00, 0x00, 0x00, 0x00, 0x00, 0x96, 0x00),
            McuSetupProtocol.dspDelay(field),
        )
        assertArrayEquals(
            bytes(0x0D, 0x0A, 0x09, 0x4F, 0x13, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x94, 0x00),
            McuSetupProtocol.dspChannelGain(field),
        )
    }

    /**
     * Stock's driver preset (EasyFieldFragment_two.java, `jiashi`): rear-right 136 cm, the
     * rest 0. 136 * 100 / 272 = 50 = 0x32. 08+4F+12+32 = 0x9B, ck 0x64.
     */
    @Test
    fun driverSeatDelaysRearRight() {
        val field = DspSound.Field().withSeat(DspSound.Seat.DRIVER)

        assertArrayEquals(
            bytes(0x0D, 0x0A, 0x08, 0x4F, 0x12, 0x00, 0x00, 0x00, 0x32, 0x00, 0x64, 0x00),
            McuSetupProtocol.dspDelay(field),
        )
    }

    /** The wire is distance * 100 / 272, truncated: 272 cm is 100, 1 cm is 0 (:108-112). */
    @Test
    fun delayScalesAndClamps() {
        val field = DspSound.Field(delays = listOf(272, 1, 999, -5, 100))
        val frame = McuSetupProtocol.dspDelay(field)

        assertEquals(listOf(100, 0, 100, 0, 36), (5..9).map { frame[it].toInt() and 0xFF })
    }

    /**
     * Each gain is value - 80 as a signed byte (:141-146): 74 = -6 dB = 0xFA, 95 = +15 = 0x0F,
     * 0 = -80 = 0xB0. 09+4F+13+FA+0F+B0 = 0x224, ck 0xDB.
     */
    @Test
    fun gainIsSignedAroundEighty() {
        val field = DspSound.Field(gains = listOf(74, 80, 95, 0, 80))

        assertArrayEquals(
            bytes(0x0D, 0x0A, 0x09, 0x4F, 0x13, 0x00, 0xFA, 0x00, 0x0F, 0xB0, 0x00, 0xDB, 0x00),
            McuSetupProtocol.dspChannelGain(field),
        )
    }

    /** The gain slider runs 0..95 (layout_field_save_two.xml, `android:max="95"`). */
    @Test
    fun gainClampsToTheSlider() {
        val frame = McuSetupProtocol.dspChannelGain(DspSound.Field(gains = listOf(200, -9, 80, 80, 80)))

        assertEquals(0x0F, frame[6].toInt())
        assertEquals(0xB0, frame[7].toInt() and 0xFF)
    }

    /** The four stock seats (EasyFieldFragment_two.java:246-275); the centre is left alone. */
    @Test
    fun seatsAreTheStockDelaySets() {
        val base = DspSound.Field(delays = listOf(9, 9, 9, 9, 40))

        assertEquals(listOf(0, 0, 136, 0, 40), base.withSeat(DspSound.Seat.PASSENGER).delays)
        assertEquals(listOf(272, 272, 0, 0, 40), base.withSeat(DspSound.Seat.REAR).delays)
        assertEquals(listOf(0, 0, 0, 0, 40), base.withSeat(DspSound.Seat.ALL).delays)
        assertEquals(DspSound.Seat.REAR, base.withSeat(DspSound.Seat.REAR).seat)
    }

    /** A dial move makes the position custom (FieldFragment_Two.java:109, DRIVE_MODE = 5). */
    @Test
    fun speakerEditMakesItCustom() {
        val field = DspSound.Field().withSeat(DspSound.Seat.DRIVER).withDelay(DspSound.Speaker.CENTRE, 34)

        assertEquals(DspSound.Seat.CUSTOM, field.seat)
        assertEquals(34, field.delays[DspSound.Speaker.CENTRE.ordinal])
    }

    /** Boot and wake carry the saved position in the stock slots (DspService :64-83). */
    @Test
    fun bootBlocksCarryTheField() {
        val field = DspSound.Field(gains = listOf(70, 80, 80, 80, 90)).withSeat(DspSound.Seat.DRIVER)
        val blocks = McuOwnerProtocol.vendorInit(McuSetup(dspField = field))

        assertArrayEquals(McuSetupProtocol.dspDelay(field), blocks[4])
        assertArrayEquals(McuSetupProtocol.dspChannelGain(field), blocks[5])
    }

    @Test
    fun rowsKeepTheField() {
        val field = DspSound.Field(gains = listOf(1, 2, 3, 4, 95)).withSeat(DspSound.Seat.REAR)
        val setup = McuSetup(dspField = field)

        assertEquals(setup, McuSetup.fromRows(setup.toRows()))
        assertEquals(DspSound.Field(), McuSetup.fromRows(emptyMap()).dspField)
    }
}
