package com.ripostelabs.carlauncher.carlib

import android.content.Context

/**
 * RadioMemory — the last station and preset list across boots.
 *
 *     MCU ──73──▶ onRadio ──▶ saved here ──▶ next boot: RadioStateHolder.seed ──▶ tuner screen
 *
 * The vendor keeps them in the gateway's `mRadio*` fields, a process that never restarts, and
 * seeds the preset list from `initRadioZone` (EventService.java:6484) before the MCU has said
 * a word. Ours die with the launcher, so the tuner screen would open on "no tuner" until the
 * MCU repeats its state, which it only does on change.
 */
class RadioMemory(context: Context) : McuOwner.Listener {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** `KEY_RADIO_ZONE_SETTINGS`: 0 (Europe) until Settings says otherwise, as on the vendor's SysVar. */
    fun zone(): Int = prefs.getInt(KEY_ZONE, DEFAULT_ZONE)

    /** What to show before the MCU speaks: the remembered station, or the zone's defaults. */
    fun restore(): RadioState = restore(
        zone = zone(),
        band = prefs.takeIf { it.contains(KEY_BAND) }?.getInt(KEY_BAND, 0),
        freq = prefs.takeIf { it.contains(KEY_FREQ) }?.getInt(KEY_FREQ, 0),
        stations = prefs.getString(KEY_STATIONS, null),
    )

    override fun onRadio(event: McuOwnerProtocol.RadioEvent) {
        val edit = prefs.edit()
        when (event) {
            is McuOwnerProtocol.RadioEvent.Band -> event.band?.let { edit.putInt(KEY_BAND, it) }
            is McuOwnerProtocol.RadioEvent.Frequency -> edit.putInt(KEY_FREQ, event.freq)
            is McuOwnerProtocol.RadioEvent.FreqList -> {
                val list = (decodeStations(prefs.getString(KEY_STATIONS, null) ?: "")
                    ?: RadioZone.of(zone()).defaultStations).toMutableList()
                list[event.index] = event.freq
                edit.putString(KEY_STATIONS, encodeStations(list))
            }
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
        private const val DEFAULT_ZONE = 0
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
