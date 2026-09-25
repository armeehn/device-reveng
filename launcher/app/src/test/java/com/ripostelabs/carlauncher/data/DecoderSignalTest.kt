package com.ripostelabs.carlauncher.data

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
}
