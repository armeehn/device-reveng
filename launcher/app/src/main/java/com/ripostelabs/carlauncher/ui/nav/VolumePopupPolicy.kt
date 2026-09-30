package com.ripostelabs.carlauncher.ui.nav

import com.ripostelabs.carlauncher.carlib.Gear
import com.ripostelabs.carlauncher.carlib.VolumeReading

/**
 * Whether a volume report pops the volume window (RAV4-155).
 *
 * <pre>
 *   fascia key ─┐
 *   wheel key  ─┼─▶ MCU amp ──79/78──▶ CarEvents.volume ──▶ onReading ──▶ VolumePopup (3 s)
 *   our slider ─┘
 * </pre>
 *
 * Every source ends as an MCU report, so the report is the one trigger. A report shows when
 * the level or mute changed, or when the MCU asks for the window (a key held at the limit
 * repeats the same level with the flag set, as stock's `showVolWnd` does). The first report
 * is the boot level and stays quiet. Reverse hides it: the camera picture stays clear.
 */
class VolumePopupPolicy {

    private var last: VolumeReading? = null

    fun onReading(reading: VolumeReading?, gear: Gear): Boolean {
        if (reading == null) {
            return false
        }

        val previous = last
        last = reading
        if (previous == null || gear == Gear.REVERSE) {
            return false
        }

        // Our own slider's `05 05 v` may come back flagged silent; a new level still shows.
        val changed = reading.level != previous.level || reading.muted != previous.muted
        return changed || reading.showWindow
    }
}
