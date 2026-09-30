package com.ripostelabs.car

import android.app.UiModeManager
import android.content.Context
import android.util.Log

/**
 * RAV4-169: the system night mode, as stock's sendSysUiModeNight (EventService.java:14043-14060).
 * Every app that follows the system theme (the suite, the keyboard, CarPlay's night map) turns
 * with the launcher. MODIFY_DAY_NIGHT_MODE is a privileged permission; the system uid holds it.
 */
class SystemUiMode(private val context: Context) : UiMode {

    override fun setNightMode(mode: Int) {
        val manager = context.getSystemService(UiModeManager::class.java) ?: return
        runCatching { manager.nightMode = mode }.onFailure { Log.w(TAG, "night mode $mode refused", it) }
    }

    private companion object {
        const val TAG = "SystemUiMode"
    }
}
