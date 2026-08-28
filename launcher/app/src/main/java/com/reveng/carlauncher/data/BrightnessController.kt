package com.reveng.carlauncher.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.reveng.carlauncher.carlib.CarService

/**
 * BrightnessController — makes the Display screen's backlight slider actually change the
 * screen, without root.
 *
 * The vendor illumination SysVars (`Sys_Light_Level_set`, `Set_Day_Light`, …) only take effect
 * when written as root/system, so on a plain install the slider moved but nothing happened. This
 * drives the **Android framework** backlight instead — `Settings.System.SCREEN_BRIGHTNESS`
 * (0–255) plus manual mode — which a normal app can write once it holds the special-access
 * `WRITE_SETTINGS` permission ([canWrite] / [requestPermission]). As a best-effort second path it
 * also pushes the level to the MCU over the vendor AIDL ([CarService.sendBacklight]).
 *
 * Percent (0–100) is the UI unit; the framework value is 0–255.
 */
object BrightnessController {

    private const val TAG = "BrightnessController"
    private const val MAX = 255
    /** Never let the head-unit backlight go fully black from a settings slider. */
    private const val MIN_APPLY = 6 // ~2%

    /**
     * Top of this panel's *usable* backlight band.
     *
     * Measured on the GT6 head unit: the panel's luminance saturates at a raw PWM value of only
     * ~24/255 and is flat above it (setting 24 vs 255 looks identical), so mapping the slider
     * across 0–255 — or even 0–34 — left the top of its travel doing nothing. We map the full
     * 0–100 % slider into [MIN_APPLY, USABLE_MAX] so every part of the slider changes the screen.
     * Determined empirically (raw plateau ≈20); nudge this if a unit's panel differs.
     */
    private const val USABLE_MAX = 20

    /** slider 0–100 % → raw backlight value, linear across the usable band. */
    private fun percentToRaw(percent: Int): Int {
        val p = percent.coerceIn(0, 100) / 100.0
        val raw = MIN_APPLY + (USABLE_MAX - MIN_APPLY) * p
        return Math.round(raw).toInt().coerceIn(MIN_APPLY, USABLE_MAX)
    }

    /** raw backlight value → slider 0–100 % (inverse; values above the band read as 100 %). */
    private fun rawToPercent(raw: Int): Int {
        val span = (USABLE_MAX - MIN_APPLY).toDouble()
        val frac = ((raw - MIN_APPLY) / span).coerceIn(0.0, 1.0)
        return Math.round(100.0 * frac).toInt().coerceIn(0, 100)
    }

    /** True once the user has granted WRITE_SETTINGS (special access). */
    fun canWrite(context: Context): Boolean = Settings.System.canWrite(context)

    /** Open the system "Modify system settings" grant screen for this app. */
    fun requestPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "cannot open WRITE_SETTINGS screen", it) }
    }

    /** Current system brightness as a 0–100 % slider position (perceptual curve). */
    fun currentPercent(context: Context): Int {
        val raw = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(128)
        return rawToPercent(raw)
    }

    /**
     * Apply a 0–100 % brightness live. Sets manual mode + SCREEN_BRIGHTNESS if we hold
     * WRITE_SETTINGS; also nudges the MCU backlight via [carService]. Returns true if the
     * framework write went through (false = permission missing).
     */
    fun setPercent(context: Context, percent: Int, carService: CarService?): Boolean {
        val value = percentToRaw(percent)
        // Best-effort MCU path regardless of WRITE_SETTINGS (guarded, no-op if unbound).
        runCatching { carService?.sendBacklight(value) }

        if (!canWrite(context)) return false
        return runCatching {
            // Manual mode so our value sticks (auto mode would immediately override it).
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                value,
            )
            true
        }.getOrElse {
            Log.w(TAG, "brightness write failed", it)
            false
        }
    }
}
