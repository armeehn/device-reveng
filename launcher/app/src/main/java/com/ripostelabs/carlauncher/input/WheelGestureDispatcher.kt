package com.ripostelabs.carlauncher.input

import android.content.Context
import com.ripostelabs.carlauncher.carlib.CarService
import com.ripostelabs.carlauncher.carlib.VendorCanKey
import com.ripostelabs.carlauncher.carlib.WheelKey
import com.ripostelabs.carlauncher.carlib.Zlink
import com.ripostelabs.carlauncher.data.RadioPresetsStore
import com.ripostelabs.carlauncher.data.WheelGestureAction
import com.ripostelabs.carlauncher.media.NowPlayingRepository
import com.ripostelabs.carlauncher.nav.NavRepository
import com.ripostelabs.carlauncher.ui.RadioTuning
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Turns one [WheelGestureAction] into the launcher's existing calls. Nothing here decides
 * *whether* to act — the bindings and the enable switch do that in MainActivity — so every
 * branch is a straight line to a repository, [CarService] or a screen switch.
 *
 * Blocking AIDL reads (mute state, tuner band/frequency) run on IO; everything else is a
 * fire-and-forget call on the thread the gesture arrived on (main).
 */
class WheelGestureDispatcher(
    private val context: Context,
    private val scope: CoroutineScope,
    private val nowPlaying: NowPlayingRepository,
    private val carService: CarService,
    private val radioPresets: RadioPresetsStore,
    private val zlinkConnected: () -> Boolean,
    private val openMedia: () -> Unit,
    private val openRadio: () -> Unit,
    private val openHome: () -> Unit,
) {

    /** Run [action]. Returns false only for [WheelGestureAction.NONE], so feedback can stay quiet. */
    fun run(action: WheelGestureAction): Boolean {
        when (action) {
            WheelGestureAction.NONE -> return false
            WheelGestureAction.SEEK_FORWARD_30S -> seekBy(SEEK_FORWARD_MS)
            WheelGestureAction.SEEK_BACK_10S -> seekBy(-SEEK_BACK_MS)
            WheelGestureAction.NEXT_TRACK -> nowPlaying.next()
            WheelGestureAction.PREV_TRACK -> nowPlaying.prev()
            WheelGestureAction.PLAY_PAUSE -> nowPlaying.playPause()
            WheelGestureAction.OPEN_MEDIA -> openMedia()
            WheelGestureAction.OPEN_RADIO -> openRadio()
            WheelGestureAction.OPEN_HOME -> openHome()
            WheelGestureAction.RADIO_SEEK_UP -> carService.radioSeekUp()
            WheelGestureAction.RADIO_SEEK_DOWN -> carService.radioSeekDown()
            WheelGestureAction.RADIO_NEXT_PRESET -> nextPreset()
            WheelGestureAction.CLAIM_RADIO -> carService.claimRadio()
            WheelGestureAction.RELEASE_SOURCE -> carService.releaseRadio()
            WheelGestureAction.SIRI -> Zlink.request(Zlink.Feature.SIRI).broadcast(context)
            WheelGestureAction.NAV -> nav()
            WheelGestureAction.MUTE_TOGGLE -> toggleMute()
            WheelGestureAction.VOICE -> VendorCanKey.press(WheelKey.VOICE).broadcast(context)
        }
        return true
    }

    /** Seek the active session by [deltaMs] from its live position, clamped to the track. */
    private fun seekBy(deltaMs: Long) {
        val now = nowPlaying.state.value ?: return
        val target = now.livePositionMs() + deltaMs
        val max = if (now.durationMs > 0) now.durationMs else Long.MAX_VALUE
        nowPlaying.seekTo(target.coerceIn(0L, max))
    }

    /** CarPlay's Maps while a phone is projected; otherwise the nav app the Nav card opens. */
    private fun nav() {
        if (zlinkConnected()) {
            Zlink.request(Zlink.Feature.MAPS).broadcast(context)
            return
        }
        NavRepository.launchMaps(context)
    }

    private fun toggleMute() {
        scope.launch(Dispatchers.IO) {
            carService.setMute(!carService.isMuteOn())
        }
    }

    /**
     * Recall the preset after the one the tuner sits on, wrapping; from an unknown frequency,
     * the first. The store is empty on a unit with no presets saved, and then nothing happens.
     */
    private fun nextPreset() {
        val presets = radioPresets.presets.value
        if (presets.isEmpty()) {
            return
        }
        scope.launch(Dispatchers.IO) {
            val band = carService.getRadioBand()
            val freq = carService.getRadioFreq()
            val current = if (band == null || freq == null) -1
            else presets.indexOfFirst { RadioTuning.presetMatches(it, band, freq) }
            val preset = presets[(current + 1) % presets.size]

            RadioTuning.recallPreset(
                readBand = { carService.getRadioBand() },
                selectBand = { RadioTuning.selectBand(carService, it) },
                tune = { carService.sendUserFreq(it, !CarService.isAmBand(preset.band)) },
                preset = preset,
            )
        }
    }

    private companion object {
        const val SEEK_FORWARD_MS = 30_000L
        const val SEEK_BACK_MS = 10_000L
    }
}
