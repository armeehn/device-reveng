package com.reveng.carlauncher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Backing DataStore for the user's per-app placement overrides (distinct name). */
private val Context.appDirectoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_directory")

/**
 * Where the user has chosen to put an app in the drawer, overriding [AppRepository]'s built-in
 * user/system classification. Absence of an entry = [DEFAULT] (honour the classification).
 */
enum class Placement { HOME, SYSTEM, HIDDEN }

/**
 * Persists the user's custom app directory (v0.4.2): a per-package [Placement] that overrides the
 * hard-coded user/system split in [com.reveng.carlauncher.AppRepository]. Lets the driver pull a
 * misclassified vendor app onto the home grid, tuck a cluttering app into the System folder, or
 * hide one from the drawer entirely — none of which the fixed classification allowed.
 *
 * Same shape as [FavoritesStore] / [AppOrderStore]: pure launcher UI state via DataStore
 * Preferences (never SysVar — this is not a vehicle signal), exposed as a [StateFlow] for Compose.
 * Keyed by package name (stable across icon/label changes). Serialised as a set of `pkg|PLACEMENT`
 * strings; package names never contain `|`.
 */
class AppDirectoryStore(
    private val context: Context,
    scope: CoroutineScope,
) {
    private val key = stringSetPreferencesKey("placements")

    /** Package name → chosen [Placement]; empty until the first read completes. */
    val placements: StateFlow<Map<String, Placement>> = context.appDirectoryDataStore.data
        .map { prefs -> decode(prefs[key]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** Set [pkg]'s placement, or clear it (back to the default classification) when [placement] is null. */
    suspend fun setPlacement(pkg: String, placement: Placement?) {
        context.appDirectoryDataStore.edit { prefs ->
            val current = decode(prefs[key]).toMutableMap()
            if (placement == null) current.remove(pkg) else current[pkg] = placement
            prefs[key] = encode(current)
        }
    }

    /** Clear every override at once (the screen's "Reset to defaults"). */
    suspend fun clearAll() {
        context.appDirectoryDataStore.edit { prefs -> prefs[key] = emptySet() }
    }

    private fun decode(raw: Set<String>?): Map<String, Placement> =
        raw.orEmpty().mapNotNull { entry ->
            val sep = entry.lastIndexOf('|')
            if (sep <= 0) return@mapNotNull null
            val pkg = entry.substring(0, sep)
            val placement = runCatching { Placement.valueOf(entry.substring(sep + 1)) }.getOrNull()
                ?: return@mapNotNull null
            pkg to placement
        }.toMap()

    private fun encode(map: Map<String, Placement>): Set<String> =
        map.entries.map { (pkg, placement) -> "$pkg|${placement.name}" }.toSet()
}
