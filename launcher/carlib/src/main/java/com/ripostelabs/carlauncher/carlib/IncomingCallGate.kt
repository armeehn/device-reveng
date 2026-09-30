package com.ripostelabs.carlauncher.carlib

/**
 * RAV4-152 — when the incoming-call window shows and when the car rings, on Riposte OS 0.2.
 *
 * Stock btsuite floats `BTFloatWndLandscape` over any app for HFP states 4/5/6
 * (`BTFloatWndLandscape.java:53,495-512`). On 0.2 there is no btsuite, so the launcher does it
 * from [BtCarKit]'s snapshot:
 *
 * ```
 *  BtCarKit.snapshot ─┐
 *  CarEvents.carplay ─┼─▶ IncomingCallGate.decide ─▶ IncomingCallView ─┬─▶ window (show)
 *  launcher front ────┘         ▲                                     └─▶ ringer (ring)
 *                               └── onAction (Answer / Decline tapped)
 * ```
 *
 * - A CarPlay session owns the phone's calls (projection PR #83 broadcasts PHONE_CALL_ON/OFF):
 *   while one is up nothing shows, so a CarPlay call never gets a second window.
 * - The Phone screen already carries Answer / Reject, so the window stays off while it is in
 *   front. The ringtone does not: the screen makes no sound.
 * - Local ringtone only for a first call ([HfCallState.INCOMING]) and only while the phone sends
 *   no in-band ring (SCO audio down). A waiting call beeps on the phone's own audio.
 */
class IncomingCallGate {

    /** The driver tapped Answer or Decline on the current ring: hidden until it ends. */
    private var actedOn = false

    fun onAction() {
        actedOn = true
    }

    fun decide(s: BtCarKitSnapshot, carPlay: CarPlayState, front: LauncherFront): IncomingCallView {
        val ringing = BtCarKitMap.leadCall(s.calls)?.takeIf { it.state in RINGING }
        if (ringing == null) {
            actedOn = false
            return IncomingCallView.NONE
        }
        if (actedOn || carPlay.connected || carPlay.inCall) {
            return IncomingCallView.NONE
        }

        return IncomingCallView(
            show = front != LauncherFront.PHONE_SCREEN,
            ring = ringing.state == HfCallState.INCOMING && s.hfAudio == HfAudio.OFF,
            number = ringing.number,
        )
    }

    private companion object {
        val RINGING = setOf(HfCallState.INCOMING, HfCallState.WAITING)
    }
}

/** One frame for the window and the ringer. */
data class IncomingCallView(
    val show: Boolean,
    val ring: Boolean,
    val number: String?,
) {
    companion object {
        val NONE = IncomingCallView(show = false, ring = false, number = null)
    }
}

/** What the launcher has in front, as far as the window cares. */
enum class LauncherFront { PHONE_SCREEN, ELSEWHERE }

/** The HF client's SCO link: ON while the phone streams call audio or an in-band ring. */
enum class HfAudio {
    OFF,
    ON,
    ;

    companion object {
        /** `BluetoothHeadsetClient.STATE_AUDIO_CONNECTED` (android-14.0.0_r1). */
        const val STATE_CONNECTED = 2

        fun of(extra: Int): HfAudio = if (extra == STATE_CONNECTED) ON else OFF
    }
}
