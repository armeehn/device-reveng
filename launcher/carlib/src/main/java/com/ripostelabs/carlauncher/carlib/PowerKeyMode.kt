package com.ripostelabs.carlauncher.carlib

/**
 * What a short POWER press does (RAV4-156), as `Sys_Power_key_set` stores it: 0 blacks the
 * screen, 1 enters standby (onPowerClicked, EventService.java:13824-13880).
 *
 * Standby is our default. Stock's hard key always runs powerOff (EventService.java:2695-2698);
 * the SysVar only steers its soft Power button, so a blank row keeps the key as it was.
 */
enum class PowerKeyMode(val raw: Int) {
    SCREEN_OFF(0),
    STANDBY(1),
    ;

    companion object {
        /** The stored SysVar value; blank or unknown is [STANDBY]. */
        fun of(raw: String?): PowerKeyMode =
            entries.firstOrNull { it.raw.toString() == raw?.trim() } ?: STANDBY

        /** The ICarService int, which is the same raw value. */
        fun ofRaw(raw: Int): PowerKeyMode? = entries.firstOrNull { it.raw == raw }
    }
}
