package com.ripostelabs.carlauncher.ui

import com.ripostelabs.carlauncher.carlib.HfpState
import com.ripostelabs.carlauncher.carlib.VendorBtState

/**
 * RAV4-50 — the pure half of [PhoneScreen]: what the HFP state means for the buttons and the
 * labels, and what a dialable number looks like. No framework, so the tests pin it directly.
 *
 * HFP states are btsuite's own table (`BTUtils.java:115-121`, [HfpState]):
 *
 * ```
 *  state          label            Answer  Hang up
 *  null           "No signal"      -       -
 *  INITIALISING   "Starting"       -       -
 *  READY          "No phone"       -       -
 *  CONNECTING     "Connecting"     -       -
 *  CONNECTED      "Connected"      -       -
 *  OUTGOING_CALL  "Calling"        -       yes
 *  INCOMING_CALL  "Incoming call"  yes     yes   (hang up = reject)
 *  ACTIVE_CALL    "In call"        -       yes
 * ```
 */
internal object PhoneLogic {

    /** Digits the vendor dial page accepts; `+` only as a leading trunk prefix. */
    private val DIAL_CHARS = ('0'..'9').toSet() + setOf('*', '#')
    private const val TRUNK_PREFIX = '+'

    /** btsuite's own dial field is bounded; a longer string is a typo, not a number. */
    const val MAX_DIAL_LENGTH = 32

    private const val SECONDS_PER_MINUTE = 60

    /** Which of the two big buttons a state enables. */
    data class CallButtons(val answer: Boolean, val hangUp: Boolean) {
        companion object {
            val NONE = CallButtons(answer = false, hangUp = false)
        }
    }

    fun buttons(state: HfpState?): CallButtons = when (state) {
        HfpState.INCOMING_CALL -> CallButtons(answer = true, hangUp = true)
        HfpState.OUTGOING_CALL, HfpState.ACTIVE_CALL -> CallButtons(answer = false, hangUp = true)
        else -> CallButtons.NONE
    }

    fun stateLabel(state: HfpState?): String = when (state) {
        null -> "No signal"
        HfpState.INITIALISING -> "Starting"
        HfpState.READY -> "No phone"
        HfpState.CONNECTING -> "Connecting"
        HfpState.CONNECTED -> "Connected"
        HfpState.OUTGOING_CALL -> "Calling"
        HfpState.INCOMING_CALL -> "Incoming call"
        HfpState.ACTIVE_CALL -> "In call"
    }

    /** Placing a call needs a connected, idle phone; the dial pad is inert otherwise. */
    fun canDial(state: HfpState?, number: String): Boolean =
        state == HfpState.CONNECTED && isDialable(number)

    /** `+` leading only, then digits / `*` / `#`, 1..[MAX_DIAL_LENGTH] characters. */
    fun isDialable(number: String): Boolean {
        if (number.isEmpty() || number.length > MAX_DIAL_LENGTH) {
            return false
        }
        val body = number.removePrefix(TRUNK_PREFIX.toString())
        return body.isNotEmpty() && body.all { it in DIAL_CHARS }
    }

    /** Append one key press; anything the number cannot take is dropped, not appended. */
    fun append(number: String, key: Char): String {
        if (number.length >= MAX_DIAL_LENGTH) {
            return number
        }
        if (key == TRUNK_PREFIX) {
            return if (number.isEmpty()) TRUNK_PREFIX.toString() else number
        }
        return if (key in DIAL_CHARS) number + key else number
    }

    fun backspace(number: String): String = number.dropLast(1)

    /** `mm:ss`; hours roll into minutes (a 90-minute call reads `90:00`). */
    fun timer(seconds: Int): String {
        val clamped = seconds.coerceAtLeast(0)
        val min = clamped / SECONDS_PER_MINUTE
        val sec = clamped % SECONDS_PER_MINUTE
        return "%02d:%02d".format(min, sec)
    }

    /** The other party as the screen names them: name, else number, else nothing. */
    fun party(state: VendorBtState): String? =
        state.callerName?.takeIf { it.isNotBlank() }
            ?: state.callerNumber?.takeIf { it.isNotBlank() }

    /**
     * Status-bar chip text while a call is up, null otherwise. The timer wins over the state
     * word once it is ticking; the party is appended when known.
     */
    fun callChip(state: VendorBtState): String? {
        if (state.inCall != true) {
            return null
        }
        val head = state.speakingSec?.let(::timer) ?: stateLabel(state.hfp)
        val party = party(state) ?: return head
        return "$head · $party"
    }
}
