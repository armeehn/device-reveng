package com.reveng.carlauncher.carlib

/**
 * v0.4.9 — decode the two UNPROTECTED steering-wheel/panel key broadcasts (CAR_API §4 paths
 * 2 and 3) into the same canonical form as the protected `STEER_WHEEL_INFOR` path.
 *
 * Why this exists: CarEvents registered `ACTION_HOST_MCU_BUTTON_KEY` and `MCU_KEY_INFOR` but
 * never dispatched them, so a NON-ROOT install (where the protected broadcast is silently
 * never delivered and the root helper cannot run) had zero wheel control. These two actions
 * are exactly the fallback CAR_API §4 documents for a normal app.
 *
 * All functions are pure so a unit test reaches them without a Context.
 *
 * Decode rules are deliberately conservative (unknown → null → the event is dropped):
 *  - Key codes: `CAR_KEY_*` (1..14) pass through; the `MCU_KEY_SYS_*` panel codes translate
 *    to their CAR_KEY twin (HOME/MENU by name; ESC → BACK). Anything else is unknown.
 *  - `HostKeyStatus` (byte, "down/up" — CAR_API §1.3): only the 3=down / 4=up convention the
 *    same MCU documents for `STEER_WHEEL_INFOR_WPARAM` is accepted. Any other encoding is
 *    UNCONFIRMED, and a wrong guess here is worse than a dropped event — an inverted edge
 *    leaves a key "held" in KeyPump and fires a phantom long-press.
 */
internal object SwcFallback {

    /** A decoded key edge from an unprotected fallback broadcast. */
    data class Edge(val keyIndex: Int, val down: Boolean)

    // ---- MCU_KEY_SYS_* panel codes (CAR_API §4, EventUtils.java:1617-1620) ----
    const val MCU_KEY_SYS_HOME = 76
    const val MCU_KEY_SYS_MENU = 77
    const val MCU_KEY_SYS_ESC = 78

    /**
     * `ACTION_HOST_MCU_BUTTON_KEY`: `HostKeyWord` (int keycode) + `HostKeyStatus` (down/up).
     * @return the decoded edge, or null when either extra is unknown/undecodable.
     */
    fun hostKey(keyWord: Int?, status: Int?): Edge? {
        val index = normalizeKey(keyWord) ?: return null
        val down = when (status) {
            CarEvents.SWC_STATE_DOWN -> true
            CarEvents.SWC_STATE_UP -> false
            else -> return null // UNCONFIRMED encoding — drop rather than guess an edge
        }
        return Edge(index, down)
    }

    /**
     * `MCU_KEY_INFOR`: `MCU_KEY_VALUE` (int keycode), NO press state on this path — the caller
     * synthesises a complete down+up tap from the one broadcast.
     * @return the canonical CAR_KEY index, or null for an unknown code.
     */
    fun mcuKey(value: Int?): Int? = normalizeKey(value)

    /** Map a received keycode into the CAR_KEY_* space, or null if it is not one we know. */
    fun normalizeKey(code: Int?): Int? {
        if (code == null) {
            return null
        }
        return when (code) {
            in CarEvents.CAR_KEY_POWER..CarEvents.CAR_KEY_R_TUNE_R -> code
            MCU_KEY_SYS_HOME -> CarEvents.CAR_KEY_HOME
            MCU_KEY_SYS_MENU -> CarEvents.CAR_KEY_MENU
            MCU_KEY_SYS_ESC -> CarEvents.CAR_KEY_BACK // ESC ≈ Android Back on this panel
            else -> null
        }
    }

    /**
     * The canonical int-extra map an edge dedupes and dispatches under. Same key set as
     * [CarEvents.swcDedupeInts] keeps for the protected path — that identity is what lets
     * [ProtectedEventDedupe] drop the duplicate when the protected capture (root/system) and
     * this unprotected delivery co-arrive on a rooted unit.
     */
    fun canonicalInts(edge: Edge): Map<String, Int> = mapOf(
        CarEvents.EXTRA_SWC_LPARAM to edge.keyIndex,
        CarEvents.EXTRA_SWC_WPARAM to
            if (edge.down) CarEvents.SWC_STATE_DOWN else CarEvents.SWC_STATE_UP,
    )
}
