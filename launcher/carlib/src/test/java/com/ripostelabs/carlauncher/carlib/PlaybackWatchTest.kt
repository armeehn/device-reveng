package com.ripostelabs.carlauncher.carlib

import android.media.AudioAttributes
import com.ripostelabs.carlauncher.carlib.ArmAudioRoute.Playback
import org.junit.Assert.assertEquals
import org.junit.Test

/** How the active players fold into the vendor's nav / system bytes and the media-source triggers. */
class PlaybackWatchTest {

    private fun player(usage: Int, content: Int = AudioAttributes.CONTENT_TYPE_UNKNOWN) = usage to content

    @Test
    fun nothingPlayingIsSilence() {
        assertEquals(Playback(), PlaybackWatch.summarise(emptyList()))
    }

    /** A music player is a media source and system sound. */
    @Test
    fun mediaPlayerIsMediaAndSystem() {
        val playback = PlaybackWatch.summarise(listOf(player(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC)))

        assertEquals(Playback(media = true, any = true), playback)
    }

    /** Guidance is the nav byte and nothing else; a notification is system sound only. */
    @Test
    fun guidanceIsNavAndNotificationIsSystemOnly() {
        val nav = PlaybackWatch.summarise(listOf(player(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)))
        val ping = PlaybackWatch.summarise(listOf(player(AudioAttributes.USAGE_NOTIFICATION)))

        assertEquals(Playback(nav = true, any = true), nav)
        assertEquals(Playback(any = true), ping)
    }

    /** CONTENT_TYPE_MOVIE on a media player is the videoplayer's SRC_MOVIE. */
    @Test
    fun movieContentMarksMovie() {
        val playback = PlaybackWatch.summarise(listOf(player(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MOVIE)))

        assertEquals(Playback(media = true, movie = true, any = true), playback)
    }
}
