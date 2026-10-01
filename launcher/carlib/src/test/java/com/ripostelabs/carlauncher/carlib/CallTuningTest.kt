package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins call audio against stock eventcenter.
 *
 * Mic gain, `EventService.onSetMicGain` (EventService.java:14836-14854): level 1..5 writes
 * `persist.blinkbt.aec.gain` = 85, 90, 96, 100, 112; anything else is 96.
 * Echo delay, `SystemUtils.initSysBTLaunchSound` (SystemUtils.java:310-332): phone calls to
 * `persist.blinkbt.aec.delay`, CarPlay to `persist.blinkbt.carplay.aecdelay`, both clamped to
 * 0..1000 ms. The settings slider moves in 10 ms steps (DataManage.java:371-379).
 */
class CallTuningTest {

    @Test
    fun `mic gain levels write stock's values`() {
        val written = MicGain.entries.map { it.level to it.value }
        assertEquals(listOf(1 to 85, 2 to 90, 3 to 96, 4 to 100, 5 to 112), written)
        assertEquals("persist.blinkbt.aec.gain", CallTuning.MIC_GAIN_PROP)
    }

    @Test
    fun `a mic gain prop reads back as its level`() {
        assertEquals(MicGain.G112, MicGain.parse("112"))
        assertEquals(MicGain.G85, MicGain.parse(" 85\n"))
        assertNull(MicGain.parse(""))
        assertNull(MicGain.parse("97"))
    }

    @Test
    fun `levels outside 1 to 5 are not a gain`() {
        assertEquals(MicGain.G96, MicGain.of(3))
        assertNull(MicGain.of(0))
        assertNull(MicGain.of(6))
    }

    @Test
    fun `each echo path has stock's prop`() {
        assertEquals("persist.blinkbt.aec.delay", AecPath.PHONE.prop)
        assertEquals("persist.blinkbt.carplay.aecdelay", AecPath.CARPLAY.prop)
        assertEquals(AecPath.CARPLAY, AecPath.of(2))
        assertNull(AecPath.of(3))
    }

    @Test
    fun `delays stay inside stock's clamp`() {
        assertEquals("0", CallTuning.delayValue(0))
        assertEquals("400", CallTuning.delayValue(400))
        assertEquals("1000", CallTuning.delayValue(1000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a delay past 1000 ms is refused`() {
        CallTuning.delayValue(1010)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a negative delay is refused`() {
        CallTuning.delayValue(-10)
    }

    @Test
    fun `a delay prop reads back in ms`() {
        assertEquals(100, CallTuning.parseDelay("100\n"))
        assertNull(CallTuning.parseDelay(""))
        assertNull(CallTuning.parseDelay("5000"))
    }
}
