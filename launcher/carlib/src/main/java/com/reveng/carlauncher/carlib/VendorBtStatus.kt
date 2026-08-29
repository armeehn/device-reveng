package com.reveng.carlauncher.carlib

/**
 * v0.4.9 — Bluetooth status as the VENDOR's bt module reports it.
 *
 * The vendor launcher's BT chip listens to the unprotected `com.szchoiceway.btsuite.HBCP_EVT_*`
 * broadcasts (CUSTOMERUI_NOTES §3e/§4): the head unit's phone Bluetooth runs through that
 * module, so the Android `BluetoothManager` may see nothing while a phone is connected. This
 * state lets our chip agree with the vendor stack when — and only when — its events actually
 * arrive.
 *
 * `powered`/`connected` stay null until an event both arrived AND decoded unambiguously;
 * [lastEventMs] is 0 until the first HBCP broadcast of any shape. A consumer must treat
 * null / stale as "no vendor signal" and keep its own source.
 */
data class VendorBtState(
    /** Vendor BT power on/off, or null when never decoded. */
    val powered: Boolean? = null,
    /** A device connected per the vendor module, or null when never decoded. */
    val connected: Boolean? = null,
    /** Arrival time of the last HBCP_EVT_* broadcast of ANY shape; 0 = none this session. */
    val lastEventMs: Long = 0L,
)

/**
 * Pure decoder for HBCP_EVT_* payloads. The exact extra keys and value encodings are
 * UNCONFIRMED (the decompile names only the actions' categories: power / connected-device /
 * HSHF), so every rule below refuses to guess:
 *
 *  - only 0/1 (int/byte/boolean) status values map to a boolean; any other value is ignored,
 *  - a non-blank device name/address only ever raises `connected`, never lowers it,
 *  - an undecodable event still stamps [VendorBtState.lastEventMs], proving the channel is
 *    alive without fabricating a reading.
 */
internal object VendorBtDecode {

    /** Substrings that pick the status extra out of an unknown key set, best first. */
    private val STATUS_KEY_HINTS = arrayOf("status", "state", "on")

    /** Key substrings that mark a device-identity extra (name/address). */
    private val DEVICE_KEY_HINTS = arrayOf("name", "addr", "mac", "device")

    /** Fold one HBCP broadcast into [prev]. [extras] is the raw bundle snapshot. */
    fun apply(
        prev: VendorBtState,
        action: String,
        extras: Map<String, Any?>,
        atMs: Long,
    ): VendorBtState {
        var powered = prev.powered
        var connected = prev.connected
        val flag = statusFlag(extras)

        when {
            action.contains("POWER") -> if (flag != null) {
                powered = flag
                // Power off implies nothing can stay connected; power on implies nothing.
                if (!flag) connected = false
            }

            action.contains("CONNECT") || action.contains("DEVICE") ||
                action.contains("HSHF") -> when {
                flag != null -> connected = flag
                hasDeviceIdentity(extras) -> connected = true
            }
        }

        return VendorBtState(powered = powered, connected = connected, lastEventMs = atMs)
    }

    /**
     * Find a 0/1 status among the extras: a hinted key first, else a SINGLE numeric extra
     * (two candidates = ambiguous = null). Values other than 0/1 are treated as unknown.
     */
    private fun statusFlag(extras: Map<String, Any?>): Boolean? {
        for (hint in STATUS_KEY_HINTS) {
            val hit = extras.entries.firstOrNull { it.key.contains(hint, ignoreCase = true) }
                ?: continue
            return asFlag(hit.value)
        }
        val numeric = extras.values.filter { it is Number || it is Boolean }
        return if (numeric.size == 1) asFlag(numeric[0]) else null
    }

    private fun asFlag(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is Number -> when (value.toInt()) {
            1 -> true
            0 -> false
            else -> null
        }
        else -> null
    }

    private fun hasDeviceIdentity(extras: Map<String, Any?>): Boolean =
        extras.any { (key, value) ->
            value is String && value.isNotBlank() &&
                DEVICE_KEY_HINTS.any { key.contains(it, ignoreCase = true) }
        }
}
