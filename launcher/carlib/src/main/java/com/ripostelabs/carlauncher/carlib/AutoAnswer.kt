package com.ripostelabs.carlauncher.carlib

/**
 * RAV4-164 — answer a ringing phone without a touch, as stock's module could (`MF=3/1`,
 * EventManagerImplFEasycom.java:26-31). The driver picks the delay in Settings > Phone.
 *
 * ```
 *  lead call   null ──▶ INCOMING ──(delay)──▶ still INCOMING? ──▶ answer()
 *                          │ arm                 │ ACTIVE / gone: the driver acted, do nothing
 * ```
 *
 * Only a fresh ring arms: a WAITING call beside an active one is the driver's choice.
 */
enum class AutoAnswer(val delayMs: Long?) {
    OFF(null),
    NOW(0L),
    AFTER_3S(3_000L),
    AFTER_5S(5_000L);

    /** The delay to wait when [lead] is a ring [prev] was not; null = arm nothing. */
    fun arm(prev: Int?, lead: Int?): Long? {
        if (lead != HfCallState.INCOMING || prev == HfCallState.INCOMING) {
            return null
        }
        return delayMs
    }

    companion object {
        /** The armed answer goes ahead only on a ring still ringing, with no CarPlay call up. */
        fun due(lead: Int?, carPlayCall: Boolean): Boolean = lead == HfCallState.INCOMING && !carPlayCall

        /** A stored name; anything unknown is [OFF], never an answer the driver did not choose. */
        fun of(name: String?): AutoAnswer = entries.firstOrNull { it.name == name } ?: OFF
    }
}
