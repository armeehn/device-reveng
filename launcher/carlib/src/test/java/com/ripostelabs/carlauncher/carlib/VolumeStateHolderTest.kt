package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The fold the vendor's `mMainVol` / `mMuteOn` fields do, one frame at a time. */
class VolumeStateHolderTest {

    private val holder = VolumeStateHolder()

    @Test
    fun nullUntilTheMcuHasSaid() {
        assertNull(holder.state.value)
    }

    @Test
    fun muteKeepsLevel() {
        holder.onMainVolume(McuOwnerProtocol.MainVolume(level = 12, silent = false))
        assertEquals(VolumeState(level = 12, muted = false), holder.state.value)

        holder.onMute(McuOwnerProtocol.Mute(muted = true, silent = false))
        assertEquals(VolumeState(level = 12, muted = true), holder.state.value)
    }

    /**
     * The car (2026-10-01): volume down to 0 showed the mute icon, and it stayed on after the
     * level rose again. A non-zero level ends the mute at once.
     */
    @Test
    fun nonZeroLevelClearsMute() {
        holder.onMainVolume(McuOwnerProtocol.MainVolume(level = 0, silent = false))
        holder.onMute(McuOwnerProtocol.Mute(muted = true, silent = false))

        holder.onMainVolume(McuOwnerProtocol.MainVolume(level = 3, silent = false))

        assertEquals(VolumeState(level = 3, muted = false), holder.state.value)
    }

    /** Level 0 is silence whatever the mute flag says, so the icon shows it. */
    @Test
    fun zeroLevelIsMuted() {
        holder.onMainVolume(McuOwnerProtocol.MainVolume(level = 5, silent = false))
        holder.onMainVolume(McuOwnerProtocol.MainVolume(level = 0, silent = false))
        assertEquals(VolumeState(level = 0, muted = true), holder.state.value)

        holder.onMute(McuOwnerProtocol.Mute(muted = false, silent = false))
        assertEquals(VolumeState(level = 0, muted = true), holder.state.value)
    }

    /** A mute report before any level is the MCU's word alone. */
    @Test
    fun muteBeforeLevelIsTheReport() {
        holder.onMute(McuOwnerProtocol.Mute(muted = false, silent = false))
        assertEquals(false, holder.state.value?.muted)
    }
}
