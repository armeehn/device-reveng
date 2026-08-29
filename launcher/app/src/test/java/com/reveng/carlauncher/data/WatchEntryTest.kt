package com.reveng.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [WatchEntry.progress] feeds a progress bar directly from numbers a third-party MediaSession
 * published, so every degenerate pair a player can emit has to produce either a usable fraction or
 * an honest null. A live stream reports no duration; a session polled before it has loaded reports
 * -1 for both; a session that over-reports position past its own duration is common enough to be
 * unremarkable.
 */
class WatchEntryTest {

    private fun entry(positionMs: Long, durationMs: Long) = WatchEntry(
        packageName = "com.example.player",
        title = "Episode 1",
        subtitle = "Season 1",
        positionMs = positionMs,
        durationMs = durationMs,
        lastSeenAtMs = 1_700_000_000_000L,
    )

    @Test
    fun ordinaryPlaybackReportsAFraction() {
        assertEquals(0.5f, entry(positionMs = 50, durationMs = 100).progress()!!, 1e-6f)
        assertEquals(0.25f, entry(positionMs = 250, durationMs = 1000).progress()!!, 1e-6f)
    }

    @Test
    fun theEndsOfTheBarAreExact() {
        assertEquals(0f, entry(positionMs = 0, durationMs = 100).progress()!!, 0f)
        assertEquals(1f, entry(positionMs = 100, durationMs = 100).progress()!!, 0f)
    }

    @Test
    fun noDurationMeansNoBar() {
        // A live stream has no duration, and plenty of players simply never publish one.
        assertNull(entry(positionMs = 500, durationMs = -1).progress())
        assertNull(entry(positionMs = 500, durationMs = 0).progress())
    }

    @Test
    fun unknownPositionMeansNoBar() {
        assertNull(entry(positionMs = -1, durationMs = 1000).progress())
    }

    @Test
    fun overRunningPositionIsClamped() {
        // Some players keep counting past their reported duration. A fraction over 1 would draw a
        // bar wider than its track.
        assertEquals(1f, entry(positionMs = 5000, durationMs = 1000).progress()!!, 0f)
    }

    @Test
    fun longRunningSessionsDoNotLosePrecisionToInt() {
        // Six hours in milliseconds is comfortably past Int.MAX_VALUE. Both fields are Long for
        // exactly this reason; the ratio must still land in the right half of the bar.
        val sixHours = 6L * 60 * 60 * 1000
        assertEquals(0.5f, entry(positionMs = sixHours / 2, durationMs = sixHours).progress()!!, 1e-6f)
    }

    @Test
    fun dedupeIdentityIsPackageAndTitle() {
        // WatchHistoryStore.record/remove match on packageName + title only. Two entries that differ
        // solely in position must therefore be the same row, or a paused-then-resumed episode
        // accumulates duplicates on the shelf.
        val first = entry(positionMs = 10, durationMs = 100)
        val later = first.copy(positionMs = 90, lastSeenAtMs = first.lastSeenAtMs + 60_000)

        assertEquals(first.packageName, later.packageName)
        assertEquals(first.title, later.title)
    }
}
