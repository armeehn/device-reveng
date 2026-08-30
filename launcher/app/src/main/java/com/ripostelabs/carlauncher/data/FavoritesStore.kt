package com.ripostelabs.carlauncher.data

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

/** Backing DataStore for favorite package names (one per process, distinct name). */
private val Context.favoritesDataStore: DataStore<Preferences> by preferencesDataStore(name = "favorites")

/**
 * Persists the set of "favorite" app package names for the drawer's pinned section, via
 * Jetpack DataStore Preferences (v0.4, App Drawer 2.0). Deliberately NOT SysVar — this is
 * pure launcher UI state, not a vehicle signal.
 *
 * Favorites are keyed by package name (stable across icon/label changes). Exposed as a
 * [StateFlow] so Compose can observe it with [androidx.lifecycle.compose.collectAsStateWithLifecycle].
 */
class FavoritesStore(
    private val context: Context,
    scope: CoroutineScope,
) {
    private val key = stringSetPreferencesKey("favorite_packages")

    /** Current favorite package names; empty until the first read completes. */
    val favorites: StateFlow<Set<String>> = context.favoritesDataStore.data
        .map { prefs -> prefs[key] ?: emptySet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Add [pkg] if absent, remove it if present. */
    suspend fun toggle(pkg: String) {
        context.favoritesDataStore.edit { prefs ->
            val current = prefs[key]?.toMutableSet() ?: mutableSetOf()
            if (!current.add(pkg)) current.remove(pkg)
            prefs[key] = current
        }
    }

    /**
     * v3.0 — replace the whole set in one write, for [DriverProfilesStore] switching profiles.
     *
     * A profile must land atomically: applying it as a run of [toggle] calls would publish a
     * sequence of half-merged states to every collector, and a failure part-way through would
     * leave a set belonging to neither driver.
     */
    suspend fun setAll(pkgs: Set<String>) {
        context.favoritesDataStore.edit { prefs -> prefs[key] = pkgs }
    }
}
