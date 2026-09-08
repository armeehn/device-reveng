package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture is the deliverable of a drive, so the two things that must not go wrong are the
 * format (anything else means writing a parser later) and the cap (an unattended capture on this
 * bus grows ~3 MB a minute).
 */
class CanableRecorderTest {

    @Test
    fun `a frame is one candump line`() {
        val out = StringBuilder()
        val recorder = CanableRecorder(out)

        recorder.record(SlcanFrame(0x4A5, listOf(0x04, 0x00, 0x80)), atMs = 1_757_356_789_123L)

        assertEquals("(1757356789.123000) can0 4A5#040080\n", out.toString())
    }

    @Test
    fun `an extended id keeps its full width`() {
        val out = StringBuilder()
        val recorder = CanableRecorder(out)

        recorder.record(SlcanFrame(0x18FF0021, listOf(0xAA), IdFormat.EXTENDED), atMs = 1_000L)

        assertTrue(out.toString().contains("can0 18FF0021#AA"))
    }

    @Test
    fun `a zero-length frame still records`() {
        val out = StringBuilder()
        val recorder = CanableRecorder(out)

        recorder.record(SlcanFrame(0x123, emptyList()), atMs = 2_000L)

        assertTrue(out.toString().endsWith("can0 123#\n"))
    }

    // ── Negative controls ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the cap stops writing rather than truncating a line`() {
        val out = StringBuilder()
        val recorder = CanableRecorder(out, maxBytes = 40)

        repeat(10) { recorder.record(SlcanFrame(0x4A5, listOf(1)), atMs = 1_757_356_789_123L) }

        // Whatever was written is whole lines only: a half-written line would break every parser.
        assertTrue(out.toString().endsWith("\n"))
        assertTrue(recorder.isFull)
    }

    @Test
    fun `frames past the cap are counted, not silently lost`() {
        val out = StringBuilder()
        val recorder = CanableRecorder(out, maxBytes = 1)

        repeat(5) { recorder.record(SlcanFrame(0x123, listOf(0)), atMs = 1_000L) }

        // A capture that quietly ends early is indistinguishable from a bus that went quiet.
        assertEquals(4, recorder.dropped)
    }

    @Test
    fun `a healthy capture drops nothing`() {
        val out = StringBuilder()
        val recorder = CanableRecorder(out)

        repeat(1_000) { recorder.record(SlcanFrame(0x123, listOf(0)), atMs = 1_000L) }

        assertEquals(0, recorder.dropped)
    }
}
