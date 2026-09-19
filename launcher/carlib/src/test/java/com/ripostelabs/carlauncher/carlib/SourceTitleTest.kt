package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceTitleTest {

    @Test
    fun `audio sources have a title`() {
        assertEquals("Radio", SourceTitle.of(McuOwnerProtocol.Mode.RADIO))
        assertEquals("Bluetooth", SourceTitle.of(McuOwnerProtocol.Mode.BT_MUSIC))
        assertEquals("CarPlay", SourceTitle.of(McuOwnerProtocol.Mode.CARPLAY))
    }

    @Test
    fun `no mode or a control mode has none`() {
        assertNull(SourceTitle.of(null))
        assertNull(SourceTitle.of(McuOwnerProtocol.Mode.NONE))
        assertNull(SourceTitle.of(McuOwnerProtocol.Mode.POWER_ON))
        assertNull(SourceTitle.of(McuOwnerProtocol.Mode.MCU_VERSION))
    }
}
