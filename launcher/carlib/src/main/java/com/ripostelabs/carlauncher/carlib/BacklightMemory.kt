package com.ripostelabs.carlauncher.carlib

import android.content.Context

/**
 * BacklightMemory — the two `2E` backlight targets across boots, which the vendor kept as the
 * `Set_Day_Light` / `Set_Night_Light` rows of its settings provider.
 *
 *     slider / DIM key ──▶ remember() ──▶ next boot: StartupConfig ──▶ 2E day night 80 200
 *     71 headlamp bit  ──▶ lampsOn ─────▶ withLevel(): which side a single level lands on
 *
 * The MCU picks day or night from its own headlamp input, so both targets ride every frame
 * (sendBacklight, EventService.java:9639-9648) and nothing is re-sent when the lamps toggle.
 * The bit is still tracked here because a one-level slider has to change the target the panel
 * is showing now and leave the other alone (adjustBLLevel(boolean), :7971-7987).
 */
class BacklightMemory(private val store: Store) : McuOwner.Listener {

    /** Two integer rows; [Prefs] is the SharedPreferences one, tests use a map. */
    interface Store {
        fun getInt(key: String, default: Int): Int

        fun putInt(key: String, value: Int)
    }

    class Prefs(context: Context) : Store {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        override fun getInt(key: String, default: Int): Int = prefs.getInt(key, default)

        override fun putInt(key: String, value: Int) {
            prefs.edit().putInt(key, value).apply()
        }
    }

    /** The two MCU targets, each 0..[CarService.BACKLIGHT_MAX]. */
    data class Targets(val day: Int, val night: Int)

    @Volatile
    private var lampsOn = false

    fun targets(): Targets = Targets(
        day = store.getInt(KEY_DAY, DEFAULT_DAY),
        night = store.getInt(KEY_NIGHT, DEFAULT_NIGHT),
    )

    fun remember(day: Int, night: Int) {
        store.putInt(KEY_DAY, day)
        store.putInt(KEY_NIGHT, night)
    }

    /** The headlamp line as of the last `71` (mLAMPConnected, EventService.java:2333-2335). */
    fun lampsOn(): Boolean = lampsOn

    /** [level] on the side the panel shows now, the other side as remembered. Writes nothing. */
    fun withLevel(level: Int): Targets {
        val current = targets()
        if (lampsOn) {
            return current.copy(night = level)
        }

        return current.copy(day = level)
    }

    /** [base] with the remembered targets, for the boot and wake sequences. */
    fun config(base: McuOwnerProtocol.StartupConfig): McuOwnerProtocol.StartupConfig {
        val current = targets()
        return base.copy(backlightDay = current.day, backlightNight = current.night)
    }

    override fun onSysEvent(event: McuOwnerProtocol.SysEvent) {
        lampsOn = event.illumination
    }

    private companion object {
        const val PREFS = "mcu_backlight"
        const val KEY_DAY = "day"
        const val KEY_NIGHT = "night"

        /** setRecordDefaultValue("Set_Day_Light", "20") / ("Set_Night_Light", "8"), EventService.java:6502-6503. */
        const val DEFAULT_DAY = 20
        const val DEFAULT_NIGHT = 8
    }
}
