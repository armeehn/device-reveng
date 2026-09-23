package com.ripostelabs.carlauncher.carlib

import android.content.Context

/**
 * VolumeMemory — the amp level across boots, which the vendor kept in its own settings.
 *
 *     MCU ──79──▶ onMainVolume ──▶ saved here ──▶ next boot: StartupConfig.mainVolume ──▶ 05 05 v
 *
 * Without it nothing sets the amp at boot, the MCU never reports a level, and the volume slider
 * waits for a report that never comes (0.2 in the car, 2026-09-22).
 */
class VolumeMemory(context: Context) : McuOwner.Listener {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The last level the MCU reported, or a quiet default on a first boot. */
    fun level(): Int = prefs.getInt(KEY_LEVEL, DEFAULT_LEVEL)

    override fun onMainVolume(volume: McuOwnerProtocol.MainVolume) {
        prefs.edit().putInt(KEY_LEVEL, volume.level).apply()
    }

    private companion object {
        const val PREFS = "mcu_volume"
        const val KEY_LEVEL = "level"
        /** 12 of [CarService.MAX_VOLUME] (40): audible, not startling. */
        const val DEFAULT_LEVEL = 12
    }
}
