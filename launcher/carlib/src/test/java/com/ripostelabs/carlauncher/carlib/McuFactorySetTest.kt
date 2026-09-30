package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vectors are `sendFactoryMcuSet` (EventService.java:9984-10250) run by hand over this unit's
 * own factorySet.xml (hvac-backup-0014): the frame stock eventcenter sent at every boot.
 * `0D 0A LEN 0F b1..b10 CK 00`, LEN = 0x0C, CK = ~(LEN + Σbody).
 */
class McuFactorySetTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    /** No saved rows: the unit's factory set, byte for byte. */
    @Test
    fun emptyRowsSendTheUnitsStockFrame() {
        val expected = bytes(
            0x0D, 0x0A, 0x0C, 0x0F,
            0x02, // Sys_LogoType 2
            0x14, // Sys_McuSet 20, half-wave encoder on (bit 7 off)
            0x24, // Sys_CarInfor_ID 9 << 2
            0x12, // not Suoluode (bit 1), fixed bit 4
            0x44, // auto antenna (bit 2), no soft reverse (bit 6), radar tone off
            0x00, // screen off with ACC, NDebug 1, bit depth 1
            0x08, // panel 1920x720
            0xC0, // fixed bit 6, TV out (bit 7), no power-off delay
            0x19, // fan (bit 0), GPS mix (bit 1 off), A/C supplier 6 << 2
            0x10, // sleep switch (bit 4)
            0x63, 0x00,
        )

        assertArrayEquals(expected, McuFactorySet.frame(emptyMap()))
    }

    /** Every user row flipped at once; the vendor-config bytes stay put. */
    @Test
    fun userRowsFlipOnlyTheirBits() {
        val rows = mapOf(
            McuFactorySet.KEY_MCU_SET to "${0x14 or 0x20 or 0x40}",
            McuFactorySet.KEY_RADAR_TONE to "1",
            McuFactorySet.KEY_AUTO_ANTENNA to "0",
            McuFactorySet.KEY_SCREEN_OFF_WITH_ACC to "0",
            McuFactorySet.KEY_POWER_OFF_DELAY to "1",
            McuFactorySet.KEY_SLEEP_SWITCH to "0",
        )
        val expected = bytes(0x0D, 0x0A, 0x0C, 0x0F, 0x02, 0x74, 0x24, 0x12, 0xC0, 0x02, 0x08, 0xC2, 0x19, 0x00, 0x93, 0x00)

        assertArrayEquals(expected, McuFactorySet.frame(rows))
    }

    /** Bits 0-1 of Sys_McuSet carry the high byte of Sys_LogoType, not the row's own (:10039). */
    @Test
    fun logoHighByteFillsMcuSetLowBits() {
        val payload = McuFactorySet.payload(mapOf(McuFactorySet.KEY_LOGO_TYPE to "${0x0301}", McuFactorySet.KEY_MCU_SET to "${0x17}"))

        assertEquals(0x01, payload[0].toInt() and 0xFF)
        assertEquals(0x17, payload[1].toInt() and 0xFF)
    }

    /** A garbage row keeps the baseline, the way getRecordInteger falls back. */
    @Test
    fun unparsableRowKeepsTheBaseline() {
        assertArrayEquals(McuFactorySet.frame(emptyMap()), McuFactorySet.frame(mapOf(McuFactorySet.KEY_MCU_SET to "x")))
    }

    /** clickReverseMute (settings ItemTextRightCheckBoxView.java:757-773): on sets 0x20|0x40. */
    @Test
    fun reverseMuteBitsMatchTheSettingsApp() {
        assertEquals(0x74, McuFactorySet.ReverseMute.MUTE.mcuSet(0x14))
        assertEquals(0x14, McuFactorySet.ReverseMute.ATTENUATE.mcuSet(0x74))
        assertEquals(0x54, McuFactorySet.ReverseMute.OFF.mcuSet(0x34))
    }

    /** getSysReverseMute (ProviderHelps.java:262-269): sound row first, then attenuation. */
    @Test
    fun reverseMuteReadsTheTwoRows() {
        assertEquals(McuFactorySet.ReverseMute.ATTENUATE, McuFactorySet.ReverseMute.of(emptyMap()))
        assertEquals(
            McuFactorySet.ReverseMute.MUTE,
            McuFactorySet.ReverseMute.of(mapOf(McuFactorySet.KEY_SOUND_WHEN_REVERSING to "1")),
        )
        assertEquals(
            McuFactorySet.ReverseMute.OFF,
            McuFactorySet.ReverseMute.of(mapOf(McuFactorySet.KEY_REVERSING_ATTENUATION to "0")),
        )
    }

    /** The rows a mode writes read back as that mode, and the McuSet row carries its bits. */
    @Test
    fun reverseMuteRowsRoundTrip() {
        for (mode in McuFactorySet.ReverseMute.values()) {
            val rows = mode.rows(emptyMap())

            assertEquals(mode, McuFactorySet.ReverseMute.of(rows))
            assertEquals("${mode.mcuSet(0x14)}", rows[McuFactorySet.KEY_MCU_SET])
        }
    }

    /** `49 17 min sec` from Sys_Acc_Delay (sendAccDelayTime, :3169-3175). 05+49+17+01+1E = 0x84. */
    @Test
    fun accDelayReadsItsRow() {
        val expected = bytes(0x0D, 0x0A, 0x05, 0x49, 0x17, 0x01, 0x1E, 0x7B, 0x00)

        assertArrayEquals(expected, McuFactorySet.boot(mapOf(McuFactorySet.KEY_ACC_DELAY to "90"))[1])
    }

    @Test
    fun rowChangeSendsOnceAndOnlyForItsKeys() {
        val sent = mutableListOf<ByteArray>()
        var rows = mapOf<String, String>()
        val watcher = McuFactorySet.Watcher({ rows }) { sent += it }

        watcher.onRow("Set_Day_Light")
        assertTrue(sent.isEmpty())

        rows = mapOf(McuFactorySet.KEY_SLEEP_SWITCH to "0")
        watcher.onRow(McuFactorySet.KEY_SLEEP_SWITCH)
        watcher.onRow(McuFactorySet.KEY_SLEEP_SWITCH)
        assertEquals(1, sent.size)
        assertArrayEquals(McuFactorySet.frame(rows), sent.single())

        rows = rows + (McuFactorySet.KEY_ACC_DELAY to "90")
        watcher.onRow(McuFactorySet.KEY_ACC_DELAY)
        assertEquals(2, sent.size)
        assertArrayEquals(McuSetupProtocol.accDelay(90), sent.last())
    }

    /** Boot republishes every row; what boot already sent is not sent again. */
    @Test
    fun republishAfterBootSendsNothing() {
        val sent = mutableListOf<ByteArray>()
        val rows = mapOf(McuFactorySet.KEY_SLEEP_SWITCH to "0", McuFactorySet.KEY_ACC_DELAY to "5")
        val watcher = McuFactorySet.Watcher({ rows }) { sent += it }

        watcher.booted(McuFactorySet.boot(rows))
        rows.keys.forEach(watcher::onRow)

        assertTrue(sent.isEmpty())
    }
}
