package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frames eventcenter and btsuite put on the MCU wire around a phone call, from the decompile:
 * `0B state` on every HFP state line (EventService.java:4336-4342), `4C 0A` before answer and
 * `4C 14` + 300 ms before hang up (EventHandle.java:45-53, BTService.java:1706-1711).
 */
class BtCallMcuTest {

    private val sent = mutableListOf<List<Byte>>()
    private val naps = mutableListOf<Long>()
    private val mcu = BtCallMcu(
        object : BtCallMcu.Mcu {
            override fun send(frame: ByteArray) {
                sent += frame.toList()
            }
        },
        sleep = { naps += it },
    )

    private fun frame(vararg v: Int) = v.map { it.toByte() }

    /** LEN 03 + 0B + 05 = 0x13, ~0x13 = 0xEC. A repeat of the same state sends nothing. */
    @Test
    fun stateChangeSendsBtStateOnce() {
        mcu.onHfp(HfpState.INCOMING_CALL, carPlayCall = false)
        mcu.onHfp(HfpState.INCOMING_CALL, carPlayCall = false)
        mcu.onHfp(HfpState.CONNECTED, carPlayCall = false)

        assertEquals(
            listOf(frame(0x0D, 0x0A, 0x03, 0x0B, 0x05, 0xEC, 0x00), McuOwnerProtocol.btState(HfpState.CONNECTED.code).toList()),
            sent,
        )
    }

    /** `sendBTState` is skipped while a CarPlay call is up (`getCarPlayCallState() == 1`, :4339). */
    @Test
    fun carPlayCallSuppressesTheStateFrame() {
        mcu.onHfp(HfpState.INCOMING_CALL, carPlayCall = true)
        assertTrue(sent.isEmpty())

        // The gate lifting re-sends the state the MCU never got.
        mcu.onHfp(HfpState.INCOMING_CALL, carPlayCall = false)
        assertEquals(1, sent.size)
    }

    /** Answer: `4C 0A` (03 + 4C + 0A = 0x59, ~ = 0xA6). Hang up: `4C 14` then a 300 ms nap. */
    @Test
    fun answerAndHangUpMuteTheAmpFirst() {
        mcu.beforeAnswer()
        assertEquals(listOf(frame(0x0D, 0x0A, 0x03, 0x4C, 0x0A, 0xA6, 0x00)), sent)
        assertTrue(naps.isEmpty())

        mcu.beforeHangUp()
        assertEquals(frame(0x0D, 0x0A, 0x03, 0x4C, 0x14, 0x9C, 0x00), sent[1])
        assertEquals(listOf(BtCallMcu.HANG_UP_SETTLE_MS), naps)
    }
}
