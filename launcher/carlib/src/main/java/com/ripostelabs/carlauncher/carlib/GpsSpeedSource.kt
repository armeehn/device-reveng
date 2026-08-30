package com.ripostelabs.carlauncher.carlib

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.roundToInt

/**
 * v2.5 — GpsSpeedSource: the first real road-speed source for [CarEvents.speedKmh].
 *
 * The vendor gateway never publishes a numeric speed: `SHOW_CAR_SPEED_EVENT` is a show/hide UI
 * toggle carrying no value (CAR_API §1.3), so `speedKmh` shipped as a stubbed -1 through v2.4.1
 * and the LAUNCHER_DESIGN §1.4 "no text entry while moving" rules were unenforceable. GPS speed
 * is the one source a *normal* app can read, so it is the one we start from; decoding the CAN
 * bulk frame stays the preferred upgrade once its layout is confirmed on-device.
 *
 * Two behaviours matter because a safety gate is built on top of this:
 *
 *  * **Smoothing.** A raw GPS speed jitters by a few km/h between fixes even at a standstill.
 *    An exponential moving average stops a parked car from flickering over the moving threshold.
 *  * **Staleness is not zero.** GPS drops out in a garage, a tunnel, or a covered car park. A fix
 *    that stopped arriving is *unknown*, never "0 km/h" — reporting a stationary car there would
 *    unlock text entry precisely when we have lost the ability to tell. After [STALE_AFTER_MS]
 *    with no fix the value reverts to [SPEED_UNKNOWN].
 *
 * All callbacks land on the main looper, so [onSpeed] can drive Compose state directly.
 */
class GpsSpeedSource(
    context: Context,
    private val onSpeed: (Int) -> Unit,
) {

    companion object {
        private const val TAG = "GpsSpeedSource"

        /** No trustworthy fix. Same sentinel [CarEvents.speedKmh] used while stubbed. */
        const val SPEED_UNKNOWN = -1

        /** The receiver on this head unit fixes at ~1 Hz; asking for faster just burns power. */
        private const val MIN_INTERVAL_MS = 1000L
        private const val MIN_DISTANCE_M = 0f

        /**
         * Weight of each new fix in the exponential moving average. At 0.5 the average halves
         * GPS jitter yet still reaches ~95% of a step change within four fixes (~4 s), so the
         * gate closes shortly after the car actually pulls away.
         */
        private const val SMOOTHING = 0.5f

        /** No fix within this window means unknown, not stationary. */
        private const val STALE_AFTER_MS = 5_000L

        /** Location reports metres per second; the car speaks km/h. */
        private const val MS_TO_KMH = 3.6f
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    /** Running average, or NaN before the first usable fix. */
    private var smoothed = Float.NaN
    private var started = false

    /**
     * Fires when fixes stop arriving. Resets the average too: when GPS returns after a dropout
     * the car may be at a completely different speed, and averaging across the gap would drag
     * the new reading toward a stale one.
     */
    private val staleCheck = Runnable {
        smoothed = Float.NaN
        onSpeed(SPEED_UNKNOWN)
    }

    private val listener = LocationListener { location -> onFix(location) }

    /**
     * Begin listening. A no-op when already started, when the location permission has not been
     * granted, or when the platform has no GPS provider — in every one of those cases speed
     * simply stays [SPEED_UNKNOWN] and the caller degrades to its unknown-speed behaviour.
     */
    fun start() {
        if (started) {
            return
        }
        if (!hasLocationPermission()) {
            Log.i(TAG, "ACCESS_FINE_LOCATION not granted — speed stays unknown")
            return
        }

        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (manager == null) {
            Log.w(TAG, "no LocationManager — speed stays unknown")
            return
        }

        // requestLocationUpdates throws if the provider does not exist on this build.
        val requested = runCatching {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_INTERVAL_MS,
                MIN_DISTANCE_M,
                listener,
                Looper.getMainLooper(),
            )
        }.onFailure { Log.w(TAG, "requestLocationUpdates failed", it) }.isSuccess

        if (!requested) {
            return
        }

        started = true
        // Nothing has arrived yet, so arm the staleness timer straight away: without this a unit
        // that never gets a fix would sit on whatever the previous session left behind.
        handler.postDelayed(staleCheck, STALE_AFTER_MS)
        Log.i(TAG, "GPS speed source started")
    }

    fun stop() {
        if (!started) {
            return
        }
        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        runCatching { manager?.removeUpdates(listener) }
        handler.removeCallbacks(staleCheck)
        smoothed = Float.NaN
        started = false
    }

    private fun onFix(location: Location) {
        handler.removeCallbacks(staleCheck)
        handler.postDelayed(staleCheck, STALE_AFTER_MS)

        // A fix can be positionally valid yet carry no velocity (cold start, single-satellite
        // recovery). That tells us nothing about motion, so leave the last verdict standing.
        if (!location.hasSpeed()) {
            return
        }

        val kmh = location.speed * MS_TO_KMH
        smoothed = if (smoothed.isNaN()) kmh else smoothed + SMOOTHING * (kmh - smoothed)
        onSpeed(smoothed.roundToInt().coerceAtLeast(0))
    }

    private fun hasLocationPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
