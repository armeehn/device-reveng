package com.ripostelabs.carlauncher.data

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
import org.json.JSONArray
import org.json.JSONObject

/** App-local DataStore for the v2.7 continue-watching shelf. Launcher state, not a car signal. */
private val Context.watchHistoryDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "watch_history")

/**
 * One thing this head unit has played, as far as its MediaSession told us.
 *
 * [positionMs] / [durationMs] are the session's own numbers at the moment we last saw it, so the
 * shelf can draw a progress bar. Both may be -1: plenty of players never publish a duration, and
 * a live stream has none. Treat the pair as a hint, never as truth about the server's playstate.
 */
data class WatchEntry(
    val packageName: String,
    val title: String,
    val subtitle: String,
    val positionMs: Long,
    val durationMs: Long,
    val lastSeenAtMs: Long,
) {
    /** Fraction watched, or null when the session published no usable duration. */
    fun progress(): Float? {
        if (durationMs <= 0 || positionMs < 0) {
            return null
        }
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }
}

/**
 * Persists the recently-played list behind the continue-watching shelf.
 *
 * Hand-rolled `org.json` rather than a serialization dependency, matching [ThemeStore] — the
 * whole app deliberately carries no JSON library.
 *
 * Dedupe is by title within a package, not by any item id: MediaSession metadata carries no stable
 * identifier we can trust across apps, so two different episodes with the same title collapse into
 * one row. That is a real limitation and the reason this shelf is a convenience, not a library.
 */
class WatchHistoryStore(context: Context, scope: CoroutineScope) {

    private val ds = context.applicationContext.watchHistoryDataStore

    /** Most recently seen first; the shelf renders this order directly. */
    val entries: StateFlow<List<WatchEntry>> = ds.data
        .map { prefs -> decode(prefs[ENTRIES_KEY]) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Insert or refresh [entry]. Read-modify-write inside the transaction so a burst of metadata
     * callbacks can't clobber each other through a stale snapshot (the [ThemeStore.upsert] lesson).
     */
    suspend fun record(entry: WatchEntry) {
        ds.edit { prefs ->
            val kept = decode(prefs[ENTRIES_KEY])
                .filterNot { it.packageName == entry.packageName && it.title == entry.title }
            prefs[ENTRIES_KEY] = encode((listOf(entry) + kept).take(MAX_ENTRIES))
        }
    }

    suspend fun remove(entry: WatchEntry) {
        ds.edit { prefs ->
            val kept = decode(prefs[ENTRIES_KEY])
                .filterNot { it.packageName == entry.packageName && it.title == entry.title }
            prefs[ENTRIES_KEY] = encode(kept)
        }
    }

    suspend fun clear() {
        ds.edit { it[ENTRIES_KEY] = encode(emptyList()) }
    }

    private companion object {
        val ENTRIES_KEY = stringPreferencesKey("watch_entries_json")

        /** A glanceable shelf, not an archive — one screen of rows is the whole point. */
        const val MAX_ENTRIES = 12
    }
}

// ---- JSON (de)serialization ------------------------------------------------------------

private fun encode(entries: List<WatchEntry>): String {
    val arr = JSONArray()
    entries.forEach { e ->
        arr.put(
            JSONObject().apply {
                put("pkg", e.packageName)
                put("title", e.title)
                put("subtitle", e.subtitle)
                put("position", e.positionMs)
                put("duration", e.durationMs)
                put("seen", e.lastSeenAtMs)
            },
        )
    }
    return arr.toString()
}

private fun decode(json: String?): List<WatchEntry> {
    if (json.isNullOrBlank()) {
        return emptyList()
    }
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            WatchEntry(
                packageName = o.getString("pkg"),
                title = o.getString("title"),
                subtitle = o.optString("subtitle"),
                positionMs = o.optLong("position", -1L),
                durationMs = o.optLong("duration", -1L),
                lastSeenAtMs = o.optLong("seen", 0L),
            )
        }
    } catch (_: Exception) {
        // A corrupt blob costs the shelf, nothing else. Dropping it is better than crashing the
        // launcher on boot over a convenience feature.
        emptyList()
    }
}
