package com.ripostelabs.carlauncher.data

import com.ripostelabs.car.ICarService
import com.ripostelabs.carlauncher.carlib.CarDecoder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * camera_status indexes libais_pr2000.so's 13-row size table (status - 1, `cmp w8, #0xc`); any
 * other value gives the server 0 x 0 and a dead stream. Seen on the bench: 0 with no signal,
 * 7 for AHD 720p30, 218 after a newline-mangled write.
 */
class DecoderSignalTest {

    // Stock opens at whatever row the chip settles on (BackcarEvent.java:1514-1540): 7 is AHD
    // 720p30, 11 is TVI 720p30, and this camera has come up as both (car, 2026-09-28/29).
    @Test
    fun anyReportedFormatIsALock() {
        assertTrue(DecoderSignal.isLocked("7"))
        assertTrue(DecoderSignal.isLocked("11\n"))
        assertTrue(DecoderSignal.isLocked("1"))
        assertTrue(DecoderSignal.isLocked("13"))
    }

    // The driver detects only inside pr2000_show (its one call to check_pr2000_signal, 0xb4cd5c):
    // reading camera_status never looks at the chip, so a launcher polling it waited forever on
    // a cold boot while every manual `cat /sys/pr2000/pr2000` found the camera (2026-09-29).
    @Test
    fun theLockIsReadWhereTheDriverDetects() {
        assertEquals("cat /sys/pr2000/pr2000", DecoderSignal.STATUS_COMMAND)
    }

    @Test
    fun noSignalOrJunkIsNot() {
        assertFalse(DecoderSignal.isLocked("0"))
        assertFalse(DecoderSignal.isLocked("14"))
        assertFalse(DecoderSignal.isLocked("218"))
        assertFalse(DecoderSignal.isLocked(""))
        assertFalse(DecoderSignal.isLocked(null))
    }

    // The lock is read from the node, not asked of the service.
    @Test
    fun theServiceDoesNotDecideTheLock() {
        DecoderSignal.service = FakeDecoder(lock = true)
        DecoderSignal.statusRead = { "0" }

        assertFalse(DecoderSignal.locked())
    }

    private class FakeDecoder(private val lock: Boolean?) : CarDecoder {
        val signals = mutableListOf<Int>()
        override fun setDecoderMode(mode: Int) = true
        override fun decoderLocked() = lock
        override fun decoderSignal(action: Int): Boolean { signals += action; return true }
    }

    @After
    fun noService() {
        DecoderSignal.service = null
        DecoderSignal.shell = DecoderSignal.ROOT
        DecoderSignal.statusRead = DecoderSignal.ROOT_STATUS
    }

    // Stock's nudge minus its reset: on this kernel `r` drops the channel and leaves 0, while
    // c1 + v0 ran check_pr2000_signal and the row came within a second (car, 2026-09-29).
    // A forced v7 only re-triggers the check, and a stream opened under it stayed dark.
    @Test
    fun theNudgeIsAutoOnChannelOne() {
        val svc = FakeDecoder(lock = true)
        DecoderSignal.service = svc
        val ran = mutableListOf<String>()
        DecoderSignal.shell = { ran += it }
        DecoderSignal.statusRead = { "7" }

        assertTrue(DecoderSignal.locked())
        DecoderSignal.forceStreamable()
        DecoderSignal.redetect()

        assertEquals(emptyList<Int>(), svc.signals)
        assertEquals(2, ran.size)
        for (command in ran) {
            val writes = Regex("printf (\\w+) > /sys/pr2000/pr2000").findAll(command).map { it.groupValues[1] }.toList()
            assertEquals(listOf("c1", "v0"), writes)
        }
    }
}
