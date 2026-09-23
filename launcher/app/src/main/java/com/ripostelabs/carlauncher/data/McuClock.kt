package com.ripostelabs.carlauncher.data

import android.app.AlarmManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.ripostelabs.carlauncher.carlib.McuOwner
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

/**
 * Sets the system clock from the MCU's battery-backed RTC on the owner path (Riposte OS 0.2).
 *
 * The vendor does the same in onCmdSysRTCTimeEvt (EventService.java:3041-3058), but only
 * while it has neither a GPS fix nor a network: once online, NTP owns the clock. This mirrors
 * both halves of that gate ([GpsClock] reports the GPS half) and adds a drift floor so a frame
 * that agrees with the clock never writes it.
 *
 *     McuOwner ─▶ onRtc(83 yy MM dd HH mm ss) ─▶ policy ─▶ AlarmManager.setTime
 *
 * Needs android.permission.SET_TIME, a privileged permission the OS build allowlists.
 */
class McuClock(
    private val context: Context,
    /** Writes `13 yy MM dd HH mm ss` to the MCU; null on the vendor slot, which sends its own. */
    private val sendRtc: ((LocalDateTime) -> Unit)? = null,
) : McuOwner.Listener {

    private var pushed = false
    private var gpsSet = false

    override fun onRtc(time: LocalDateTime) {
        val mcuMs = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (!shouldSet(online(), gpsSet, mcuMs - System.currentTimeMillis())) {
            return
        }

        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { alarms.setTime(mcuMs) }
            .onSuccess { Log.i(TAG, "clock set from MCU RTC: $time") }
            .onFailure { Log.w(TAG, "clock not set from MCU RTC", it) }
    }

    /**
     * GPS wrote the clock this wake ([GpsClock]): the vendor's `mIsGPSGetLocation`, which blocks
     * the `83` frame (:3042) and, offline, is the only clock worth handing to the MCU.
     */
    fun onGpsClock() {
        gpsSet = true
        pushIfTrustworthy()
    }

    /** The once-per-wake GPS write comes back after ACC wake (setAccWakeUp, :3440). */
    override fun onWake() {
        gpsSet = false
    }

    /**
     * The other direction: give the MCU the clock once this boot has a trustworthy one. The
     * vendor only writes the MCU's RTC at power-off; a bench unit never powers off and a car
     * whose power-off frame is lost boots with no date. Once per boot, online or GPS-set only.
     */
    fun pushIfTrustworthy(now: LocalDateTime = LocalDateTime.now()) {
        if (!shouldPush(online(), gpsSet, now.year, pushed)) {
            return
        }
        sendRtc?.invoke(now) ?: return
        pushed = true
        Log.i(TAG, "MCU RTC set to $now")
    }

    private fun online(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        private const val TAG = "McuClock"

        /** Below this the clock already agrees with the MCU; writing it would only jitter. */
        const val DRIFT_FLOOR_MS = 2_000L

        /** A clock this build could not have made up: NTP or GPS has run, so the year is current. */
        const val TRUSTWORTHY_YEAR = 2025

        /** Push once per boot, only with a network or a GPS fix to have set the clock, only with a live year. */
        fun shouldPush(online: Boolean, gpsSet: Boolean, year: Int, alreadyPushed: Boolean): Boolean {
            if (alreadyPushed || !(online || gpsSet)) {
                return false
            }
            return year >= TRUSTWORTHY_YEAR
        }

        /** The vendor's gate: no network, no GPS fix yet, and the clock is off by more than the floor. */
        fun shouldSet(online: Boolean, gpsSet: Boolean, driftMs: Long): Boolean {
            if (online || gpsSet) {
                return false
            }
            return abs(driftMs) > DRIFT_FLOOR_MS
        }
    }
}
