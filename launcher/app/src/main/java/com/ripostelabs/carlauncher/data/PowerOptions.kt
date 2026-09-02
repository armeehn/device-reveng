package com.ripostelabs.carlauncher.data

/**
 * Value domains of the power / sleep SysVars, as the vendor settings app writes them.
 *
 * Sources: `com.szchoiceway.settings` `ItemTextRightCheckBoxView.java:450-486` (ACC-on delay
 * 0s..7s), `:644-692` (screensaver / close-screen 0/60/300/600/1800 s) and the gateway
 * `EventService.java:9361-9371` (`SYS_SLEEP_TIME` 1/2/3 -> MCU 960/1440/2880, else 480) and
 * `:3169-3172` (`Sys_Acc_Delay` seconds sent as mm:ss).
 *
 * Every option list is `raw -> label`; the screen shows the label and writes the raw value.
 * [rawOrNull] is the guard the screen uses: a stored value outside the domain is shown raw and
 * read-only instead of being coerced, so a wrong assumption never overwrites the vendor's value.
 */
object PowerOptions {

    /** `SET_ACC_ON_DELAY`: whole seconds, packed `& 7` into MCU frame 0x10. */
    val ACC_ON_DELAY_SECONDS: IntRange = 0..7

    /**
     * `Sys_Acc_Delay`: seconds. No vendor UI writes it; the MCU frame carries minutes and seconds
     * as separate bytes, so the ceiling here is the launcher's choice, not the vendor's. UNVERIFIED.
     */
    val ACC_DELAY_SECONDS: IntRange = 0..MAX_ACC_DELAY_SECONDS
    private const val MAX_ACC_DELAY_SECONDS = 600
    private const val SECONDS_PER_MINUTE = 60

    /**
     * `SYS_SLEEP_TIME`: an enum, not minutes. 1 -> 960, 2 -> 1440, 3 -> 2880 on the MCU side,
     * default 2. The unit of those MCU values is UNVERIFIED, so the labels state only the order.
     */
    const val SLEEP_TIME_DEFAULT = 2
    val SLEEP_TIME: List<Pair<Int, String>> = listOf(
        1 to "1 (shortest)",
        2 to "2 (default)",
        3 to "3 (longest)",
    )

    /** `SYS_AUTO_START_SCREENSAVER_TIME` / `SYS_AUTO_START_CLOSE_SCREEN_TIME`: seconds, 0 = never. */
    const val SCREEN_TIMEOUT_NEVER = 0
    val SCREEN_TIMEOUT: List<Pair<Int, String>> = listOf(
        SCREEN_TIMEOUT_NEVER to "Never",
        60 to "1 min",
        300 to "5 min",
        600 to "10 min",
        1800 to "30 min",
    )

    /** The customer type whose gateway acts on the close-screen timer (`EventService.java:14394`). */
    const val CLOSE_SCREEN_CUSTOMER_TYPE = 69

    /** Parse a stored value against [options]; null when blank, non-numeric or not an option. */
    fun rawOrNull(stored: String, options: List<Pair<Int, String>>): Int? {
        val value = stored.trim().toIntOrNull() ?: return null
        if (options.none { it.first == value }) {
            return null
        }
        return value
    }

    /** Parse a stored value against [range]; null when blank, non-numeric or outside it. */
    fun rawOrNull(stored: String, range: IntRange): Int? {
        val value = stored.trim().toIntOrNull() ?: return null
        if (value !in range) {
            return null
        }
        return value
    }

    /** Seconds -> "m:ss", the shape the gateway sends `Sys_Acc_Delay` to the MCU in. */
    fun minutesSeconds(seconds: Int): String {
        val m = seconds / SECONDS_PER_MINUTE
        val s = seconds % SECONDS_PER_MINUTE
        return "$m:${s.toString().padStart(2, '0')}"
    }
}
