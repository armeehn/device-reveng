package com.ripostelabs.carlauncher.data

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ripostelabs.carlauncher.carlib.McuOwner
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * Sets the system clock from the first real GPS fix of each wake (Riposte OS 0.2).
 *
 * The car has no cell network and no NTP, so the MCU's RTC is the only clock offline and it
 * drifts (a day behind on 2026-09-22). The vendor gateway fixes that from GPS in its
 * LocationChangeListener (EventService.java:1187-1211): once a fix arrives with more than three
 * satellites used (`mGpsSatelliteFix`, :1216-1223) it writes `location.getTime()` to the clock,
 * then sets `mIsGPSGetLocation` so neither a later fix nor the MCU's `83` frame
 * (onCmdSysRTCTimeEvt, :3042) overwrites it. ACC wake clears the flag (setAccWakeUp, :3440).
 *
 *     LocationManager ─▶ onFix ─▶ shouldSet ─▶ AlarmManager.setTime ─▶ McuClock.onGpsClock
 *          GnssStatus ─▶ satsInFix ─┘                                  (MCU RTC gets it too)
 *
 * The vendor adds an hour when its `Sys_dst_Set` record is on (:1199); the image sets a real
 * zone, so DST is the zone's business here and the fix stays UTC.
 */
class GpsClock(
    private val context: Context,
    /** Runs after a GPS write: the clock is now trustworthy, so the MCU RTC gets it. */
    private val onClockSet: () -> Unit,
) : McuOwner.Listener {

    private var set = false
    private var satsInFix = 0
    private var started = false

    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            satsInFix = (0 until status.satelliteCount).count { status.usedInFix(it) }
        }
    }

    private val listener = LocationListener { location -> onFix(location) }

    /** A no-op without the location grant or a GPS provider: the clock then waits for the MCU. */
    fun start() {
        if (started) {
            return
        }
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        if (granted != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "ACCESS_FINE_LOCATION not granted, clock stays on the MCU RTC")
            return
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return

        // requestLocationUpdates throws when the build has no GPS provider.
        val requested = runCatching {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_INTERVAL_MS,
                MIN_DISTANCE_M,
                listener,
                Looper.getMainLooper(),
            )
            manager.registerGnssStatusCallback(gnss, Handler(Looper.getMainLooper()))
        }.onFailure { Log.w(TAG, "GPS clock source unavailable", it) }.isSuccess
        started = requested
    }

    fun stop() {
        if (!started) {
            return
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        runCatching {
            manager?.removeUpdates(listener)
            manager?.unregisterGnssStatusCallback(gnss)
        }
        started = false
    }

    /** ACC wake clears the once-per-wake flag (setAccWakeUp, EventService.java:3440). */
    override fun onWake() {
        set = false
    }

    private fun onFix(location: Location) {
        val gpsMs = location.time
        if (!shouldSet(satsInFix, gpsMs, gpsMs - System.currentTimeMillis(), set)) {
            return
        }

        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { alarms.setTime(gpsMs) }
            .onSuccess {
                set = true
                Log.i(TAG, "clock set from GPS: ${Instant.ofEpochMilli(gpsMs)} ($satsInFix sats)")
                onClockSet()
            }
            .onFailure { Log.w(TAG, "clock not set from GPS", it) }
    }

    companion object {
        private const val TAG = "GpsClock"

        /** The receiver fixes at ~1 Hz; the vendor asks for the same (:279). */
        private const val MIN_INTERVAL_MS = 1_000L
        private const val MIN_DISTANCE_M = 0f

        /** The vendor's `mGpsSatelliteFix`: more than three satellites used in the fix (:1223). */
        const val MIN_SATS_IN_FIX = 4

        /** The vendor's floor for a clock it believes (SysDevStateThread, :10900). */
        const val MIN_YEAR = 2015

        /** A 32-bit `time_t` ceiling the vendor keeps (:1202); the kernel's RTC driver shares it. */
        private const val MAX_EPOCH_S = Int.MAX_VALUE.toLong()

        /** Once per wake, from a fix worth believing, when the clock is off by more than the floor. */
        fun shouldSet(satsInFix: Int, gpsTimeMs: Long, driftMs: Long, alreadySet: Boolean): Boolean {
            if (alreadySet || satsInFix < MIN_SATS_IN_FIX) {
                return false
            }
            val year = Instant.ofEpochMilli(gpsTimeMs).atZone(ZoneOffset.UTC).year
            if (year < MIN_YEAR || gpsTimeMs / 1_000L >= MAX_EPOCH_S) {
                return false
            }
            return abs(driftMs) > McuClock.DRIFT_FLOOR_MS
        }
    }
}
