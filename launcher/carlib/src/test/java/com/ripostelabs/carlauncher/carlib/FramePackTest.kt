package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FramePackTest {

    @Test
    fun layoutIsLengthThenBytes() {
        val packed = FramePack.pack(listOf(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()), byteArrayOf(0xDD.toByte())))
        assertArrayEquals(byteArrayOf(0, 3, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0, 1, 0xDD.toByte()), packed)
    }

    // The real handshake survives the trip byte for byte.
    @Test
    fun startupFramesRoundTrip() {
        val frames = McuOwnerProtocol.startup(McuOwnerProtocol.StartupConfig(mainVolume = 12, setup = McuSetup()))
        val back = FramePack.unpack(FramePack.pack(frames))

        assertEquals(frames.size, back.size)
        frames.zip(back).forEach { (a, b) -> assertArrayEquals(a, b) }
    }

    @Test
    fun emptyAndZeroLengthFramesSurvive() {
        assertEquals(0, FramePack.unpack(FramePack.pack(emptyList())).size)
        assertEquals(0, FramePack.unpack(FramePack.pack(listOf(ByteArray(0)))).single().size)
    }

    @Test
    fun truncatedPackIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { FramePack.unpack(byteArrayOf(0, 5, 1, 2)) }
        assertThrows(IllegalArgumentException::class.java) { FramePack.unpack(byteArrayOf(0)) }
    }
}
