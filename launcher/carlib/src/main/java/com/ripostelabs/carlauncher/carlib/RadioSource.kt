package com.ripostelabs.carlauncher.carlib

/**
 * RadioSource — the vendor's `mValidMode` discipline for the tuner, on the owner path.
 *
 * ── Where this sits ─────────────────────────────────────────────────────────────────────────────
 *
 *     RadioScreen ──▶ CarService.claimRadio()   ──▶ claim()   ──▶ McuOwner.setMode(RADIO)  `01 01`, ACK awaited
 *                     CarService.releaseRadio() ──▶ release() ──▶ McuOwner.setMode(NULL)   `01 63`
 *
 * On a stock or 0.1 slot the gateway keeps this state and the launcher only asks; on Riposte
 * OS 0.2 there is no gateway, so the launcher must select the source itself or the tuner
 * answers every getter while the amplifier stays on the last source (a screen that seeks but
 * never plays).
 *
 * Claiming is what the vendor radio's `RadioService.sendRadioMode` amounts to inside the
 * gateway: `setCurModeCallback` records SRC_RADIO as the valid mode before any ACK
 * (EventService.java:8830-8842), then `sendMode(SRC_RADIO, true)` waits for MODE_ACK
 * (:3933-3952). Hence [held] is set before the send and stays set when the ACK is late.
 *
 * Leaving is `exitCurMode(SRC_RADIO)` (:8917-8945): a no-op unless the valid mode is still
 * ours, otherwise SRC_NULL on the wire. Not HOME: SRC_HOME(43) exists in `eSrcMode`
 * (EventUtils.java:2034) but nothing in EventService ever sends it.
 */
class RadioSource(private val select: (McuOwnerProtocol.Mode) -> Boolean) {

    /** Mirrors `mValidMode == SRC_RADIO`. */
    @Volatile
    var held: Boolean = false
        private set

    /** Take the tuner source. Returns the MCU's ACK; the source is held either way. */
    fun claim(): Boolean {
        held = true
        return select(McuOwnerProtocol.Mode.RADIO)
    }

    /** Hand the source back. False when it was never ours, in which case nothing is sent. */
    fun release(): Boolean {
        if (!held) {
            return false
        }

        held = false
        select(McuOwnerProtocol.Mode.NULL)
        return true
    }
}
