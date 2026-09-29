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

    @Test
    fun aTableRowIsALock() {
        assertTrue(DecoderSignal.isLocked("7"))
        assertTrue(DecoderSignal.isLocked("1\n"))
        assertTrue(DecoderSignal.isLocked("13"))
    }

    @Test
    fun noSignalOrJunkIsNot() {
        assertFalse(DecoderSignal.isLocked("0"))
        assertFalse(DecoderSignal.isLocked("14"))
        assertFalse(DecoderSignal.isLocked("218"))
        assertFalse(DecoderSignal.isLocked(""))
        assertFalse(DecoderSignal.isLocked(null))
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
    }

    // The lock goes through root even with the service bound: its sequence is stock's r, c1, v0,
    // and on this kernel a reset drops the channel and auto never fills camera_status (car,
    // 2026-09-28). c1 then a one-digit v7 gave status 7 at once and the picture.
    @Test
    fun theWarmUpLocksChannelOneAt720p() {
        val svc = FakeDecoder(lock = true)
        DecoderSignal.service = svc
        val ran = mutableListOf<String>()
        DecoderSignal.shell = { ran += it }

        assertTrue(DecoderSignal.locked())
        DecoderSignal.forceStreamable()
        DecoderSignal.redetect()

        assertEquals(emptyList<Int>(), svc.signals)
        assertEquals(2, ran.size)
        for (command in ran) {
            val writes = Regex("printf (\\w+) > /sys/pr2000/pr2000").findAll(command).map { it.groupValues[1] }.toList()
            assertEquals(listOf("c1", "v7"), writes)
        }
    }

    @Test
    fun anUnlockedServiceSaysSo() {
        DecoderSignal.service = FakeDecoder(lock = false)

        assertFalse(DecoderSignal.locked())
    }
}
