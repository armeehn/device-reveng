package com.ripostelabs.carlauncher.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.json.JSONArray
import org.json.JSONObject

/**
 * v3.0 — a named bundle of the settings that differ between two people sharing one car.
 *
 * @param themeId the [ThemeStore] theme to activate.
 * @param favorites package names, mirroring [FavoritesStore].
 * @param appOrder the quick-launch / drawer order, mirroring [AppOrderStore].
 * @param driverSide reachability, mirroring [SettingsStore]'s `driverSideMode` (v2.8).
 */
data class DriverProfile(
    val id: String,
    val name: String,
    val themeId: String,
    val favorites: Set<String>,
    val appOrder: List<String>,
    val driverSide: DriverSideMode,
) {
    /**
     * Stored as JSON in a single preference entry rather than as four parallel keys.
     *
     * A profile is only meaningful as a whole — half-applying one (new theme, old favourites)
     * is worse than not switching at all — so it is written and read as one value that cannot
     * tear. `org.json` is in the platform, so this costs no dependency.
     */
    fun encode(): String = JSONObject().apply {
        put(KEY_ID, id)
        put(KEY_NAME, name)
        put(KEY_THEME, themeId)
        put(KEY_FAVORITES, JSONArray(favorites.toList()))
        put(KEY_ORDER, JSONArray(appOrder))
        put(KEY_SIDE, driverSide.name)
    }.toString()

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_NAME = "name"
        private const val KEY_THEME = "theme"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_ORDER = "order"
        private const val KEY_SIDE = "side"

        /** Returns null for anything unparseable — a corrupt entry is skipped, never fatal. */
        fun decode(raw: String): DriverProfile? = runCatching {
            val o = JSONObject(raw)
            DriverProfile(
                id = o.getString(KEY_ID),
                name = o.getString(KEY_NAME),
                themeId = o.getString(KEY_THEME),
                favorites = o.optJSONArray(KEY_FAVORITES).toStringSet(),
                appOrder = o.optJSONArray(KEY_ORDER).toStringList(),
                driverSide = runCatching {
                    DriverSideMode.valueOf(o.optString(KEY_SIDE, DriverSideMode.AUTO.name))
                }.getOrDefault(DriverSideMode.AUTO),
            )
        }.getOrNull()

        private fun JSONArray?.toStringList(): List<String> {
            val a = this ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
        }

        private fun JSONArray?.toStringSet(): Set<String> = toStringList().toSet()
    }
}

/** App-local DataStore for driver profiles; separate file from launcher settings. */
private val Context.profilesDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "driver_profiles")

/**
 * v3.0 — named driver profiles (theme + favourites + quick-launch order + reachability),
 * switchable from Home in two taps.
 *
 * This store owns only the *bundles*. Applying one writes through to the stores that already own
 * each setting ([ThemeStore], [FavoritesStore], [AppOrderStore], [SettingsStore]) rather than
 * introducing a second source of truth for the same values — so everything that reads those
 * stores keeps working, and a profile can never disagree with the live setting.
 */
class DriverProfilesStore(
    private val context: Context,
    scope: CoroutineScope,
) {
    private val ds = context.applicationContext.profilesDataStore

    /** Profiles in creation order (the set is small; sorted by name for a stable strip). */
    val profiles: StateFlow<List<DriverProfile>> = ds.data
        .map { prefs ->
            (prefs[PROFILES_KEY] ?: emptySet())
                .mapNotNull { DriverProfile.decode(it) }
                .sortedBy { it.name.lowercase() }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val activeIdFlow = ds.data.map { it[ACTIVE_ID_KEY] }

    /** The profile last applied, or null when none has been (or it was since deleted). */
    val activeProfile: StateFlow<DriverProfile?> =
        combine(profiles, activeIdFlow) { list, id -> list.firstOrNull { it.id == id } }
            .stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun upsert(profile: DriverProfile) {
        ds.edit { prefs ->
            // Replace by id rather than by encoded string: any field may have changed, so
            // matching on the whole value would leave the old copy behind as a duplicate.
            val kept = (prefs[PROFILES_KEY] ?: emptySet())
                .filter { DriverProfile.decode(it)?.id != profile.id }
            prefs[PROFILES_KEY] = (kept + profile.encode()).toSet()
        }
    }

    suspend fun delete(id: String) {
        ds.edit { prefs ->
            prefs[PROFILES_KEY] = (prefs[PROFILES_KEY] ?: emptySet())
                .filter { DriverProfile.decode(it)?.id != id }
                .toSet()
            if (prefs[ACTIVE_ID_KEY] == id) {
                prefs.remove(ACTIVE_ID_KEY)
            }
        }
    }

    suspend fun markActive(id: String) {
        ds.edit { it[ACTIVE_ID_KEY] = id }
    }

    /**
     * Write [profile] through to the stores that own each setting, then record it as active.
     *
     * Order matters: every write lands (is awaited) before [markActive], so a failure part-way
     * leaves the profile un-marked rather than marked-but-unapplied. A driver seeing the old
     * name next to their new theme is a much smaller lie than the reverse. The theme and
     * driver-side setters run on their stores' own scopes and return [kotlinx.coroutines.Job]s;
     * fire-and-forgetting those let markActive commit first, which is exactly the
     * marked-but-unapplied state this ordering exists to rule out.
     */
    suspend fun apply(
        profile: DriverProfile,
        themeStore: ThemeStore,
        favoritesStore: FavoritesStore,
        appOrderStore: AppOrderStore,
        settingsStore: SettingsStore,
    ) {
        val themeWrite = themeStore.setActive(profile.themeId)
        favoritesStore.setAll(profile.favorites)
        appOrderStore.setOrder(profile.appOrder)
        val sideWrite = settingsStore.setDriverSideMode(profile.driverSide)
        themeWrite.join()
        sideWrite.join()

        markActive(profile.id)
    }

    /**
     * Snapshot the live settings into a profile named [name], reusing [id] when overwriting.
     *
     * "Save the way things are now" is how a profile actually gets made — nobody sits down to
     * author one field by field, they arrange the launcher until it suits them and then want to
     * keep it.
     */
    suspend fun captureCurrent(
        name: String,
        themeStore: ThemeStore,
        favoritesStore: FavoritesStore,
        appOrderStore: AppOrderStore,
        settingsStore: SettingsStore,
        id: String = "profile.${System.currentTimeMillis()}",
    ): DriverProfile {
        val profile = DriverProfile(
            id = id,
            name = name,
            themeId = themeStore.activeTheme.value.id,
            favorites = favoritesStore.favorites.value,
            appOrder = appOrderStore.order.value,
            driverSide = settingsStore.settings.value.driverSideMode,
        )
        upsert(profile)
        return profile
    }

    private companion object {
        val PROFILES_KEY = stringSetPreferencesKey("profiles")
        val ACTIVE_ID_KEY = stringPreferencesKey("active_profile")
    }
}
