package com.ripostelabs.carlauncher.carlib

/**
 * BtAutoConnect — btsuite's reconnect campaign, as a pure policy [BtCarKit] drives.
 *
 * The vendor arms it 2 s after its service starts (`BTService.java:985-987`) unless CarPlay is
 * up or a phone is already on (`:1078-1088`), then sends "connect last device" (`BTFunctionEvent
 * (19)` -> `connectDev()`). Each HFP report while armed (`:229-249`): CONNECTING marks an
 * attempt, a READY after one is a failure that spends one of four tries (`mAutoConnectBtCount
 * = 4`, `:120`) and re-sends, anything else (a phone on) ends the campaign.
 *
 * ```
 *  start + 2 s ─ arm ─▶ connect ─▶ CONNECTING ─▶ READY ─▶ connect (x3 at most)
 *                                              └▶ CONNECTED: done
 * ```
 *
 * The stock stack has no such policy for the car-kit roles (`PhonePolicy` auto-connects the
 * phone-side profiles only), so without this the unit waits for the phone to come to it.
 */
class BtAutoConnect {

    private var armed = false
    private var connecting = false
    private var attemptsLeft = 0

    /** The 2 s mark: true = send the first connect. [state] null = nothing known yet. */
    fun arm(state: HfpState?, carPlay: Boolean): Boolean {
        val phoneOn = state != null && state.code >= HfpState.CONNECTED.code
        if (carPlay || phoneOn) {
            return false
        }
        armed = true
        connecting = false
        attemptsLeft = ATTEMPTS
        return true
    }

    /** One HFP report; true = send another connect. */
    fun onState(state: HfpState?): Boolean {
        if (!armed) {
            return false
        }
        if (state == HfpState.CONNECTING) {
            connecting = true
            return false
        }
        if (state != HfpState.READY) {
            // A phone on the link, or the adapter gone: the campaign is over either way.
            armed = false
            return false
        }
        if (!connecting) {
            return false
        }

        // CONNECTING -> READY: one attempt spent (`mAutoConnectBtCount - 1`, `:243-247`).
        connecting = false
        attemptsLeft -= 1
        if (attemptsLeft == 0) {
            armed = false
            return false
        }
        return true
    }

    companion object {
        /** `mAutoConnectBtCount = 4` (BTService.java:120): the first send and three retries. */
        const val ATTEMPTS = 4

        /** `postDelayed(autoConnectBT, 2000L)` after the service came up (BTService.java:981-987). */
        const val START_DELAY_MS = 2_000L
    }
}
