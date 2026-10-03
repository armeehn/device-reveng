package com.ripostelabs.carlauncher.input

import com.ripostelabs.carlauncher.carlib.BtCarKit
import com.ripostelabs.carlauncher.carlib.KeyAction
import com.ripostelabs.carlauncher.carlib.MediaActions
import com.ripostelabs.carlauncher.carlib.NavVolume
import com.ripostelabs.carlauncher.data.WheelGestureAction

/**
 * Riposte OS 0.2: one [KeyAction] from [com.ripostelabs.carlauncher.carlib.KeyRouter] to the
 * launcher's existing calls. The table is in carlib; this is only the last hop.
 *
 * Volume is one [NavVolume] step, the nav bar's path: [com.ripostelabs.carlauncher.carlib.AmpVolumeKeys]
 * sends the amp its `05 05 v` step, the one frame verified in the car (2026-09-22). The vendor's
 * `08 xx` echo for the same keys is unverified, so it is not used here.
 */
class KeyActionDispatcher(
    private val volume: (NavVolume.Step) -> Unit,
    private val gestures: WheelGestureDispatcher,
    private val carKit: () -> BtCarKit?,
    private val zlinkConnected: () -> Boolean,
    private val sourceMode: () -> Unit,
    private val back: () -> Unit,
    private val openPhone: () -> Unit,
    private val openSettings: () -> Unit,
    private val mediaAction: (String) -> Boolean,
) {

    /** Run [action]; false when nothing could act on it, so feedback stays quiet. */
    fun run(action: KeyAction): Boolean {
        when (action) {
            KeyAction.VOLUME_UP -> volume(NavVolume.Step.UP)
            KeyAction.VOLUME_DOWN -> volume(NavVolume.Step.DOWN)
            KeyAction.MUTE -> gestures.run(WheelGestureAction.MUTE_TOGGLE)
            KeyAction.NEXT -> gestures.run(WheelGestureAction.NEXT_TRACK)
            KeyAction.PREV -> gestures.run(WheelGestureAction.PREV_TRACK)
            KeyAction.PLAY_PAUSE -> gestures.run(WheelGestureAction.PLAY_PAUSE)
            KeyAction.MODE -> sourceMode()
            KeyAction.TALK -> talk()
            KeyAction.HANGUP -> return carKit()?.let { it.hangUp(); true } ?: false
            KeyAction.VOICE -> gestures.run(if (zlinkConnected()) WheelGestureAction.SIRI else WheelGestureAction.VOICE)
            KeyAction.HOME -> gestures.run(WheelGestureAction.OPEN_HOME)
            KeyAction.BACK -> back()
            KeyAction.RADIO -> gestures.run(WheelGestureAction.OPEN_RADIO)
            KeyAction.NAV -> gestures.run(WheelGestureAction.NAV)
            KeyAction.OPEN_MEDIA -> gestures.run(WheelGestureAction.OPEN_MEDIA)
            KeyAction.SETTINGS -> openSettings()
            KeyAction.REPEAT -> return mediaAction(MediaActions.REPEAT)
            KeyAction.SHUFFLE -> return mediaAction(MediaActions.SHUFFLE)
        }
        return true
    }

    /**
     * TALK answers a ringing call and otherwise opens the phone, as eventcenter opened btsuite
     * (`ProcessCanKey` 23, EventService.java:13060-13066). `acceptCall` with nothing ringing is
     * a no-op in the HF client. UNVERIFIED: the vendor sent CarPlay its own key code (5) while
     * projecting; Zlink exposes no such call here, so the phone screen opens instead.
     */
    private fun talk() {
        carKit()?.answer()
        openPhone()
    }
}
