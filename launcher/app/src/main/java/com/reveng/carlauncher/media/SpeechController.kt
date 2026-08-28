package com.reveng.carlauncher.media

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.reveng.carlauncher.notif.ShelfNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v0.4.2 — a thin TextToSpeech wrapper for short, eyes-free spoken announcements.
 *
 * Today it speaks the now-playing track when [observeNowPlaying] is wired and the user has opted
 * in (`SettingsStore.readNowPlaying`, **off by default** — a launcher that starts talking unasked
 * would be worse than silent). Kept deliberately small: one utterance queue, flush-on-new so a
 * fast track skip doesn't back up a queue of stale titles, and a hard no-op until the engine
 * reports ready so a press during init is dropped rather than crashing.
 */
class SpeechController(context: Context) {

    private val appContext = context.applicationContext
    private val ready = AtomicBoolean(false)
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val locale = Locale.getDefault()
                val supported = (tts?.isLanguageAvailable(locale) ?: TextToSpeech.LANG_NOT_SUPPORTED) >=
                    TextToSpeech.LANG_AVAILABLE
                tts?.language = if (supported) locale else Locale.US
                ready.set(true)
            } else {
                Log.w(TAG, "TTS init failed: $status")
            }
        }
    }

    val isReady: Boolean get() = ready.get()

    /**
     * Speak [text]. [flush] true interrupts anything currently speaking (right for a track change
     * that makes the previous announcement stale); false queues after it (right for notifications,
     * where cutting off the previous one loses information). No-op until the engine is ready.
     */
    fun speak(text: String, flush: Boolean = true) {
        val engine = tts ?: return
        if (!ready.get() || text.isBlank()) return
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        engine.speak(text, mode, null, UTTERANCE_ID)
    }

    /**
     * Announce the now-playing track whenever it changes to a new playing item and [enabled] is on.
     * Dedups by title+artist so a position/art update mid-track doesn't re-announce. Collected on
     * the caller's [scope]; nothing is spoken while [enabled] is false.
     */
    fun observeNowPlaying(
        scope: CoroutineScope,
        nowPlaying: Flow<NowPlaying?>,
        enabled: Flow<Boolean>,
    ) {
        scope.launch {
            var lastKey: String? = null
            combine(nowPlaying, enabled) { np, on -> np to on }
                .distinctUntilChanged()
                .collect { (np, on) ->
                    if (!on || np == null || !np.isPlaying || np.title.isBlank()) {
                        // Reset so re-enabling, or resuming the same track, announces again.
                        if (!on) lastKey = null
                        return@collect
                    }
                    val key = np.title + "" + np.artist
                    if (key != lastKey) {
                        lastKey = key
                        speak(announce(np))
                    }
                }
        }
    }

    private fun announce(np: NowPlaying): String =
        if (np.artist.isBlank()) "Now playing ${np.title}"
        else "Now playing ${np.title} by ${np.artist}"

    /**
     * Announce newly-arrived shelf notifications while [enabled] is on. On the first emission after
     * enabling it snapshots the current keys WITHOUT speaking, so switching this on doesn't read out
     * the whole existing backlog — only notifications that arrive afterwards are spoken. Queued
     * (not flushed) so a burst is read in order rather than clipped to the last one.
     */
    fun observeNotifications(
        scope: CoroutineScope,
        notifications: Flow<List<ShelfNotification>>,
        enabled: Flow<Boolean>,
    ) {
        scope.launch {
            var seen: Set<String>? = null
            combine(notifications, enabled) { list, on -> list to on }
                .collect { (list, on) ->
                    if (!on) {
                        seen = null // reset: re-enabling re-snapshots rather than replaying the backlog
                        return@collect
                    }
                    val keys = list.mapTo(HashSet()) { it.key }
                    val prev = seen
                    seen = keys
                    if (prev == null) return@collect // first pass after enabling: snapshot only
                    list.filter { it.key !in prev }
                        .sortedBy { it.postedAtMs }
                        .forEach { speak(announceNotification(it), flush = false) }
                }
        }
    }

    private fun announceNotification(n: ShelfNotification): String {
        val head = if (n.title.isBlank()) n.appLabel else "${n.appLabel}: ${n.title}"
        return if (n.text.isBlank()) head else "$head. ${n.text}"
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready.set(false)
    }

    private companion object {
        const val TAG = "SpeechController"
        const val UTTERANCE_ID = "carlauncher-tts"
    }
}
