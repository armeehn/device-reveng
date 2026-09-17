package com.ripostelabs.carlauncher.carlib

/**
 * SysVarMirror — the SysVar rows the suite still reads, kept on Riposte OS 0.2.
 *
 *     McuOwner ──▶ FanOut ──┬─▶ VendorBroadcastReemitter ─▶ MCU_MSG_BRAKE_EVT   (the edge)
 *                           └─▶ SysVarMirror ─────────────▶ onChange(key, value) (the value)
 *                                                                   │
 *                                    com.ripostelabs.video BrakeGate ◀── provider query
 *
 * The vendor's players learn *that* the brake moved from the broadcast and *what* it is from
 * `Sys_CurBreakSate` in eventcenter's SysVarProvider. On 0.2 the provider is gone with
 * eventcenter, and `com.ripostelabs.video` read "no row" as "no gate" (RAV4-98). This listener
 * recomputes the row from the owner's `71` SYS_EVENT with the vendor's rule
 * (`HANDLER_BREAK_EVENT`, EventService.java:536-541):
 *
 *     "1" (cover the picture)  when detection is on and the brake line is NOT connected
 *     "0" (open)               when the brake is connected, or detection is off
 *
 * `Set_BreakDetected` was a vendor setting defaulting to off; on the owner path the launcher is
 * the policy: [detect] is read on every SYS_EVENT (MainActivity hands it the parked-only motion
 * gate, the one opt-out safety switch) and defaults to on because a player that never covers
 * itself in a moving car is the wrong failure. Rows are announced on change only, so a caller
 * can notify observers from [onChange] without a storm.
 */
class SysVarMirror(
    private val detect: () -> Boolean = { true },
    private val onChange: (key: String, value: String) -> Unit,
) : McuOwner.Listener {

    private val rows = mutableMapOf<String, String>()

    @Volatile
    private var last: McuOwnerProtocol.SysEvent? = null

    /** The row's value, or null until the first SYS_EVENT has been seen. */
    fun get(key: String): String? = synchronized(rows) { rows[key] }

    override fun onSysEvent(event: McuOwnerProtocol.SysEvent) {
        last = event
        put(KEY_CUR_BRAKE_STATE, brakeState(detect(), event.brake))
    }

    /**
     * Recompute from the last SYS_EVENT: the MCU reports `71` on change, so a [detect] flip
     * would otherwise wait for the next brake edge. Nothing until the first event.
     */
    fun refresh() {
        last?.let(::onSysEvent)
    }

    private fun put(key: String, value: String) {
        val moved = synchronized(rows) { rows.put(key, value) != value }
        if (!moved) {
            return
        }
        onChange(key, value)
    }

    companion object {
        /** `SysProviderOpt.SYS_CUR_BREAK_STATE_KEY`, misspelling included: the suite matches it byte for byte. */
        const val KEY_CUR_BRAKE_STATE = "Sys_CurBreakSate"

        const val BRAKE_GATED = "1"
        const val BRAKE_OPEN = "0"

        /** The vendor's rule, pure. [connected] is SYS_EVENT bit 0x04 (handbrake on). */
        fun brakeState(detect: Boolean, connected: Boolean): String =
            if (detect && !connected) BRAKE_GATED else BRAKE_OPEN
    }
}
