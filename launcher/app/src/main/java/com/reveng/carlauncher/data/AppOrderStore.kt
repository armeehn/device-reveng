package com.reveng.carlauncher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Backing DataStore for the user's custom app order (distinct name from favorites). */
private val Context.appOrderDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_order")

/**
 * Persists the user's custom drag-to-reorder order for the main app grid (v0.4). The order is
 * a list of package names; apps not present in the saved order are appended afterwards,
 * alphabetically, by the drawer. Stored as a single newline-joined string (package names never
 * contain newlines).
 */
class AppOrderStore(
    private val context: Context,
    scope: CoroutineScope,
) {
    private val key = stringPreferencesKey("app_order")

    /** Ordered package names; empty until first read (drawer then falls back to alphabetical). */
    val order: StateFlow<List<String>> = context.appOrderDataStore.data
        .map { prefs -> prefs[key]?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Replace the saved order with [pkgs] (in the new visual order). */
    suspend fun setOrder(pkgs: List<String>) {
        context.appOrderDataStore.edit { prefs ->
            prefs[key] = pkgs.joinToString("\n")
        }
    }
}
