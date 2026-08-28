package com.reveng.carlauncher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * v0.6 — launcher settings persisted in Jetpack DataStore, separate from [ThemeStore].
 *
 * Holds the user-tunable launcher preferences that Home and QuickControls read:
 *   * app-grid density (column count feeding [com.reveng.carlauncher.ui.AppDrawer]),
 *   * which Home widgets are shown (media / radio / climate / nav), and
 *   * the day/night theme mode ([DayNightMode]: AUTO follows the vendor illumination
 *     broadcast, FORCE_DAY / FORCE_NIGHT override it).
 *
 * Everything is exposed as a single resolved [settings] [StateFlow] plus per-field setters,
 * mirroring the [ThemeStore] pattern (own DataStore file, no extra serialization dep).
 */

/** Day/night theming policy for the launcher (overrides CarEvents.dayNight when forced). */
enum class DayNightMode { AUTO, FORCE_DAY, FORCE_NIGHT }

/** Immutable snapshot of all launcher settings. */
data class LauncherSettings(
    val gridColumns: Int = DEFAULT_GRID_COLUMNS,
    val showMedia: Boolean = true,
    val showRadio: Boolean = true,
    val showClimate: Boolean = true,
    val showNav: Boolean = true,
    val dayNightMode: DayNightMode = DayNightMode.AUTO,
    /**
     * v2.5 — enforce the LAUNCHER_DESIGN §1.4 parked-only rules. On by default: the gate is a
     * safety feature, so it must be opted *out* of, not into. The escape hatch exists because
     * the gate rests on GPS speed, and a bench or garage session with a bad fix would otherwise
     * hide the theme editor and SysVar browser with no way to get them back.
     */
    val motionGateEnabled: Boolean = true,
    val shadeEnabled: Boolean = true, // v2.5 swipe-from-top Quick Controls shade
    val replaceSystemBars: Boolean = false, // v2.5 suppress vendor status bar + shade (root)
    /**
     * v2.8 — reachability mirror (LAUNCHER_DESIGN §2.5). AUTO defers to [Reachability], which has
     * no confirmed `Sys_CarType` mapping yet and therefore always answers LHD; LHD/RHD pin it.
     */
    val driverSideMode: DriverSideMode = DriverSideMode.AUTO,
    /**
     * v2.8 — the user has checked the radar byte layout against their car (Settings ▸ Parking
     * radar ▸ Raw frame capture) and it decodes correctly.
     *
     * Off by default, and the default is the whole point: [com.reveng.carlauncher.carlib.RadarState]
     * decodes a GUESSED layout, so anything that turns a decoded level into a *safety* claim — the
     * maneuvering side-strip — stays hidden until a human has confirmed the guess on a real car.
     * The bars that merely mirror the raw frame (the radar settings readout) are not gated: they
     * report what arrived, not what it means.
     */
    val radarLayoutConfirmed: Boolean = false,
) {
    companion object {
        /** 0 = adaptive/auto sizing; otherwise a fixed column count. */
        const val DEFAULT_GRID_COLUMNS = 3
        const val MIN_GRID_COLUMNS = 2
        const val MAX_GRID_COLUMNS = 6
    }
}

/** App-local DataStore (Preferences). Launcher config, NOT car SysVars. */
private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsStore(context: Context) {

    private val ds = context.applicationContext.settingsDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * v1.0 — first-run flag driving the one-shot onboarding flow.
     *
     * Nullable on purpose: the initial value is `null` = "not yet read from disk", so
     * MainActivity can hold a plain themed frame until the real value arrives instead of
     * flashing the onboarding screen at every returning user (the stored default is `true`,
     * so a naive non-null initial would mis-route). `true` = show onboarding once,
     * `false` = onboarding already completed/skipped.
     */
    val firstRun: StateFlow<Boolean?> =
        ds.data
            .map { prefs -> prefs[FIRST_RUN_KEY] ?: true }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** Mark onboarding as done so it never shows again (called on Finish or Skip). */
    fun setFirstRunComplete() = scope.launch { ds.edit { it[FIRST_RUN_KEY] = false } }

    /** Cancel the internal scope (its Eagerly StateFlow collectors). Call from Activity.onDestroy. */
    fun release() = scope.cancel()

    /** The resolved launcher settings, observed by MainActivity / HomeScreen / QuickControls. */
    val settings: StateFlow<LauncherSettings> =
        ds.data
            .map { prefs ->
                LauncherSettings(
                    gridColumns = prefs[GRID_COLUMNS_KEY] ?: LauncherSettings.DEFAULT_GRID_COLUMNS,
                    showMedia = prefs[SHOW_MEDIA_KEY] ?: true,
                    showRadio = prefs[SHOW_RADIO_KEY] ?: true,
                    showClimate = prefs[SHOW_CLIMATE_KEY] ?: true,
                    showNav = prefs[SHOW_NAV_KEY] ?: true,
                    dayNightMode = runCatching {
                        DayNightMode.valueOf(prefs[DAY_NIGHT_MODE_KEY] ?: DayNightMode.AUTO.name)
                    }.getOrDefault(DayNightMode.AUTO),
                    motionGateEnabled = prefs[MOTION_GATE_KEY] ?: true, // v2.5
                    shadeEnabled = prefs[SHADE_ENABLED_KEY] ?: true,
                    replaceSystemBars = prefs[REPLACE_SYSTEM_BARS_KEY] ?: false,
                    driverSideMode = runCatching { // v2.8
                        DriverSideMode.valueOf(prefs[DRIVER_SIDE_KEY] ?: DriverSideMode.AUTO.name)
                    }.getOrDefault(DriverSideMode.AUTO),
                    radarLayoutConfirmed = prefs[RADAR_CONFIRMED_KEY] ?: false, // v2.8
                )
            }
            .stateIn(scope, SharingStarted.Eagerly, LauncherSettings())

    fun setGridColumns(columns: Int) = scope.launch {
        val clamped = columns.coerceIn(
            LauncherSettings.MIN_GRID_COLUMNS,
            LauncherSettings.MAX_GRID_COLUMNS,
        )
        ds.edit { it[GRID_COLUMNS_KEY] = clamped }
    }

    fun setShowMedia(show: Boolean) = scope.launch { ds.edit { it[SHOW_MEDIA_KEY] = show } }
    fun setShowRadio(show: Boolean) = scope.launch { ds.edit { it[SHOW_RADIO_KEY] = show } }
    fun setShowClimate(show: Boolean) = scope.launch { ds.edit { it[SHOW_CLIMATE_KEY] = show } }
    fun setShowNav(show: Boolean) = scope.launch { ds.edit { it[SHOW_NAV_KEY] = show } }

    fun setDayNightMode(mode: DayNightMode) = scope.launch {
        ds.edit { it[DAY_NIGHT_MODE_KEY] = mode.name }
    }

    /** v2.5 — turn the parked-only gate on or off. */
    fun setMotionGateEnabled(enabled: Boolean) = scope.launch {
        ds.edit { it[MOTION_GATE_KEY] = enabled }
    }

    fun setShadeEnabled(enabled: Boolean) = scope.launch { ds.edit { it[SHADE_ENABLED_KEY] = enabled } }

    fun setReplaceSystemBars(enabled: Boolean) = scope.launch {
        ds.edit { it[REPLACE_SYSTEM_BARS_KEY] = enabled }
    }

    /** v2.8 — pin the driver's side, or hand it back to [Reachability]. */
    fun setDriverSideMode(mode: DriverSideMode) = scope.launch {
        ds.edit { it[DRIVER_SIDE_KEY] = mode.name }
    }

    /** v2.8 — record that the radar byte layout was checked against a real car. */
    fun setRadarLayoutConfirmed(confirmed: Boolean) = scope.launch {
        ds.edit { it[RADAR_CONFIRMED_KEY] = confirmed }
    }

    private companion object {
        val GRID_COLUMNS_KEY = intPreferencesKey("grid_columns")
        val SHOW_MEDIA_KEY = booleanPreferencesKey("show_media")
        val SHOW_RADIO_KEY = booleanPreferencesKey("show_radio")
        val SHOW_CLIMATE_KEY = booleanPreferencesKey("show_climate")
        val SHOW_NAV_KEY = booleanPreferencesKey("show_nav")
        val DAY_NIGHT_MODE_KEY = stringPreferencesKey("day_night_mode")
        val FIRST_RUN_KEY = booleanPreferencesKey("first_run") // v1.0 onboarding gate
        val MOTION_GATE_KEY = booleanPreferencesKey("motion_gate") // v2.5 parked-only gate
        val SHADE_ENABLED_KEY = booleanPreferencesKey("shade_enabled") // v2.5
        val REPLACE_SYSTEM_BARS_KEY = booleanPreferencesKey("replace_system_bars") // v2.5
        val DRIVER_SIDE_KEY = stringPreferencesKey("driver_side") // v2.8 reachability mirror
        val RADAR_CONFIRMED_KEY = booleanPreferencesKey("radar_layout_confirmed") // v2.8
    }
}
