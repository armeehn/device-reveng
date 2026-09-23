package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 0xA5 relay as a stream. The split below is the car's, 2026-09-22: a len-61 frame (66 bytes)
 * cut after 50 bytes, which logged the two warnings pinned in the first case every ~40 s.
 */
class McuCanRelayTest {

    private companion object {
        const val SPLIT_PAYLOAD = 61
        const val SPLIT_FIRST = 50
        const val LONG_CMD = 0x7E
        const val SHORT_CMD = 0x32
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val longPayload = ByteArray(SPLIT_PAYLOAD) { it.toByte() }
    private val longFrame = McuFrame.encode(LONG_CMD, longPayload)
    private val firstHalf = longFrame.copyOfRange(0, SPLIT_FIRST)
    private val secondHalf = longFrame.copyOfRange(SPLIT_FIRST, longFrame.size)

    private val shortPayload = bytes(0x00, 0x00, 0x05, 0x14, 0x00, 0x00, 0x00, 0x00, 0x00, 0xFF)
    private val shortFrame = McuFrame.encode(SHORT_CMD, shortPayload)

    private fun malformed(reason: String) = McuFrame.Decoded.Malformed(reason)

    @Test
    fun `one frame per relay reproduces the car's two warnings`() {
        val first = McuSerial.Command(McuSerial.OP_CAN, firstHalf).innerFrame()
        val second = McuSerial.Command(McuSerial.OP_CAN, secondHalf).innerFrame()

        assertEquals(malformed("len says 61, so 66 bytes, but got 50"), first)
        assertEquals(malformed("bad header; expected 5A A5"), second)
    }

    @Test
    fun `a frame split across two relays decodes once both arrive`() {
        val relay = McuCanRelay()

        assertEquals(emptyList<McuFrame.Decoded>(), relay.feed(firstHalf))
        assertEquals(listOf(McuFrame.Decoded.Frame(LONG_CMD, longPayload)), relay.feed(secondHalf))
    }

    @Test
    fun `one relay carrying two frames yields both`() {
        val decoded = McuCanRelay().feed(shortFrame + longFrame)

        assertEquals(
            listOf(McuFrame.Decoded.Frame(SHORT_CMD, shortPayload), McuFrame.Decoded.Frame(LONG_CMD, longPayload)),
            decoded,
        )
    }

    @Test
    fun `a header split between relays is kept`() {
        val relay = McuCanRelay()

        assertEquals(emptyList<McuFrame.Decoded>(), relay.feed(shortFrame.copyOfRange(0, 1)))
        assertEquals(
            listOf(McuFrame.Decoded.Frame(SHORT_CMD, shortPayload)),
            relay.feed(shortFrame.copyOfRange(1, shortFrame.size)),
        )
    }

    @Test
    fun `junk before a header is reported once`() {
        val decoded = McuCanRelay().feed(bytes(0x01, 0x02, 0x03) + shortFrame)

        assertEquals(
            listOf(malformed("skipped 3 bytes before 5A A5"), McuFrame.Decoded.Frame(SHORT_CMD, shortPayload)),
            decoded,
        )
    }

    // A relay lost mid-frame: the orphan head swallows the next bytes, fails, and the scan resumes
    // inside it, so the frames behind it still decode.
    @Test
    fun `a lost second half costs only the frame it cut`() {
        val relay = McuCanRelay()
        val decoded = relay.feed(firstHalf) + relay.feed(shortFrame) + relay.feed(shortFrame)

        assertEquals(3, decoded.size)
        assertTrue(decoded[0] is McuFrame.Decoded.Malformed)
        assertEquals(McuFrame.Decoded.Frame(SHORT_CMD, shortPayload), decoded[1])
        assertEquals(McuFrame.Decoded.Frame(SHORT_CMD, shortPayload), decoded[2])
    }
}
