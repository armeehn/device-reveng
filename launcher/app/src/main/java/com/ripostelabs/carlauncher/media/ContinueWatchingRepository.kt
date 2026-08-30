package com.ripostelabs.carlauncher.media

import android.content.Context
import android.os.SystemClock
import com.ripostelabs.carlauncher.data.WatchEntry
import com.ripostelabs.carlauncher.data.WatchHistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * v2.7 — the continue-watching shelf, built from the only Jellyfin signal a launcher can read
 * without the server's credentials: the client app's own [android.media.session.MediaSession].
 *
 * ## Why it is not the server's Continue Watching row
 *
 * Jellyfin's real resume list is `GET /Users/{userId}/Items/Resume`, authenticated with an API
 * key or an access token. Reaching it would mean this app storing a tailnet URL and a credential
 * — configuration that belongs to the owner, not to a launcher APK, and something the brief
 * explicitly rules out. So we do not fake one. We watch what plays on *this unit* and remember it.
 *
 * What that buys, and what it costs:
 * - CONFIRMED by construction: any app that publishes a MediaSession appears here, Jellyfin
 *   included, with title, artist/subtitle and (when the app publishes them) position and duration.
 * - Missing: anything watched on a phone or TV, since it never touched this device's media stack.
 * - Missing: resuming a specific item. Deep-linking an episode needs its server GUID, so tapping
 *   a row opens Jellyfin at its own home screen, where the real resume row is one tap away.
 * - Approximate: the position is whatever the session last published. A player that stops
 *   updating on pause leaves the row slightly stale. It is a hint, not a bookmark.
 *
 * ## Scope of what gets recorded
 *
 * Only sessions from a Jellyfin client ([JellyfinApp.isJellyfin]). The media stack sees every
 * player on the unit — Spotify, the vendor player, whatever the phone is projecting — and a
 * "continue watching" shelf full of radio adverts would be worse than an empty one. Widening this
 * later is one predicate change; narrowing it after users have a history is not.
 */
class ContinueWatchingRepository(
    private val context: Context,
    private val store: WatchHistoryStore,
) {

    /** The shelf, newest first. Empty until something has actually played here. */
    val shelf: StateFlow<List<WatchEntry>> = store.entries

    /** Title of the last thing we wrote, so an unchanged track doesn't rewrite DataStore. */
    private var lastTitle: String? = null

    /** elapsedRealtime of the last write, for the [RECORD_INTERVAL_MS] throttle. */
    private var lastWriteAt = 0L

    /**
     * Follow [state] and record Jellyfin playback.
     *
     * Throttled deliberately. `NowPlayingRepository.publish()` fires on every metadata and
     * playback-state callback, which during a seek is many per second; a DataStore commit each
     * time would put the launcher's UI thread behind a queue of disk writes for a shelf nobody is
     * looking at. A new title writes immediately; the same title refreshes its position at most
     * once per [RECORD_INTERVAL_MS].
     */
    fun observe(scope: CoroutineScope, state: StateFlow<NowPlaying?>) {
        scope.launch {
            state.collect { now -> onNowPlaying(now) }
        }
    }

    private suspend fun onNowPlaying(now: NowPlaying?) {
        if (now == null || !JellyfinApp.isJellyfin(now.sourcePackage)) {
            return
        }
        val pkg = now.sourcePackage ?: return
        if (now.title.isBlank()) {
            return
        }

        val elapsed = SystemClock.elapsedRealtime()
        val isNewTitle = now.title != lastTitle
        if (!isNewTitle && elapsed - lastWriteAt < RECORD_INTERVAL_MS) {
            return
        }

        lastTitle = now.title
        lastWriteAt = elapsed

        store.record(
            WatchEntry(
                packageName = pkg,
                title = now.title,
                subtitle = now.artist,
                // livePositionMs() interpolates from the last published position, which is the
                // closest thing to "where the user actually is" that the session offers.
                positionMs = now.livePositionMs(),
                durationMs = now.durationMs,
                lastSeenAtMs = System.currentTimeMillis(),
            ),
        )
    }

    /** Open Jellyfin. False when no client is installed, so the UI can say so rather than no-op. */
    fun openJellyfin(): Boolean = JellyfinApp.launch(context)

    /** Null when no Jellyfin client is installed — the shelf's empty state keys off this. */
    fun jellyfinLabel(): String? = JellyfinApp.label(context)

    fun forget(scope: CoroutineScope, entry: WatchEntry) {
        scope.launch { store.remove(entry) }
    }

    fun clear(scope: CoroutineScope) {
        scope.launch { store.clear() }
    }

    private companion object {
        /** Refresh an already-recorded title's position at most this often. */
        const val RECORD_INTERVAL_MS = 15_000L
    }
}
