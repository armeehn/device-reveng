package com.ripostelabs.carlauncher.carlib

/**
 * DoorPopup — when the door overlay shows and hides, as stock `DoorInfoWindow` decides it
 * (`CB/ui/door/DoorInfoWindow.java:50-85`).
 *
 *     door mask   last    ──▶ action
 *     0x40        0x00    ──▶ SHOW   (driver's door opened)
 *     0x40        0x40    ──▶ NONE   (the box repeats 0x11)
 *     0x48        0x40    ──▶ SHOW   (tailgate too: shown again, timer restarts)
 *     0x00        0x48    ──▶ HIDE   (all shut)
 *
 * The overlay hides itself [AUTO_HIDE_MS] after a show (`:177-178`) and on a tap (`:306-310`).
 * No speed gate: stock hands every door byte to the window (`CanDataParseBase.java:452-460`).
 */
class DoorPopup {

    /** What the overlay does for one door report. */
    enum class Action { SHOW, HIDE, NONE }

    private var lastMask = 0

    /** The action for this report; also remembers it so a repeat is a no-op. */
    fun onDoors(state: DoorState): Action {
        val mask = maskOf(state)
        if (mask == lastMask) {
            return Action.NONE
        }
        lastMask = mask

        return if (mask == 0) Action.HIDE else Action.SHOW
    }

    /** Six openings as bits, so a change of any one is a new mask; the order is ours. */
    private fun maskOf(s: DoorState): Int {
        val open = listOf(s.frontLeft, s.frontRight, s.rearLeft, s.rearRight, s.tailgate, s.bonnet)
        return open.foldIndexed(0) { i, acc, isOpen -> if (isOpen) acc or (1 shl i) else acc }
    }

    companion object {
        /** `sendEmptyMessageDelayed(MSG_AUTO_HIDE_DOOR_INFOR, 10000L)` (`DoorInfoWindow.java:178`). */
        const val AUTO_HIDE_MS = 10_000L
    }
}
