package com.ripostelabs.carlauncher.carlib

import android.content.Intent

/**
 * v0.4.3 — the raw CAN bulk-frame broadcast, for the on-device capture view.
 *
 * The gateway broadcasts the raw MCU 0xA5 passthrough on `MCU_MSG_CAN_ALL_INFO` and canbus2 its
 * 3-byte speed/RPM digest on `MCU_CAR_CAN_INFO` (CAR_API §1.3; `CAN_BASIC_EVT` is never sent).
 * This snapshots EVERY extra of the broadcast (name → readable value) plus the first `byte[]`
 * payload found, so an on-device capture can re-check both.
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
