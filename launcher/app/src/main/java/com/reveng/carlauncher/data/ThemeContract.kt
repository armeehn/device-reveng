package com.reveng.carlauncher.data

import android.net.Uri
import com.reveng.carlauncher.ui.theme.ThemeColors

/**
 * v0.5 — the wire format the launcher publishes its active palette on, for the standalone
 * `com.reveng.*` suite ([RevengSuite]) to paint itself with.
 *
 * ## Why a ContentProvider and not a file or a broadcast
 *
 * The suite apps are ordinary packages in their own sandboxes, so they cannot read the launcher's
 * files. A world-readable file would need external storage and a permission each app must be
 * granted by hand at the car. A broadcast delivers the theme only to apps that happen to be
 * running, so a cold-started app would paint its fallback palette and then flash to the real one.
 *
 * A provider answers all three: it is pull-based (an app reads at startup, before its first
 * frame), it needs no runtime permission, and [ACTIVE_URI] can be observed, so an app already on
 * screen re-paints when the driver switches theme or the car crosses into night.
 *
 * ## Why columns and not JSON
 *
 * Every consumer is plain Java with no AndroidX and no JSON helper. A one-row cursor read by
 * column name costs the client four lines; a JSON blob costs it a parser and an error path.
 * Reading by *name* is also what makes this forward-compatible: a later release may append
 * columns, and a client that indexes by name is unaffected.
 *
 * Colours are ARGB packed into a `Long`, the same representation [ThemeColors] stores. A client
 * hands the value straight to `View.setBackgroundColor(( int ) value)`.
 */
object ThemeContract {

    /**
     * The authority of the *release* launcher — the one every suite app queries.
     *
     * The provider is declared as `${'$'}{applicationId}.theme`, so the `.debug` variant answers on
     * its own authority instead of colliding with the release install (two packages declaring one
     * authority cannot both be installed). Clients deliberately do not follow the variant: a
     * debug launcher on the bench must not restyle the suite out from under the release build
     * that is actually running the car.
     */
    const val AUTHORITY = "com.reveng.carlauncher.theme"

    /**
     * The single row describing what the launcher is painting *right now* — the day or night
     * variant already resolved, so a client never has to know which one applies.
     */
    // Lazy, not eager: `Uri` is a framework class with no JVM implementation, so building it in
    // this object's initializer would make merely *touching* the column names throw in a unit
    // test. The row mapping below is the part worth testing, and it must stay reachable without
    // an emulator.
    val ACTIVE_URI: Uri by lazy { activeUri(AUTHORITY) }

    /** The same row on an arbitrary authority — used by the launcher for its own variant. */
    fun activeUri(authority: String): Uri = Uri.parse("content://$authority/active")

    /** The authority this install serves on, given its applicationId. */
    fun authorityFor(applicationId: String): String = "$applicationId.theme"

    const val COL_THEME_ID = "theme_id"
    const val COL_THEME_NAME = "theme_name"

    /** 1 when the resolved variant is the night one, 0 for day. */
    const val COL_NIGHT = "night"

    const val COL_BACKGROUND = "background"
    const val COL_SURFACE = "surface"
    const val COL_SURFACE_VARIANT = "surface_variant"
    const val COL_PRIMARY = "primary"
    const val COL_ON_BACKGROUND = "on_background"
    const val COL_ON_SURFACE = "on_surface"
    const val COL_ON_SURFACE_MUTED = "on_surface_muted"
    const val COL_ERROR = "error"
    const val COL_ACCENT2 = "accent2"
    const val COL_ACCENT3 = "accent3"

    /** Cursor column order. Clients must read by name; this order is an implementation detail. */
    val COLUMNS: Array<String> = arrayOf(
        COL_THEME_ID,
        COL_THEME_NAME,
        COL_NIGHT,
        COL_BACKGROUND,
        COL_SURFACE,
        COL_SURFACE_VARIANT,
        COL_PRIMARY,
        COL_ON_BACKGROUND,
        COL_ON_SURFACE,
        COL_ON_SURFACE_MUTED,
        COL_ERROR,
        COL_ACCENT2,
        COL_ACCENT3,
    )

    /**
     * One published palette. [colors] is an already-resolved variant, not a whole [CarTheme]:
     * picking day vs night is the launcher's job, because only the launcher receives the
     * illumination broadcast.
     */
    data class Snapshot(
        val themeId: String,
        val themeName: String,
        val night: Boolean,
        val colors: ThemeColors,
    )

    /**
     * The cursor row for [snapshot], in [COLUMNS] order.
     *
     * `accent2` / `accent3` are stored as 0 when unset and fall back to `primary` — that rule
     * lives here rather than in each of twenty-six clients, so an app can paint a three-accent
     * theme and a one-accent theme with the same code.
     */
    fun row(snapshot: Snapshot): Array<Any> {
        val c = snapshot.colors
        return arrayOf(
            snapshot.themeId,
            snapshot.themeName,
            if (snapshot.night) 1 else 0,
            c.background,
            c.surface,
            c.surfaceVariant,
            c.primary,
            c.onBackground,
            c.onSurface,
            c.onSurfaceMuted,
            c.error,
            if (c.accent2 != 0L) c.accent2 else c.primary,
            if (c.accent3 != 0L) c.accent3 else c.primary,
        )
    }
}
