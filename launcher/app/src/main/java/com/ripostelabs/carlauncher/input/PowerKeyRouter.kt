package com.ripostelabs.carlauncher.input

import com.ripostelabs.carlauncher.carlib.McuOwner
import com.ripostelabs.carlauncher.carlib.McuOwnerProtocol
import com.ripostelabs.carlauncher.carlib.PowerKeyMode
import com.ripostelabs.carlauncher.ui.nav.ReversePicture

/**
 * The POWER key, routed by the driver's choice (RAV4-156). Stock: onPowerClicked,
 * EventService.java:13824-13880.
 *
 * <pre>
 *   MCU 72 key ──▶ PowerKeyRouter ──┬─ panel dark ───────▶ Panel.light()   (any key)
 *                                   ├─ POWER, SCREEN_OFF ─▶ Panel.darken()
 *                                   └─ POWER, STANDBY ────▶ McuSleepWake.PowerKeyListener
 * </pre>
 *
 * SCREEN_OFF never arms the ACC standby timer, and the owner skips its SRC_POWEROFF burst
 * ([com.ripostelabs.carlauncher.carlib.McuPort.setPowerKey]), so the unit keeps running (audio,
 * nav prompts) behind a black panel. It is a window and a `1F` frame, not an Android sleep: no doze, no
 * KEYCODE_POWER, nothing that brings back the flash #294 removed. ACC off still sleeps the unit
 * through [com.ripostelabs.carlauncher.carlib.McuSleepWake] as before.
 */
class PowerKeyRouter(
    private val choice: () -> PowerKeyMode,
    private val standby: McuOwner.Listener,
    private val panel: Panel,
    /** The reverse picture; any thread. Stock lifts the black screen while it is up. */
    private val reverse: () -> ReversePicture = { ReversePicture.DOWN },
) : McuOwner.Listener {

    /** The black screen. Called on the owner's reader thread; the real one posts to main. */
    interface Panel {
        val dark: Boolean

        fun darken()

        fun light()
    }

    override fun onPanelKey(key: McuOwnerProtocol.PanelKey) {
        // A press on a dark panel is the driver looking for the screen, nothing more.
        if (panel.dark) {
            panel.light()
            return
        }

        if (key.code != McuOwnerProtocol.Key.POWER) {
            return
        }

        when (choice()) {
            PowerKeyMode.SCREEN_OFF -> darkenUnlessReversing()
            PowerKeyMode.STANDBY -> standby.onPanelKey(key)
        }
    }

    /** A black window and `1F 00` over the camera would hide it: in reverse the press does nothing. */
    private fun darkenUnlessReversing() {
        if (reverse() == ReversePicture.UP) {
            return
        }

        panel.darken()
    }
}
