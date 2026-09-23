package com.ripostelabs.carlauncher.carlib

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.util.Log

/**
 * PlaybackWatch — "is any Android player started", the public-API stand-in for the vendor's
 * zxw_io START_SND_PID / STOP_SND_PID uevent (EventService.java:13542-13580).
 *
 *     AudioFlinger players ──▶ AudioPlaybackCallback (main looper) ──▶ onChange(playing, what)
 *
 * [onChange] runs on the main thread, once at [start] and on every change of the active set,
 * with the set folded by [summarise] into the route's triggers.
 * `what` lists the active players (usage, content type) so logcat shows which playback appeared,
 * e.g. the projection's USAGE_MEDIA track (usage=1).
 *
 * Active only: without MODIFY_AUDIO_ROUTING (the launcher does not hold it) AudioService hands
 * out anonymised copies of STARTED players and nothing else, so a non-empty list is "playing".
 * `isActive` itself is a system API.
 */
class PlaybackWatch(
    context: Context,
    private val onChange: (playback: ArmAudioRoute.Playback, what: String) -> Unit,
) {

    private val audio = context.getSystemService(AudioManager::class.java)

    private var last: String? = null

    private val callback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            report(configs)
        }
    }

    fun start() {
        audio.registerAudioPlaybackCallback(callback, null)
        report(audio.activePlaybackConfigurations)
    }

    fun stop() {
        audio.unregisterAudioPlaybackCallback(callback)
    }

    private fun report(configs: List<AudioPlaybackConfiguration>) {
        val what = configs.joinToString(prefix = "[", postfix = "]") {
            "usage=${it.audioAttributes.usage} content=${it.audioAttributes.contentType}"
        }

        // The callback fires on volume and routing changes too; log only a new active set.
        if (what == last) {
            return
        }
        last = what
        Log.i(LOG_TAG, "active players: $what")
        onChange(summarise(configs.map { it.audioAttributes.usage to it.audioAttributes.contentType }), what)
    }

    companion object {
        private const val LOG_TAG = "PlaybackWatch"

        /** The vendor's SRC_MUSIC / SRC_MOVIE players: media, game, or an app that set nothing. */
        private val MEDIA_USAGES = setOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)

        /**
         * Fold `(usage, contentType)` per active player into the route's triggers. The nav byte is
         * what eventcenter derives from a navigation app's pid (EventService.java:1237, :13545-13568);
         * here it is the guidance usage every navigation app declares.
         */
        fun summarise(players: List<Pair<Int, Int>>): ArmAudioRoute.Playback {
            val media = players.filter { (usage, _) -> usage in MEDIA_USAGES }
            return ArmAudioRoute.Playback(
                media = media.isNotEmpty(),
                movie = media.any { (_, content) -> content == AudioAttributes.CONTENT_TYPE_MOVIE },
                nav = players.any { (usage, _) -> usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE },
                any = players.isNotEmpty(),
            )
        }
    }
}
