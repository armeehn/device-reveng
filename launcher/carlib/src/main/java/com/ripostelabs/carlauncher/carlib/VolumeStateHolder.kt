package com.ripostelabs.carlauncher.carlib

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The MCU's last word on the main volume; null until it has said (an owner that heard nothing). */
data class VolumeState(val level: Int, val muted: Boolean)

/**
 * When the launcher shows "muted": the MCU reports mute (`78`) or the level is 0. The first
 * non-zero level (`79`) ends it. The car (2026-10-01) showed the mute icon at 0 and kept it
 * after the level rose, because a `79` carried the old flag forward.
 */
internal object MuteRule {
    /** After a `79`: level 0 is silence, any other level is sound. */
    fun afterLevel(level: Int): Boolean = level == 0

    /** After a `78`: the report, or a known level of 0. */
    fun afterMute(muted: Boolean, level: Int?): Boolean = muted || level == 0
}

/**
 * VolumeStateHolder — the cache behind getMainVolume / isMuteOn on Riposte OS 0.2.
 *
 *     McuOwner ──▶ Listener.onMainVolume(79) / onMute(78) ──▶ VolumeStateHolder ──▶ CarService
 *
 * The vendor gateway folds the same two frames into `mMainVol` / `mMuteOn` and its getters read
 * them back (onCmdMainVolEvent, onCmdMuteEvent). A `78` keeps the level, as the vendor's fields
 * do; the mute flag follows [MuteRule], so a non-zero `79` clears it.
 */
class VolumeStateHolder : McuOwner.Listener {

    private val _state = MutableStateFlow<VolumeState?>(null)
    val state: StateFlow<VolumeState?> = _state.asStateFlow()

    /** Called from the owner's pump thread only, so read-modify-write needs no loop. */
    override fun onMainVolume(volume: McuOwnerProtocol.MainVolume) {
        _state.value = VolumeState(level = volume.level, muted = MuteRule.afterLevel(volume.level))
    }

    override fun onMute(mute: McuOwnerProtocol.Mute) {
        val level = _state.value?.level
        _state.value = VolumeState(level = level ?: 0, muted = MuteRule.afterMute(mute.muted, level))
    }
}
