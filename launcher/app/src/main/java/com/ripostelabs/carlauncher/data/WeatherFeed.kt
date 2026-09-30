package com.ripostelabs.carlauncher.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import kotlin.math.roundToInt

/** Temperature unit the weather app fetched in. It converts, the launcher only labels. */
enum class TempUnit(val code: String) { CELSIUS("C"), FAHRENHEIT("F") }

/** Coarse sky state for the home card glyph, folded from a WMO weather code. */
enum class Sky { CLEAR, CLOUDY, FOG, RAIN, SNOW, STORM }

/** One reading from the suite weather app. Temperatures are in [unit]. */
data class WeatherNow(
    val temp: Double,
    val high: Double?,
    val low: Double?,
    val unit: TempUnit,
    val code: Int,
    val aqi: Int?,
    val place: String,
    val updatedMs: Long,
)

/**
 * RAV4-196 — the home card's read side of the suite weather app's last fetch.
 *
 * The weather app (`com.ripostelabs.weather`, rav4-apps) owns the network, the location and the
 * unit. It keeps its last Open-Meteo result in a one-row read-only provider:
 *
 * ```
 *   content://com.ripostelabs.weather.current/current
 *   temp REAL | high REAL | low REAL | unit TEXT (C/F) | code INT (WMO) | aqi INT (US AQI)
 *   place TEXT | updated INT (epoch ms)
 * ```
 *
 * No app, no row or a stale row all read as null, and the card shows the clock alone.
 */
object WeatherFeed {

    const val PACKAGE = "com.ripostelabs.weather"
    const val AUTHORITY = "com.ripostelabs.weather.current"
    val URI: Uri get() = Uri.parse("content://$AUTHORITY/current")

    const val COL_TEMP = "temp"
    const val COL_HIGH = "high"
    const val COL_LOW = "low"
    const val COL_UNIT = "unit"
    const val COL_CODE = "code"
    const val COL_AQI = "aqi"
    const val COL_PLACE = "place"
    const val COL_UPDATED = "updated"

    /** The app refreshes hourly. Six missed refreshes means no network, so hide the number. */
    const val MAX_AGE_MS = 6L * 3_600_000L

    /** A clock ahead of the reading by less than this is fine (GPS time sync lands late). */
    private const val FUTURE_SLACK_MS = 5L * 60_000L

    /** Build a reading from one row, looked up by column name. Null without a temperature. */
    fun parse(get: (String) -> Any?): WeatherNow? {
        val temp = number(get(COL_TEMP)) ?: return null

        return WeatherNow(
            temp = temp,
            high = number(get(COL_HIGH)),
            low = number(get(COL_LOW)),
            unit = TempUnit.entries.firstOrNull { it.code == get(COL_UNIT) } ?: TempUnit.CELSIUS,
            code = number(get(COL_CODE))?.toInt() ?: 0,
            aqi = number(get(COL_AQI))?.toInt(),
            place = get(COL_PLACE) as? String ?: "",
            updatedMs = number(get(COL_UPDATED))?.toLong() ?: 0L,
        )
    }

    /** True while the reading is recent enough to show as the weather outside now. */
    fun isFresh(w: WeatherNow, nowMs: Long): Boolean {
        val age = nowMs - w.updatedMs
        return age in -FUTURE_SLACK_MS..MAX_AGE_MS
    }

    /** WMO code table (Open-Meteo docs): 0-1 clear, 45/48 fog, 5x-6x/8x rain, 7x snow, 9x storm. */
    fun sky(code: Int): Sky = when (code) {
        0, 1 -> Sky.CLEAR
        45, 48 -> Sky.FOG
        in 51..67, in 80..82 -> Sky.RAIN
        in 71..77, 85, 86 -> Sky.SNOW
        in 95..99 -> Sky.STORM
        else -> Sky.CLOUDY
    }

    /** "15°": whole degrees, the unit letter lives on the card once. */
    fun degrees(value: Double): String = "${value.roundToInt()}°"

    /** Query the provider. Null when the app is missing, empty or throws. Call off the main thread. */
    fun read(context: Context): WeatherNow? = runCatching {
        context.contentResolver.query(URI, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) {
                return@use null
            }
            parse { name -> cell(c, name) }
        }
    }.getOrNull()

    private fun cell(c: Cursor, name: String): Any? {
        val i = c.getColumnIndex(name)
        if (i < 0 || c.isNull(i)) {
            return null
        }
        return when (c.getType(i)) {
            Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
            else -> c.getString(i)
        }
    }

    private fun number(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }
}
