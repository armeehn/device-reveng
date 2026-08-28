package com.reveng.carlauncher.carlib

import android.content.Intent

/**
 * v0.4.3 — the raw CAN bulk-frame broadcast, for the on-device capture view.
 *
 * The gateway broadcasts a bulk CAN state frame on `CAN_BASIC_EVT` / `MCU_CAR_CAN_INFO` (CAR_API
 * §1.3; the `CAN_BASIC_EVT` receiver is **confirmed** in `EvtModel.java`) — the route to a real
 * speed reading that GPS cannot give indoors or at power-on. Unlike the radar frame, the *extra
 * key* carrying the payload is **not** documented, so this snapshots EVERY extra of the broadcast
 * (name → readable value) plus the first `byte[]` payload found, so an on-device capture discovers
 * both the key name and the byte layout at once.
 *
 * Plain class, not `data class`: same `StateFlow` conflation / `ByteArray.equals`-is-identity
 * reasoning as [RadarFrame].
 */
class CanFrame(
    val action: String,
    val extras: Map<String, String>,
    val bytes: ByteArray?,
    val atMs: Long,
) {
    companion object {
        /**
         * Snapshot every extra of [intent] as name → readable string, and pick up the first
         * `byte[]` extra as the candidate payload. Never assumes a key name.
         */
        fun from(intent: Intent, atMs: Long): CanFrame {
            val bundle = intent.extras
            val map = LinkedHashMap<String, String>()
            var bytes: ByteArray? = null
            if (bundle != null) {
                for (key in bundle.keySet()) {
                    @Suppress("DEPRECATION")
                    val value = runCatching { bundle.get(key) }.getOrNull()
                    if (value is ByteArray) {
                        if (bytes == null) bytes = value
                        map[key] = "byte[${value.size}] " +
                            value.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                    } else {
                        map[key] = value?.toString() ?: "null"
                    }
                }
            }
            return CanFrame(intent.action ?: "?", map, bytes, atMs)
        }
    }
}
