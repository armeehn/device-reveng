package com.ripostelabs.carlauncher.carlib

/**
 * Main volume as the gateway pushes it on `MCU_MSG_MAIL_VOL` (`EventService.java:3105-3125`,
 * `notifyMainVolChange`): int extra `MCU_MSG_MAIL_VOL_VAL` = `(mute ? 0x80 : 0) | volume`, plus
 * boolean `MCU_MSG_SHOW_VOL_WND` = whether the vendor would pop its volume window. Sent after
 * every MCU volume (frame 0x79) or mute (frame 0x78) report, so it replaces polling `getMainVolval`.
 */
data class VolumeReading(
    /** 0..[CarService.MAX_VOLUME] as the MCU reports it. */
    val level: Int,
    val muted: Boolean,
    val showWindow: Boolean,
    val atMs: Long,
) {
    companion object {
        /** Bit 7 of `MCU_MSG_MAIL_VOL_VAL` is the mute flag; the low 7 bits are the level. */
        private const val MUTE_BIT = 0x80
        private const val LEVEL_MASK = 0x7F

        /** Decode the packed `MCU_MSG_MAIL_VOL_VAL` int. */
        fun fromMailVol(raw: Int, showWindow: Boolean, atMs: Long): VolumeReading = VolumeReading(
            level = raw and LEVEL_MASK,
            muted = raw and MUTE_BIT != 0,
            showWindow = showWindow,
            atMs = atMs,
        )
    }
}
