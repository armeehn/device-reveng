package com.ripostelabs.carlauncher.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager

/**
 * RAV4-169: night by the sun at the last GPS fix, or null when no fix was ever seen.
 *
 *     LocationManager last fix ──▶ cached in prefs ──▶ SunTimes.isNight(now, lat, lon)
 *                   (none) ──────▶ cached fix from an earlier drive
 *                   (none) ──────▶ null: the caller keeps its fixed clock window
 *
 * The cache matters at power-on in a garage: the receiver has no fix yet, but the car has
 * not moved far since the last drive, and the sun cares about tens of kilometres, not metres.
 */
object SunNight {

    private const val PREFS = "sun_night"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"

    fun isNight(context: Context, nowMs: Long): Boolean? {
        val (lat, lon) = lastFix(context) ?: return null
        return SunTimes.isNight(nowMs, lat, lon)
    }

    private fun lastFix(context: Context): Pair<Double, Double>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val live = liveFix(context)
        if (live != null) {
            prefs.edit()
                .putFloat(KEY_LAT, live.latitude.toFloat())
                .putFloat(KEY_LON, live.longitude.toFloat())
                .apply()
            return live.latitude to live.longitude
        }

        if (!prefs.contains(KEY_LAT) || !prefs.contains(KEY_LON)) {
            return null
        }
        return prefs.getFloat(KEY_LAT, 0f).toDouble() to prefs.getFloat(KEY_LON, 0f).toDouble()
    }

    /** The receiver's last fix; null without the grant, a provider or any fix this boot. */
    private fun liveFix(context: Context): Location? {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return runCatching {
            manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: manager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        }.getOrNull()
    }
}
