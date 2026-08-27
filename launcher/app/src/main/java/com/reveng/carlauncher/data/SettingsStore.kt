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

    private companion object {
        val GRID_COLUMNS_KEY = intPreferencesKey("grid_columns")
        val SHOW_MEDIA_KEY = booleanPreferencesKey("show_media")
        val SHOW_RADIO_KEY = booleanPreferencesKey("show_radio")
        val SHOW_CLIMATE_KEY = booleanPreferencesKey("show_climate")
        val SHOW_NAV_KEY = booleanPreferencesKey("show_nav")
        val DAY_NIGHT_MODE_KEY = stringPreferencesKey("day_night_mode")
        val FIRST_RUN_KEY = booleanPreferencesKey("first_run") // v1.0 onboarding gate
    }
}
