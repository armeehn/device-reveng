package com.ripostelabs.carlauncher.carlib

/**
 * HfpYield — RAV4-278: the HF client steps aside for a CarPlay phone.
 *
 * A cellular call on a CarPlay iPhone reaches the car twice: as CarPlay telephony (Opus over
 * Wi-Fi, our mic and speaker) and as an HFP call. The HF client hands the HFP one to Telecom,
 * which makes it a SIM call, sets the audio mode to IN_CALL and opens the Dialer. In that mode
 * the audio policy silences every capture but the call's own, so the CarPlay mic sends zeros
 * and the other party hears nothing (car logs 2026-10-01 08:24: `adev_set_mode: mode 2`, then
 * `mic: peak 0`). CarPlay carries the call, so HFP has no job while it is up.
 *
 * ```
 *  CarPlay up, HF phone on      ─▶ PARK     connection policy FORBIDDEN: link drops, stays down
 *  CarPlay down, phones parked  ─▶ RESTORE  policy ALLOWED: the stack reconnects them
 *  otherwise                    ─▶ NONE
 * ```
 *
 * The A2DP sink and AVRCP are left alone: they carry the now-playing metadata, not the call.
 */
object HfpYield {

    enum class Step { NONE, PARK, RESTORE }

    /** [hfDevice] is the HF client's connected phone; [parked] the addresses already forbidden. */
    fun step(carPlay: Boolean, hfDevice: String?, parked: Set<String>): Step {
        if (!carPlay) {
            return if (parked.isEmpty()) Step.NONE else Step.RESTORE
        }

        // A parked phone can still read as connected while its link goes down.
        if (hfDevice == null || hfDevice in parked) {
            return Step.NONE
        }

        return Step.PARK
    }
}
