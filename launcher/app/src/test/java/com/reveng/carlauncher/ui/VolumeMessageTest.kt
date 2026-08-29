package com.reveng.carlauncher.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isVolumeMessage] decides whether a gateway status line should nudge a volume re-read. It is a
 * prefix test and nothing more: the vendor AIDL exposes no volume callback, the marker itself is
 * documented for the LocalSocket channel rather than for the message listener, and the payload
 * after the marker was never traced. These tests hold that line — a match may only trigger a read
 * of the confirmed getter, and an unrelated line must never trigger one.
 */
class VolumeMessageTest {

    @Test
    fun `matches the volume marker with and without a payload`() {
        assertTrue(isVolumeMessage("SYSTEM_VOLUME:12"))
        assertTrue(isVolumeMessage("SYSTEM_VOLUME:"))
        assertTrue(isVolumeMessage("SYSTEM_VOLUME"))
        // The gateway's line framing is unknown, so a leading space must not lose the event.
        assertTrue(isVolumeMessage("  SYSTEM_VOLUME:12"))
    }

    @Test
    fun `ignores every other gateway line`() {
        assertFalse(isVolumeMessage(null))
        assertFalse(isVolumeMessage(""))
        assertFalse(isVolumeMessage("CURRENT_MODE_INFO:1"))
        assertFalse(isVolumeMessage("CAR_ACC_STATUS:0"))
        // Marker present but not at the start: not a volume line, and not ours to interpret.
        assertFalse(isVolumeMessage("ONCLICK:SYSTEM_VOLUME"))
    }
}
