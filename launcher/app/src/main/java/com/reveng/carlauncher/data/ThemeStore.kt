package com.reveng.carlauncher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.reveng.carlauncher.ui.theme.BuiltInThemes
import com.reveng.carlauncher.ui.theme.CarTheme
import com.reveng.carlauncher.ui.theme.ThemeColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** App-local DataStore (Preferences). Themes are app config, NOT car SysVars. */
private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "themes")

/**
 * Persists user-created themes and the active theme id in Jetpack DataStore, and exposes
 * the resolved active [CarTheme] as a [StateFlow] for MainActivity to observe.
 *
 * Built-in presets ([BuiltInThemes]) are never persisted — only user themes and the
 * active-id pointer are. User themes are stored as a single JSON string (hand-rolled with
 * `org.json`, so no extra serialization dependency is pulled in).
 */
class ThemeStore(context: Context) {

    private val ds = context.applicationContext.themeDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val userThemes: StateFlow<List<CarTheme>> =
        ds.data
            .map { prefs -> decodeThemes(prefs[USER_THEMES_KEY]) }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val activeId: StateFlow<String> =
        ds.data
            .map { prefs -> prefs[ACTIVE_ID_KEY] ?: BuiltInThemes.DEFAULT.id }
            .stateIn(scope, SharingStarted.Eagerly, BuiltInThemes.DEFAULT.id)

    /** Built-in presets first, then user themes — the list shown on the Themes screen. */
    val allThemes: StateFlow<List<CarTheme>> =
        userThemes
            .map { user -> BuiltInThemes.ALL + user }
            .stateIn(scope, SharingStarted.Eagerly, BuiltInThemes.ALL)

    /** The currently active theme, resolved from [activeId] (falls back to DEFAULT). */
    val activeTheme: StateFlow<CarTheme> =
        combine(allThemes, activeId) { all, id ->
            all.firstOrNull { it.id == id } ?: BuiltInThemes.DEFAULT
        }.stateIn(scope, SharingStarted.Eagerly, BuiltInThemes.DEFAULT)

    fun setActive(id: String) = scope.launch {
        ds.edit { it[ACTIVE_ID_KEY] = id }
    }

    /** Insert or update a user theme (built-ins are ignored — they are immutable). */
    fun upsert(theme: CarTheme) = scope.launch {
        if (theme.isBuiltIn) return@launch
        val next = userThemes.value.toMutableList()
        val idx = next.indexOfFirst { it.id == theme.id }
        if (idx >= 0) next[idx] = theme else next.add(theme)
        writeUserThemes(next)
    }

    /** Delete a user theme; if it was active, fall back to the default preset. */
    fun delete(id: String) = scope.launch {
        val next = userThemes.value.filterNot { it.id == id }
        writeUserThemes(next)
        if (activeId.value == id) {
            ds.edit { it[ACTIVE_ID_KEY] = BuiltInThemes.DEFAULT.id }
        }
    }

    /** Copy any theme (built-in or user) into a fresh, editable user theme. */
    fun duplicate(source: CarTheme): CarTheme {
        val copy = source.copy(
            id = "user.${System.currentTimeMillis()}",
            name = "${source.name} copy",
            isBuiltIn = false,
        )
        upsert(copy)
        return copy
    }

    private suspend fun writeUserThemes(themes: List<CarTheme>) {
        ds.edit { it[USER_THEMES_KEY] = encodeThemes(themes) }
    }

    private companion object {
        val USER_THEMES_KEY = stringPreferencesKey("user_themes_json")
        val ACTIVE_ID_KEY = stringPreferencesKey("active_theme_id")
    }
}

// ---- JSON (de)serialization ------------------------------------------------------------

private fun encodeThemes(themes: List<CarTheme>): String {
    val arr = JSONArray()
    themes.forEach { arr.put(it.toJson()) }
    return arr.toString()
}

private fun decodeThemes(json: String?): List<CarTheme> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { themeFromJson(arr.getJSONObject(it)) }
    } catch (_: Exception) {
        emptyList()
    }
}

private fun CarTheme.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("isBuiltIn", isBuiltIn)
    put("day", day.toJson())
    put("night", night.toJson())
}

private fun themeFromJson(o: JSONObject): CarTheme = CarTheme(
    id = o.getString("id"),
    name = o.getString("name"),
    isBuiltIn = o.optBoolean("isBuiltIn", false),
    day = colorsFromJson(o.getJSONObject("day")),
    night = colorsFromJson(o.getJSONObject("night")),
)

private fun ThemeColors.toJson(): JSONObject = JSONObject().apply {
    put("background", background)
    put("surface", surface)
    put("surfaceVariant", surfaceVariant)
    put("primary", primary)
    put("onBackground", onBackground)
    put("onSurface", onSurface)
    put("onSurfaceMuted", onSurfaceMuted)
    put("error", error)
}

private fun colorsFromJson(o: JSONObject): ThemeColors = ThemeColors(
    background = o.getLong("background"),
    surface = o.getLong("surface"),
    surfaceVariant = o.getLong("surfaceVariant"),
    primary = o.getLong("primary"),
    onBackground = o.getLong("onBackground"),
    onSurface = o.getLong("onSurface"),
    onSurfaceMuted = o.getLong("onSurfaceMuted"),
    error = o.getLong("error"),
)
