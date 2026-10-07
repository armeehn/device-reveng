package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The codec mic gain raised at launcher start: every ADC, the chosen value, through root. */
class CodecMicGainTest {

    @Test
    fun setsAllThreeAdcs() {
        assertEquals(
            listOf("tinymix \"ADC1 Volume\" 16", "tinymix \"ADC2 Volume\" 16", "tinymix \"ADC3 Volume\" 16"),
            CodecMicGain.commands(16),
        )
    }

    // Bench 2026-10-07: 8 left speech near -47 dBFS; 20 peaked at -4 dBFS on room noise.
    @Test
    fun theChosenGainIsAboveTheVendorAndBelowTheTop() {
        assertTrue(CodecMicGain.VOLUME > CodecMicGain.VENDOR_VOLUME)
        assertTrue(CodecMicGain.VOLUME < 20)
    }

    @Test
    fun applyRunsEveryCommandThroughTheShell() {
        val ran = mutableListOf<String>()
        CodecMicGain.apply({ ran += it; RootShell.Result(0, emptyList(), emptyList()) })
        assertEquals(CodecMicGain.commands(CodecMicGain.VOLUME), ran)
    }
}
