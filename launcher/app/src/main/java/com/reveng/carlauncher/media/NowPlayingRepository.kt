package com.reveng.carlauncher.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.util.Log
import com.reveng.carlauncher.carlib.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Immutable snapshot of the currently-active media session, or null if nothing is playing. */
data class NowPlaying(
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val isPlaying: Boolean,
    val hasPrev: Boolean,
    val hasNext: Boolean,
)

/**
 * Reads the active media session across all apps via [MediaSessionManager] and exposes it as
 * a [StateFlow]. Transport controls drive whatever app owns the session.
 *
 * CAR_API §6.3 lists a vendor path (ZXW_MUSIC_* broadcasts / AIDL) — but the standard
 * MediaSession route is app-agnostic (works with Spotify, mpv, YouTube Music, the vendor
 * player) and needs no vendor cooperation, so we use it here.
 */
class NowPlayingRepository(private val context: Context) {

    companion object {
        private const val TAG = "NowPlaying"
    }

    private val listenerComponent =
        ComponentName(context, MediaListenerService::class.java)

    private val sessionManager: MediaSessionManager? =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state.asStateFlow()

    private var controller: MediaController? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() { controller = null; publish() }
    }

    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener { pickController(it) }

    /**
     * Start observing. If the notification listener isn't enabled yet, tries to enable it via
     * root (best-effort) — [MediaSessionManager.getActiveSessions] throws SecurityException
     * without it, which we catch.
     */
    fun start(scope: CoroutineScope) {
        val mgr = sessionManager ?: return
        scope.launch(Dispatchers.IO) { ensureListenerEnabled() }
        runCatching {
            mgr.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent)
            pickController(mgr.getActiveSessions(listenerComponent))
        }.onFailure { Log.w(TAG, "getActiveSessions failed (listener not enabled yet?): ${it.message}") }
    }

    fun stop() {
        sessionManager?.let { runCatching { it.removeOnActiveSessionsChangedListener(sessionsChanged) } }
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    // ---- transport (drives the owning app) ----------------------------------
    fun playPause() {
        val c = controller ?: return
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        if (playing) c.transportControls.pause() else c.transportControls.play()
    }
    fun next() { controller?.transportControls?.skipToNext() }
    fun prev() { controller?.transportControls?.skipToPrevious() }

    // ---- internals ----------------------------------------------------------
    private fun pickController(controllers: List<MediaController>?) {
        // Prefer a session that is actually playing; else the first one.
        val chosen = controllers?.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers?.firstOrNull()

        if (chosen?.sessionToken == controller?.sessionToken) { publish(); return }
        controller?.unregisterCallback(controllerCallback)
        controller = chosen
        controller?.registerCallback(controllerCallback)
        publish()
    }

    private fun publish() {
        val c = controller
        if (c == null) { _state.value = null; return }
        val md = c.metadata
        val ps = c.playbackState
        val actions = ps?.actions ?: 0L
        _state.value = NowPlaying(
            title = md?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString()?.ifBlank { "Unknown" }
                ?: "Unknown",
            artist = md?.getText(MediaMetadata.METADATA_KEY_ARTIST)?.toString()
                ?: md?.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.toString()
                ?: "",
            art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART),
            isPlaying = ps?.state == PlaybackState.STATE_PLAYING,
            hasPrev = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            hasNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
        )
    }

    /** True if our listener component is in the enabled_notification_listeners setting. */
    private fun isListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        val me = listenerComponent.flattenToString()
        return flat.split(":").any { it.equals(me, ignoreCase = true) }
    }

    /** Root-enable the notification listener so getActiveSessions() is permitted. */
    private fun ensureListenerEnabled() {
        if (isListenerEnabled()) return
        val comp = listenerComponent.flattenToString()
        val r = RootShell.exec("cmd notification allow_listener '$comp'")
        Log.i(TAG, "allow_listener $comp -> code=${r.code} ${r.stdout}")
        // Re-scan after enabling (best effort; a fresh getActiveSessions will now succeed).
        if (r.ok) runCatching {
            sessionManager?.let { pickController(it.getActiveSessions(listenerComponent)) }
        }
    }
}
