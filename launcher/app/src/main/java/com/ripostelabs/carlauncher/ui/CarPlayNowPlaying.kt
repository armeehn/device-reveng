package com.ripostelabs.carlauncher.ui

import com.ripostelabs.carlauncher.carlib.CarPlayState
import com.ripostelabs.carlauncher.carlib.Zlink
import com.ripostelabs.carlauncher.media.NowPlaying

/**
 * Stand-in [NowPlaying] for the media card while a phone is projected and no MediaSession
 * is visible, so the card never falls back to "No source connected" during CarPlay.
 *
 * What the head unit exposes for CarPlay audio is only a play flag: zlink's
 * `MAIN_AUDIO_START/STOP` become `setCarPlayValidModeInfor(bool)` (`ZlinkManage.java:296-301`)
 * → `setValidModeInfor("Carplay", "", playing)` (`:587-605`), which carries the protocol name
 * and nothing about the track (`EventService.java:9169-9186`). Zlink's own DEX is packed, so
 * whether and when it posts a MediaSession is UNVERIFIED; the card uses one when the
 * repository sees it and this otherwise.
 *
 * The chip carries [Zlink.PACKAGE] so the existing tap-to-open-CarPlay wiring applies.
 */
internal fun carPlayNowPlaying(state: CarPlayState, title: String, idleSubtitle: String): NowPlaying? {
    if (!state.connected) {
        return null
    }

    return NowPlaying(
        title = title,
        artist = if (state.audioPlaying) "" else idleSubtitle,
        art = null,
        isPlaying = state.audioPlaying,
        hasPrev = false,
        hasNext = false,
        sourcePackage = Zlink.PACKAGE,
        sourceLabel = CARPLAY_SOURCE_LABEL,
    )
}

private const val CARPLAY_SOURCE_LABEL = "CarPlay"
