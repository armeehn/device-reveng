package com.ripostelabs.carlauncher.carlib

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The MCU's last word on the main volume; null until it has said (an owner that heard nothing). */
data class VolumeState(val level: Int, val muted: Boolean)

/**
 * VolumeStateHolder — the cache behind getMainVolume / isMuteOn on Riposte OS 0.2.
 *
 *     McuOwner ──▶ Listener.onMainVolume(79) / onMute(78) ──▶ VolumeStateHolder ──▶ CarService
 *
 * The vendor gateway folds the same two frames into `mMainVol` / `mMuteOn` and its getters read
 * them back (onCmdMainVolEvent, onCmdMuteEvent). A `79` keeps the mute flag, a `78` keeps the
 * level, as the vendor's fields do.
 */
class VolumeStateHolder : McuOwner.Listener {

    private val _state = MutableStateFlow<VolumeState?>(null)
    val state: StateFlow<VolumeState?> = _state.asStateFlow()

    /** Called from the owner's pump thread only, so read-modify-write needs no loop. */
    override fun onMainVolume(volume: McuOwnerProtocol.MainVolume) {
        _state.value = VolumeState(level = volume.level, muted = _state.value?.muted ?: false)
    }

    override fun onMute(mute: McuOwnerProtocol.Mute) {
        _state.value = VolumeState(level = _state.value?.level ?: 0, muted = mute.muted)
    }
}
