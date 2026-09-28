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
    }

    // With the car service bound the warm-up reads and nudges the decoder through it.
    @Test
    fun theServiceAnswersTheWarmUp() {
        val svc = FakeDecoder(lock = true)
        DecoderSignal.service = svc

        assertTrue(DecoderSignal.locked())
        DecoderSignal.forceStreamable()
        DecoderSignal.redetect()

        assertEquals(listOf(ICarService.DECODER_FORCE_STREAMABLE, ICarService.DECODER_REDETECT), svc.signals)
    }

    @Test
    fun anUnlockedServiceSaysSo() {
        DecoderSignal.service = FakeDecoder(lock = false)

        assertFalse(DecoderSignal.locked())
    }
}
