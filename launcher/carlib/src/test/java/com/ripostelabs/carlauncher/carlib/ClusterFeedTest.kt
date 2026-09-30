package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/** RAV4-182: what reaches the cluster, and when. Stock sends a field only when it changes. */
class ClusterFeedTest {

    private val sent = mutableListOf<IntArray>()
    private val feed = ClusterFeed { sent += it.payload }

    private fun cmds() = sent.map { it[1] to it[2] }

    @Test
    fun mediaSendsTitleArtistAlbumOnceEach() {
        feed.onMedia(title = "Song", artist = "Band")
        feed.onMedia(title = "Song", artist = "Band")

        // Stock order: title 92, artist 94, album 93 ("unknown" when the source has none).
        assertEquals(listOf(0x92, 0x94, 0x93), sent.map { it[1] })
    }

    @Test
    fun blankMediaReadsUnknownAsStock() {
        feed.onMedia(title = null, artist = "")

        assertEquals('u'.code, sent[0][2])
    }

    @Test
    fun radioSendsOnStationChangeOnly() {
        feed.onRadio(RadioState(band = 0, freq = 9810, presetNumber = 0))
        feed.onRadio(RadioState(band = 0, freq = 9810, presetNumber = 0, stereo = true))
        feed.onRadio(RadioState(band = 0, freq = 9830, presetNumber = 0))

        assertEquals(2, sent.size)
    }

    @Test
    fun radioBeforeTheTunerReportsSendsNothing() {
        feed.onRadio(RadioState())

        assertEquals(0, sent.size)
    }

    @Test
    fun callSendsStateNumberAndName() {
        feed.onCall(hshf = 5, number = "2505550100", name = "Mom")
        feed.onCall(hshf = 5, number = "2505550100", name = "Mom")
        feed.onCall(hshf = 6, number = "2505550100", name = "Mom")

        // Ringing with the number, the name, then active.
        assertEquals(listOf(0xCD to 1, 0xC4 to 'M'.code, 0xCD to 4), cmds())
    }

    @Test
    fun clockAlwaysSends() {
        val now = LocalDateTime.of(2026, 9, 30, 14, 5)
        feed.onClock(now, ClusterText.HourFormat.H24)
        feed.onClock(now, ClusterText.HourFormat.H24)

        assertEquals(2, sent.size)
    }
}
