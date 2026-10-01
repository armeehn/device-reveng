package com.ripostelabs.carlauncher.carlib

import android.content.Context

/**
 * RadioMemory — the last station and preset list across boots.
 *
 *     MCU ──73──▶ onRadio ──▶ band, freq saved here ──▶ next boot: RadioStateHolder.seed ──▶ tuner screen
 *     RadioStateHolder.stationList ──▶ saveStations ──▶ the same seed
 *
 * The vendor keeps them in the gateway's `mRadio*` fields, a process that never restarts, and
 * seeds the preset list from `initRadioZone` (EventService.java:6484) before the MCU has said
 * a word. Ours die with the launcher, so the tuner screen would open on "no tuner" until the
 * MCU repeats its state, which it only does on change.
 */
class RadioMemory(context: Context) : McuOwner.Listener {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** `KEY_RADIO_ZONE_SETTINGS`: North America (the car is in Canada) until Settings says otherwise. */
    fun zone(): Int = prefs.getInt(KEY_ZONE, DEFAULT_ZONE)

    /** Persist a region pick; the old zone's preset list no longer fits, so it goes. */
    fun setZone(zone: Int) {
        prefs.edit().putInt(KEY_ZONE, zone).remove(KEY_STATIONS).apply()
    }

    /** `SYS_RDS_OnOff`: off until Settings says otherwise, as on stock's first boot. */
    fun rds(): Boolean = prefs.getBoolean(KEY_RDS, DEFAULT_RDS)

    fun setRds(on: Boolean) {
        prefs.edit().putBoolean(KEY_RDS, on).apply()
    }

    /** What to show before the MCU speaks: the remembered station, or the zone's defaults. */
    fun restore(): RadioState = restore(
        zone = zone(),
        band = prefs.takeIf { it.contains(KEY_BAND) }?.getInt(KEY_BAND, 0),
        freq = prefs.takeIf { it.contains(KEY_FREQ) }?.getInt(KEY_FREQ, 0),
        stations = prefs.getString(KEY_STATIONS, null),
    )

    /**
     * The preset list as [RadioStateHolder] holds it, the one store: a long press and an
     * auto-store change it there, and a list report it refused never reaches the disk.
     */
    fun saveStations(list: List<Int>) {
        if (list.size != McuOwnerProtocol.RADIO_FREQ_LIST_SIZE) {
            return
        }

        prefs.edit().putString(KEY_STATIONS, encodeStations(list)).apply()
    }

    override fun onRadio(event: McuOwnerProtocol.RadioEvent) {
        val edit = prefs.edit()
        when (event) {
            is McuOwnerProtocol.RadioEvent.Band -> event.band?.let { edit.putInt(KEY_BAND, it) }
            is McuOwnerProtocol.RadioEvent.Frequency -> edit.putInt(KEY_FREQ, event.freq)
            else -> return
        }
        edit.apply()
    }

    companion object {
        private const val PREFS = "mcu_radio"
        private const val KEY_ZONE = "zone"
        private const val KEY_BAND = "band"
        private const val KEY_FREQ = "freq"
        private const val KEY_STATIONS = "stations"
        private const val KEY_RDS = "rds"
        const val DEFAULT_ZONE = RadioZone.NORTH_AMERICA
        const val DEFAULT_RDS = false
        private const val SEPARATOR = ","

        /** One string, 42 numbers: what fits a preference without a schema. */
        fun encodeStations(list: List<Int>): String = list.joinToString(SEPARATOR)

        /** Null unless the string is exactly 42 integers; a short or foreign list would misplace every preset. */
        fun decodeStations(text: String): List<Int>? {
            val parts = text.split(SEPARATOR)
            if (parts.size != McuOwnerProtocol.RADIO_FREQ_LIST_SIZE) {
                return null
            }
            val list = parts.map { it.toIntOrNull() ?: return null }
            return list
        }

        /**
         * The seed for [RadioStateHolder]: remembered values where present, otherwise the zone
         * defaults and the FM floor the vendor starts on (`mRadioCurFreq = 8750`, EventService.java:255).
         * [RadioState.updatedAt] stays 0: a memory is not a report.
         */
        fun restore(zone: Int, band: Int?, freq: Int?, stations: String?): RadioState {
            val plan = RadioZone.of(zone)
            return RadioState(
                zone = plan.id,
                band = band ?: 0,
                freq = freq ?: plan.fm.min,
                stationList = stations?.let(::decodeStations) ?: plan.defaultStations,
            )
        }
    }
}
