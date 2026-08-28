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
import kotlinx.coroutines.launch

/** Backing DataStore for the v2.7 notification-shelf filter. */
private val Context.notificationFilterDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "notification_filter")

/**
 * v2.7 — which apps are allowed on the notification shelf, mirroring [RadioPresetsStore]'s pattern.
 *
 * Stored as a **deny** list, not an allow list. An allow list means a newly installed app is
 * silently invisible until someone remembers to permit it, which is the failure mode where the
 * driver never learns the feature is dropping things. A deny list fails the other way: everything
 * shows until you say otherwise, and the one app you muted stays muted.
 *
 * Filtering happens at render time, not at collection time, so un-muting an app brings its already
 * captured notifications straight back rather than starting from the next one.
 */
class NotificationFilterStore(context: Context, private val scope: CoroutineScope) {

    private val ds = context.applicationContext.notificationFilterDataStore

    /** Packages the driver has muted. */
    val muted: StateFlow<Set<String>> = ds.data
        .map { prefs -> prefs[MUTED_KEY] ?: emptySet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    fun setMuted(packageName: String, muted: Boolean) = scope.launch {
        ds.edit { prefs ->
            val current = prefs[MUTED_KEY]?.toMutableSet() ?: mutableSetOf()
            if (muted) current.add(packageName) else current.remove(packageName)
            prefs[MUTED_KEY] = current
        }
    }

    private companion object {
        val MUTED_KEY = stringSetPreferencesKey("muted_packages")
    }
}
