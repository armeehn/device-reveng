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

    /**
     * Add a preset. Returns false when the store is full ([MAX_PRESETS]) — the UIs render
     * exactly six slots (`presets.take(6)`), so an uncapped 7th would be invisible and
     * undeletable there, and could displace a shown one by sort order. A band+freq that
     * already exists is a no-op success.
     */
    suspend fun add(preset: RadioPreset): Boolean {
        var accepted = false
        context.radioPresetsDataStore.edit { prefs ->
            val next = addCapped(prefs[key] ?: emptySet(), preset)
            if (next != null) {
                prefs[key] = next
                accepted = true
            }
        }
        return accepted
    }

    /** Remove a preset if present. */
    suspend fun remove(preset: RadioPreset) {
        context.radioPresetsDataStore.edit { prefs ->
            val current = prefs[key]?.toMutableSet() ?: mutableSetOf()
            current.remove(preset.encode())
            prefs[key] = current
        }
    }

    companion object {
        /** The UIs show `presets.take(6)`; the store must never hold more than they render. */
        const val MAX_PRESETS = 6

        /**
         * Pure add-with-cap over the stored token set: the new set, the same set for a
         * duplicate, or null when full (reject — the caller informs, nothing is written).
         * Counts raw tokens, not decodable ones: corrupt entries still occupy visible slots.
         */
        internal fun addCapped(current: Set<String>, preset: RadioPreset): Set<String>? {
            val token = preset.encode()
            if (token in current) {
                return current
            }
            if (current.size >= MAX_PRESETS) {
                return null
            }
            return current + token
        }
    }
}
