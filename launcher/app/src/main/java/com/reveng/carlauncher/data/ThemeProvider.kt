package com.reveng.carlauncher.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.reveng.carlauncher.BuildConfig
import com.reveng.carlauncher.ui.theme.BuiltInThemes
import com.reveng.carlauncher.ui.theme.CarTheme
import com.reveng.carlauncher.ui.theme.ThemeStyle

/**
 * v0.5 — publishes the launcher's active palette to the `com.reveng.*` suite. Read-only; see
 * [ThemeContract] for the format and the reasoning behind it.
 *
 * The provider serves a snapshot written by the launcher UI ([ThemeSnapshotStore]) rather than
 * reading [ThemeStore] itself. `query` arrives on a binder thread and must answer synchronously,
 * while the theme lives in DataStore behind a coroutine — blocking a binder thread on it would
 * stall the *caller's* startup, which is exactly the frame this feature exists to get right.
 *
 * With no snapshot yet (first boot, or the launcher has not been opened since install) it serves
 * the built-in default. A client always gets a usable palette, so it never needs a "no theme yet"
 * branch of its own.
 */
class ThemeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        val snapshot = ThemeSnapshotStore.read(ctx) ?: defaultSnapshot()

        // Always the full column set: a projection would let a client silently receive fewer
        // columns than it asked for, and the row is sixteen values.
        val cursor = MatrixCursor(ThemeContract.COLUMNS, 1)
        cursor.addRow(ThemeContract.row(snapshot))

        // Lets a client hold a ContentObserver and re-paint on theme switch / day-night.
        // The uri the client actually queried, so a debug install notifies its own observers.
        cursor.setNotificationUri(ctx.contentResolver, uri)
        return cursor
    }

    override fun getType(uri: Uri): String =
        "vnd.android.cursor.item/vnd.${ThemeContract.AUTHORITY}.active"

    // Read-only by design: the palette is the launcher's state, and a suite app that could write
    // it would be able to restyle the home screen of a moving car.

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    private fun defaultSnapshot(): ThemeContract.Snapshot {
        val theme: CarTheme = BuiltInThemes.DEFAULT
        return ThemeContract.Snapshot(
            themeId = theme.id,
            themeName = theme.name,
            night = false,
            colors = theme.day,
            style = theme.style,
        )
    }
}

/**
 * The published palette, in a tiny SharedPreferences file of its own.
 *
 * SharedPreferences and not DataStore: [ThemeProvider] has to read this from a binder thread with
 * no coroutine to suspend in, and SharedPreferences is loaded once and answered from memory after
 * that. It holds sixteen values that are re-derived on every launcher start, so a lost write
 * costs nothing.
 */
object ThemeSnapshotStore {

    private const val PREFS_NAME = "theme-snapshot"

    private const val KEY_ID = "theme_id"
    private const val KEY_NAME = "theme_name"
    private const val KEY_NIGHT = "night"
    private const val KEY_BACKGROUND = "background"
    private const val KEY_SURFACE = "surface"
    private const val KEY_SURFACE_VARIANT = "surface_variant"
    private const val KEY_PRIMARY = "primary"
    private const val KEY_ON_BACKGROUND = "on_background"
    private const val KEY_ON_SURFACE = "on_surface"
    private const val KEY_ON_SURFACE_MUTED = "on_surface_muted"
    private const val KEY_ERROR = "error"
    private const val KEY_ACCENT2 = "accent2"
    private const val KEY_ACCENT3 = "accent3"
    private const val KEY_CORNER_SCALE = "corner_scale"
    private const val KEY_MONO_TYPE = "mono_type"
    private const val KEY_HARD_EDGE = "hard_edge"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Store [theme]'s resolved variant and tell observers. Called on every active-theme and
     * day/night change; writing an identical snapshot is cheap, so the caller does not dedupe.
     */
    fun publish(context: Context, theme: CarTheme, night: Boolean) {
        val c = theme.variant(night)
        prefs(context).edit()
            .putString(KEY_ID, theme.id)
            .putString(KEY_NAME, theme.name)
            .putBoolean(KEY_NIGHT, night)
            .putLong(KEY_BACKGROUND, c.background)
            .putLong(KEY_SURFACE, c.surface)
            .putLong(KEY_SURFACE_VARIANT, c.surfaceVariant)
            .putLong(KEY_PRIMARY, c.primary)
            .putLong(KEY_ON_BACKGROUND, c.onBackground)
            .putLong(KEY_ON_SURFACE, c.onSurface)
            .putLong(KEY_ON_SURFACE_MUTED, c.onSurfaceMuted)
            .putLong(KEY_ERROR, c.error)
            .putLong(KEY_ACCENT2, c.accent2)
            .putLong(KEY_ACCENT3, c.accent3)
            .putFloat(KEY_CORNER_SCALE, theme.style.cornerScale)
            .putBoolean(KEY_MONO_TYPE, theme.style.monoType)
            .putBoolean(KEY_HARD_EDGE, theme.style.hardEdge)
            .apply()

        val authority = ThemeContract.authorityFor(BuildConfig.APPLICATION_ID)
        context.applicationContext.contentResolver
            .notifyChange(ThemeContract.activeUri(authority), null)
    }

    /** The last published snapshot, or null if the launcher has not published one yet. */
    fun read(context: Context): ThemeContract.Snapshot? {
        val p = prefs(context)
        val id = p.getString(KEY_ID, null) ?: return null
        return ThemeContract.Snapshot(
            themeId = id,
            themeName = p.getString(KEY_NAME, id).orEmpty(),
            night = p.getBoolean(KEY_NIGHT, false),
            colors = com.reveng.carlauncher.ui.theme.ThemeColors(
                background = p.getLong(KEY_BACKGROUND, 0),
                surface = p.getLong(KEY_SURFACE, 0),
                surfaceVariant = p.getLong(KEY_SURFACE_VARIANT, 0),
                primary = p.getLong(KEY_PRIMARY, 0),
                onBackground = p.getLong(KEY_ON_BACKGROUND, 0),
                onSurface = p.getLong(KEY_ON_SURFACE, 0),
                onSurfaceMuted = p.getLong(KEY_ON_SURFACE_MUTED, 0),
                error = p.getLong(KEY_ERROR, 0),
                accent2 = p.getLong(KEY_ACCENT2, 0),
                accent3 = p.getLong(KEY_ACCENT3, 0),
            ),
            style = ThemeStyle(
                cornerScale = p.getFloat(KEY_CORNER_SCALE, 1f),
                monoType = p.getBoolean(KEY_MONO_TYPE, false),
                hardEdge = p.getBoolean(KEY_HARD_EDGE, false),
            ),
        )
    }
}
