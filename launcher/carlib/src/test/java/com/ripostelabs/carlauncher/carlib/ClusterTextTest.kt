package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/**
 * RAV4-182: the cluster frames, byte for byte against stock canbus2
 * (`HiworldCanParseToyota.java`, SendRadioInfo :1034, sendMediaStrToCan :1172,
 * handleBTStateEvent :1188, handleBTPhoneNumEvent :1214, sendSysRTCTimerToCan :1958).
 */
class ClusterTextTest {

    private fun ints(vararg v: Int) = v

    private fun ascii(s: String) = s.map { it.code }.toIntArray()

    /** Stock's stringToUnicode0: low byte, then high byte, per char (CanDataParseBase.java:1810). */
    private fun utf16(s: String) = s.flatMap { listOf(it.code and 0xFF, it.code shr 8) }.toIntArray()

    /** Stock's fixed-size frame: [len, cmd, ...] zero-filled, as `new byte[n]` leaves it. */
    private fun stock(size: Int, vararg at: Pair<Int, IntArray>): IntArray {
        val out = IntArray(size)
        at.forEach { (start, bytes) -> bytes.copyInto(out, start) }
        return out
    }

    @Test
    fun fmStationIsBankPresetAndFrequencyInTenths() {
        // FM1, preset index 1 (shown "02"), 98.10 MHz: 0D 91 01 '0' '2' ... '9' '8' '.' '1'.
        val expected = stock(15, 0 to ints(13, 0x91, 1), 3 to ascii("02"), 11 to ascii("98.1"))

        assertArrayEquals(expected, ClusterText.radio(band = 0, preset = 1, freq = 9810).payload)
    }

    @Test
    fun amStationIsWholeKilohertz() {
        // AM1 is band 3, sent as 4; 1000 kHz right-aligned to the frame's end.
        val expected = stock(15, 0 to ints(13, 0x91, 4), 3 to ascii("01"), 11 to ascii("1000"))

        assertArrayEquals(expected, ClusterText.radio(band = 3, preset = 0, freq = 1000).payload)
    }

    @Test
    fun textIsUtf16LeCutAtFourteenChars() {
        val title = "A very long song title"
        val expected = stock(34, 0 to ints(32, 0x92), 2 to utf16(title.take(14)))

        assertArrayEquals(expected, ClusterText.text(ClusterText.Field.TITLE, title).payload)
        assertEquals(0x94, ClusterText.text(ClusterText.Field.ARTIST, "x").payload[1])
        assertEquals(0x93, ClusterText.text(ClusterText.Field.ALBUM, "x").payload[1])
        assertEquals(0xC4, ClusterText.text(ClusterText.Field.CALLER, "x").payload[1])
    }

    @Test
    fun callStateMapsStockHshfCodes() {
        // HBCP 3 connected -> 0, 4 outgoing -> 2, 5 incoming -> 1, 6 active -> 4, else 6.
        val codes = listOf(3 to 0, 4 to 2, 5 to 1, 6 to 4, 1 to 6, 0 to 6)
        codes.forEach { (hshf, code) ->
            assertArrayEquals("hshf $hshf", stock(29, 0 to ints(27, 0xCD, code)), ClusterText.call(hshf, null).payload)
        }
    }

    @Test
    fun ringingCallCarriesTheNumber() {
        val expected = stock(29, 0 to ints(27, 0xCD, 1), 5 to utf16("2505550100"))

        assertArrayEquals(expected, ClusterText.call(hshf = 5, number = "2505550100").payload)
    }

    @Test
    fun activeCallDropsTheNumber() {
        assertArrayEquals(stock(29, 0 to ints(27, 0xCD, 4)), ClusterText.call(hshf = 6, number = "2505550100").payload)
    }

    @Test
    fun clockIsHourMinuteFormatAndDate() {
        val now = LocalDateTime.of(2026, 9, 30, 14, 5, 42)
        val expected = stock(12, 0 to ints(10, 0xCB), 3 to ints(14, 5), 7 to ints(1, 26, 9, 30))

        assertArrayEquals(expected, ClusterText.clock(now, ClusterText.HourFormat.H24).payload)
        assertEquals(0, ClusterText.clock(now, ClusterText.HourFormat.H12).payload[7])
    }

    /** The wire frame: `5A A5`, the payload, then stock's inner checksum sum - 1 (SendUtil.java:60-85). */
    @Test
    fun wireFrameCarriesStockChecksum() {
        val payload = ClusterText.call(hshf = 3, number = null).payload
        val wire = McuOwnerProtocol.cluster(ClusterText.call(hshf = 3, number = null))

        assertEquals(0x5A, wire[5].toInt() and 0xFF)
        assertEquals(0xA5, wire[6].toInt() and 0xFF)
        payload.forEachIndexed { i, b -> assertEquals("byte $i", b, wire[7 + i].toInt() and 0xFF) }
        assertEquals((payload.sum() - 1) and 0xFF, wire[7 + payload.size].toInt() and 0xFF)
    }
}
