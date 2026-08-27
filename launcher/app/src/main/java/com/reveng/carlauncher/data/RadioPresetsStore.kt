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

/** Backing DataStore for radio presets (distinct name; one instance per process). */
private val Context.radioPresetsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "radio_presets")

/** A single saved tuner preset: a band + a raw frequency value (getRadioFreq() units). */
data class RadioPreset(
    val band: Int,
    val freq: Int,
) {
    /** Serialize as "band:freq" for the DataStore string set. */
    fun encode(): String = "$band:$freq"

    companion object {
        fun decode(s: String): RadioPreset? {
            val parts = s.split(":")
            if (parts.size != 2) return null
            val b = parts[0].toIntOrNull() ?: return null
            val f = parts[1].toIntOrNull() ?: return null
            return RadioPreset(band = b, freq = f)
        }
    }
}

/**
 * v0.9 (Radio 2.0) — persists radio presets via Jetpack DataStore Preferences, mirroring
 * [FavoritesStore]'s pattern. Presets are pure launcher UI state (not a vehicle signal), so
 * DataStore rather than SysVar.
 *
 * We store presets as a string set of "band:freq" tokens. Order isn't guaranteed by a Set,
 * so the card sorts by (band, freq) for a stable strip. The raw freq encoding is whatever
 * getRadioFreq() returns — recalling a preset just replays that value through sendUserFreq().
 */
class RadioPresetsStore(
    private val context: Context,
    scope: CoroutineScope,
) {
    private val key = stringSetPreferencesKey("radio_presets")

    /** Current presets, sorted by (band, freq); empty until the first read completes. */
    val presets: StateFlow<List<RadioPreset>> = context.radioPresetsDataStore.data
        .map { prefs ->
            (prefs[key] ?: emptySet())
                .mapNotNull { RadioPreset.decode(it) }
                .sortedWith(compareBy({ it.band }, { it.freq }))
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Add a preset (no-op if an identical band+freq already exists). */
    suspend fun add(preset: RadioPreset) {
        context.radioPresetsDataStore.edit { prefs ->
            val current = prefs[key]?.toMutableSet() ?: mutableSetOf()
            current.add(preset.encode())
            prefs[key] = current
        }
    }

    /** Remove a preset if present. */
    suspend fun remove(preset: RadioPreset) {
        context.radioPresetsDataStore.edit { prefs ->
            val current = prefs[key]?.toMutableSet() ?: mutableSetOf()
            current.remove(preset.encode())
            prefs[key] = current
        }
    }
}
