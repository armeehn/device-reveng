package com.ripostelabs.carlauncher.media

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * v2.7 — the Jellyfin ("jellybelly") client, as far as a launcher on this unit can actually see it.
 *
 * **What this is not.** There is no HTTP client here, no server URL, no API key. The server lives
 * on the owner's tailnet and its address and credentials are theirs, not ours; a launcher that
 * shipped a hardcoded base URL would be wrong on every other unit and a liability on this one.
 * Everything below works off what the *installed Jellyfin app* exposes to any app on the device:
 * its launch intent, and — once it is playing — its MediaSession.
 *
 * **The consequence, stated plainly:** we cannot show the server's real "Continue Watching" row.
 * That row is a `/Users/{id}/Items/Resume` call behind authentication. What
 * [ContinueWatchingRepository] shows instead is what *this head unit* has played, recovered from
 * MediaSession metadata. For a car that is arguably the more useful list, but it is a different
 * list, and it starts empty.
 *
 * The candidate package names are the upstream Android application IDs published by the Jellyfin
 * project. **GUESSED** for this unit specifically: which of them — if either — the owner has
 * side-loaded. [installedPackage] answers that at runtime rather than assuming.
 */
object JellyfinApp {

    private const val TAG = "JellyfinApp"

    /**
     * Upstream application IDs, most likely first. `org.jellyfin.mobile` is the phone/tablet
     * client (a WebView over the server's web UI); `org.jellyfin.androidtv` is the leanback one.
     * A 1920x720 head unit is a touch panel, so the mobile client is the better fit, but the TV
     * client is what people reach for on car units with a rotary controller — accept either.
     */
    private val CANDIDATE_PACKAGES = listOf(
        "org.jellyfin.mobile",
        "org.jellyfin.androidtv",
    )

    /** The Jellyfin client installed on this unit, or null if none is. */
    fun installedPackage(context: Context): String? {
        val pm = context.packageManager
        return CANDIDATE_PACKAGES.firstOrNull { pkg ->
            runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull() != null
        }
    }

    /** True when [pkg] is a Jellyfin client — used to pick its sessions out of the media stack. */
    fun isJellyfin(pkg: String?): Boolean = pkg != null && pkg in CANDIDATE_PACKAGES

    /**
     * Bring Jellyfin to the front. Returns false when nothing could be launched, so the caller can
     * say "not installed" instead of swallowing the tap.
     *
     * There is no deep link to a specific item: resuming a particular episode needs the server's
     * item GUID, which only an authenticated API call yields. The app reopens on its own home
     * screen, where its real Continue Watching row is one tap away.
     */
    fun launch(context: Context): Boolean {
        val pkg = installedPackage(context) ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }
            .onFailure { Log.w(TAG, "launch $pkg failed: ${it.message}") }
            .getOrDefault(false)
    }

    /**
     * Human-readable name of the installed client, for empty states and the shelf header.
     * Falls back to the package name the way [NowPlayingRepository] does.
     */
    fun label(context: Context): String? {
        val pkg = installedPackage(context) ?: return null
        val pm = context.packageManager
        return runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrElse { pkg.substringAfterLast('.') }
    }

    /**
     * Move the Jellyfin tile to the front of a quick-launch list — the "preset entry" half of the
     * v2.7 integration. Ordering only: nothing is added that the drawer would not already show, so
     * a unit without Jellyfin installed sees exactly the list it saw before.
     */
    fun <T> pinFirst(apps: List<T>, packageOf: (T) -> String): List<T> {
        val idx = apps.indexOfFirst { isJellyfin(packageOf(it)) }
        if (idx <= 0) {
            return apps
        }
        val pinned = apps[idx]
        return listOf(pinned) + apps.filterIndexed { i, _ -> i != idx }
    }
}
