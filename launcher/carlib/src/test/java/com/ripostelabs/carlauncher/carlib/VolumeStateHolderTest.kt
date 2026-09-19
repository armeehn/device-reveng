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
    fun levelKeepsMuteAndMuteKeepsLevel() {
        holder.onMainVolume(McuOwnerProtocol.MainVolume(level = 12, silent = false))
        assertEquals(VolumeState(level = 12, muted = false), holder.state.value)

        holder.onMute(McuOwnerProtocol.Mute(muted = true, silent = false))
        assertEquals(VolumeState(level = 12, muted = true), holder.state.value)

        holder.onMainVolume(McuOwnerProtocol.MainVolume(level = 15, silent = true))
        assertEquals(VolumeState(level = 15, muted = true), holder.state.value)
    }
}
