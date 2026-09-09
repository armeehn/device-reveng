package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A transcription can drift in two silent ways: two names on one byte, or a byte that is not one.
 * Both are pinned. The negative control is the inner CAN command byte, which must never resolve
 * as an outer opcode however similar the two tables look.
 */
class McuOpcodeTest {

    @Test
    fun `every code is one byte and names one handler`() {
        val codes = McuOpcode.entries.map { it.code }

        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.all { it in 0..0xFF })
        assertTrue(McuOpcode.entries.all { it.handler.isNotBlank() })
    }

    @Test
    fun `the CAN relay is McuSerial's opcode`() {
        assertEquals(McuOpcode.CAN, McuOpcode.of(McuSerial.OP_CAN))
        assertEquals(McuSerial.OP_CAN, McuOpcode.CAN.code)
    }

    @Test
    fun `a key press resolves to the vendor handler`() {
        assertEquals(McuOpcode.PRESS_KEY, McuOpcode.of(0x7E))
        assertEquals("onCmdPressKeyEvent", McuOpcode.PRESS_KEY.handler)
    }

    // ── Negative controls ───────────────────────────────────────────────────────────────────────

    @Test
    fun `an inner CAN command byte is not an outer opcode`() {
        // 0xCB is the cmd of the logged SendCmdLstToCanbus frame (McuFrameTest): a different table.
        assertNull(McuOpcode.of(0xCB))
    }

    @Test
    fun `codes outside a byte resolve to nothing`() {
        assertNull(McuOpcode.of(-1))
        assertNull(McuOpcode.of(0x100))
    }
}
