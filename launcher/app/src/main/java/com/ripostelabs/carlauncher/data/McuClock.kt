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
 * the network half of that gate and adds a drift floor so a frame that agrees with the clock
 * never writes it.
 *
 *     McuOwner ─▶ onRtc(83 yy MM dd HH mm ss) ─▶ policy ─▶ AlarmManager.setTime
 *
 * Needs android.permission.SET_TIME, a privileged permission the OS build allowlists.
 */
class McuClock(private val context: Context) : McuOwner.Listener {

    override fun onRtc(time: LocalDateTime) {
        val mcuMs = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (!shouldSet(online(), mcuMs - System.currentTimeMillis())) {
            return
        }

        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { alarms.setTime(mcuMs) }
            .onSuccess { Log.i(TAG, "clock set from MCU RTC: $time") }
            .onFailure { Log.w(TAG, "clock not set from MCU RTC", it) }
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

        /** The vendor's gate, network half: offline, and the clock is off by more than the floor. */
        fun shouldSet(online: Boolean, driftMs: Long): Boolean {
            if (online) {
                return false
            }
            return abs(driftMs) > DRIFT_FLOOR_MS
        }
    }
}
