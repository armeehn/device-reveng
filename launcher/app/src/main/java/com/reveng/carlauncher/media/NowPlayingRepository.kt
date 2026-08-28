package com.reveng.carlauncher.media

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.reveng.carlauncher.carlib.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Immutable snapshot of the currently-active media session, or null if nothing is playing.
 *
 * v0.9 (Media 2.0) adds the seek-bar fields ([positionMs]/[durationMs]/[positionTimestamp]/
 * [speed]/[canSeek]) plus the source-app identity ([sourcePackage]/[sourceLabel]) and the
 * number of concurrent sessions ([sessionCount], drives the "tap to cycle" chip).
 */
data class NowPlaying(
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val isPlaying: Boolean,
    val hasPrev: Boolean,
    val hasNext: Boolean,
    // ---- v0.9 seek bar ----
    /** Position at [positionTimestamp], in ms (< 0 if unknown). */
    val positionMs: Long = -1L,
    /** Track duration in ms (<= 0 if unknown / live stream). */
    val durationMs: Long = -1L,
    /** SystemClock.elapsedRealtime() base for [positionMs] (for live interpolation). */
    val positionTimestamp: Long = 0L,
    /** Playback speed (usually 1.0 while playing, 0 while paused). */
    val speed: Float = 1f,
    /** True when the session advertises ACTION_SEEK_TO. */
    val canSeek: Boolean = false,
    // ---- v0.9 source chip ----
    val sourcePackage: String? = null,
    val sourceLabel: String? = null,
    /** Number of active sessions (>1 => the source chip offers "tap to cycle"). */
    val sessionCount: Int = 1,
) {
    /**
     * Live position interpolated to [now] (elapsedRealtime). While playing, advances the
     * captured [positionMs] by wall-clock * [speed]; clamped to [durationMs] when known.
     */
    fun livePositionMs(now: Long = SystemClock.elapsedRealtime()): Long {
        if (positionMs < 0) return 0L
        val base = if (isPlaying) positionMs + ((now - positionTimestamp) * speed).toLong()
        else positionMs
        return if (durationMs > 0) base.coerceIn(0L, durationMs) else base.coerceAtLeast(0L)
    }
}

/**
 * v2.6 — one selectable playback source for MediaScreen's picker.
 *
 * Only real [MediaSession] owners appear here. The vendor's own sources (Bluetooth, USB, the
 * built-in player) are switched through the AIDL `sendMode(int, boolean)` — ordinal 1, confirmed
 * — but the *value table* for that int is nowhere in the decompile, so we do not guess it:
 * writing an unverified mode opcode would put the head unit into an unknown source. MediaScreen
 * shows the vendor's current source read-only via `getValidModeTitleInfor()` instead, and
 * switching waits on an on-device capture of the value table.
 */
data class MediaSource(
    val packageName: String,
    val label: String,
    val isPlaying: Boolean,
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

    private val _sources = MutableStateFlow<List<MediaSource>>(emptyList())
    /**
     * v2.6 — every active session, for MediaScreen's source picker. [cycleSession] only rotates
     * blindly, which is all a small card can offer; a full screen can show the choices and let
     * the driver pick one directly.
     */
    val sources: StateFlow<List<MediaSource>> = _sources.asStateFlow()

    private var controller: MediaController? = null

    /** Last-known active session list (kept so [cycleSession] can rotate through them). */
    private var lastControllers: List<MediaController> = emptyList()

    /**
     * v0.9 — when the user taps the source chip to cycle, we pin their choice so it survives
     * re-scans (until that session dies or they cycle again).
     */
    @Volatile
    private var pinnedPackage: String? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() { controller = null; publish() }
    }

    private val sessionsChanged =
        MediaSessionManager.OnActiveSessionsChangedListener { pickController(it) }

    /** Main-thread handler so MediaController callbacks are delivered on a Looper thread. */
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var sessionsListenerRegistered = false

    /**
     * Start observing. If the notification listener isn't enabled yet, tries to enable it via
     * root (best-effort) — [MediaSessionManager.getActiveSessions] throws SecurityException
     * without it. In that case we register the sessions listener AND do the initial scan on the
     * main thread *after* root-enabling, so both the session-change callbacks and the per-session
     * MediaController callbacks are actually installed (previously they never were).
     */
    fun start(scope: CoroutineScope) {
        val mgr = sessionManager ?: return
        if (registerSessionsListener(mgr)) return
        scope.launch(Dispatchers.IO) {
            ensureListenerEnabled()
            // registerCallback()/getActiveSessions() need a Looper + the permission — do them on main.
            withContext(Dispatchers.Main) { registerSessionsListener(mgr) }
        }
    }

    /**
     * Register the active-sessions listener and run the initial scan. Idempotent. Returns true
     * if the listener is now installed, false if it failed (permission not granted yet).
     * MUST be called on the main thread.
     */
    private fun registerSessionsListener(mgr: MediaSessionManager): Boolean {
        if (sessionsListenerRegistered) return true
        return runCatching {
            mgr.addOnActiveSessionsChangedListener(sessionsChanged, listenerComponent)
            sessionsListenerRegistered = true
            pickController(mgr.getActiveSessions(listenerComponent))
        }.onFailure {
            Log.w(TAG, "getActiveSessions failed (listener not enabled yet?): ${it.message}")
        }.isSuccess
    }

    fun stop() {
        sessionManager?.let { runCatching { it.removeOnActiveSessionsChangedListener(sessionsChanged) } }
        sessionsListenerRegistered = false
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

    /** v0.9 — seek the active session to [positionMs] (no-op if unsupported / unbound). */
    fun seekTo(positionMs: Long) {
        controller?.transportControls?.seekTo(positionMs.coerceAtLeast(0L))
    }

    /**
     * v0.9 — cycle to the next active media session (source chip tap). Rotates through the
     * last-known active list and pins the choice so a re-scan keeps it selected.
     */
    fun cycleSession() {
        val list = lastControllers
        if (list.size < 2) return
        val curPkg = controller?.packageName
        val idx = list.indexOfFirst { it.packageName == curPkg }
        val nextC = list[(idx + 1).mod(list.size)]
        pinnedPackage = nextC.packageName
        selectController(nextC)
    }

    /**
     * v2.6 — select a specific session by package (MediaScreen's source picker). Pins the
     * choice like [cycleSession] does, so a re-scan doesn't hand the screen back to whichever
     * app happens to be playing.
     */
    fun selectSession(packageName: String) {
        val chosen = lastControllers.firstOrNull { it.packageName == packageName } ?: return
        pinnedPackage = packageName
        selectController(chosen)
    }

    // ---- internals ----------------------------------------------------------
    private fun pickController(controllers: List<MediaController>?) {
        lastControllers = controllers ?: emptyList()
        // v2.6: publish the pickable list before choosing, so the picker shows every session
        // even when the one we end up selecting dies immediately after.
        _sources.value = lastControllers.map { c ->
            MediaSource(
                packageName = c.packageName,
                label = appLabel(c.packageName) ?: c.packageName,
                isPlaying = c.playbackState?.state == PlaybackState.STATE_PLAYING,
            )
        }
        // If the user pinned a source and it's still active, keep it. Otherwise prefer a
        // session that is actually playing; else the first one.
        val pinned = pinnedPackage?.let { p -> lastControllers.firstOrNull { it.packageName == p } }
        if (pinnedPackage != null && pinned == null) pinnedPackage = null // pin died
        val chosen = pinned
            ?: lastControllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: lastControllers.firstOrNull()
        selectController(chosen)
    }

    private fun selectController(chosen: MediaController?) {
        if (chosen?.sessionToken == controller?.sessionToken) { publish(); return }
        controller?.unregisterCallback(controllerCallback)
        controller = chosen
        // Explicit main-looper handler: registerCallback() without one uses the *calling*
        // thread's Looper and throws if there is none.
        controller?.registerCallback(controllerCallback, mainHandler)
        publish()
    }

    private fun publish() {
        val c = controller
        if (c == null) { _state.value = null; return }
        val md = c.metadata
        val ps = c.playbackState
        val actions = ps?.actions ?: 0L
        val durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L
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
            positionMs = ps?.position ?: -1L,
            durationMs = durationMs,
            positionTimestamp = ps?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
            speed = ps?.playbackSpeed ?: 1f,
            canSeek = actions and PlaybackState.ACTION_SEEK_TO != 0L,
            sourcePackage = c.packageName,
            sourceLabel = appLabel(c.packageName),
            sessionCount = lastControllers.size.coerceAtLeast(1),
        )
    }

    /** Resolve a package's user-visible label; falls back to the package name. */
    private fun appLabel(pkg: String?): String? {
        if (pkg == null) return null
        val pm = context.packageManager
        return runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrElse { pkg.substringAfterLast('.') }
    }

    /** True if our listener component is in the enabled_notification_listeners setting. */
    private fun isListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        val me = listenerComponent.flattenToString()
        return flat.split(":").any { it.equals(me, ignoreCase = true) }
    }

    /**
     * Root-enable the notification listener so getActiveSessions() is permitted. The re-scan and
     * listener registration happen in [registerSessionsListener] on the main thread after this
     * returns — they must NOT run here (this executes on Dispatchers.IO, which has no Looper).
     */
    private fun ensureListenerEnabled() {
        if (isListenerEnabled()) return
        val comp = listenerComponent.flattenToString()
        val r = RootShell.exec("cmd notification allow_listener '$comp'")
        Log.i(TAG, "allow_listener $comp -> code=${r.code} ${r.stdout}")
    }
}
