package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reverse picture shows only while frames keep arriving. Owner, 2026-10-09: the warm feed
 * stopped mid-drive and reverse showed the last frame, a blurry black-and-white one from driving,
 * as if it were live.
 */
class FeedWatchTest {

    private val watch = FeedWatch()
    private val t0 = 1_000_000L

    @Test
    fun noFrameYetIsNotLive() {
        assertFalse(watch.live(t0))
        watch.frames(0, t0)
        assertFalse(watch.live(t0 + 100))
    }

    @Test
    fun advancingFramesAreLive() {
        watch.frames(10, t0)
        watch.frames(25, t0 + 500)
        assertTrue(watch.live(t0 + 600))
    }

    // The bug: a stream that stops leaves its last frame on the texture.
    @Test
    fun aCountThatStopsIsStaleAfterTheWindow() {
        watch.frames(10, t0)
        watch.frames(25, t0 + 500)
        watch.frames(25, t0 + 1_000)
        assertTrue(watch.live(t0 + 500 + FeedWatch.STALE_MS - 1))
        assertFalse(watch.live(t0 + 500 + FeedWatch.STALE_MS))
    }

    // A stuck client never answers the poll: silence counts as no frames.
    @Test
    fun noAnswersAtAllGoStale() {
        watch.frames(25, t0)
        watch.frames(26, t0 + 100)
        assertFalse(watch.live(t0 + 100 + FeedWatch.STALE_MS))
    }

    // A reopen restarts the client's counter at 0; the first frames after it count as new.
    @Test
    fun aRestartedCounterCountsAsFrames() {
        watch.frames(900, t0)
        watch.frames(901, t0 + 100)
        watch.reopened()
        watch.frames(0, t0 + 200)
        assertFalse(watch.live(t0 + 200 + FeedWatch.STALE_MS))
        watch.frames(3, t0 + 200 + FeedWatch.STALE_MS)
        assertTrue(watch.live(t0 + 300 + FeedWatch.STALE_MS))
    }

    // Shown and stale: reopen now. Not again before a warm-up (up to 20 s) can have finished.
    @Test
    fun reopenOnlyWhenShownAndStaleAndNotTooOften() {
        assertFalse(watch.reopenDue(shown = false, nowMs = t0))
        assertTrue(watch.reopenDue(shown = true, nowMs = t0))
        watch.reopened(t0)
        assertFalse(watch.reopenDue(shown = true, nowMs = t0 + FeedWatch.REOPEN_EVERY_MS - 1))
        assertTrue(watch.reopenDue(shown = true, nowMs = t0 + FeedWatch.REOPEN_EVERY_MS))

        watch.frames(1, t0 + FeedWatch.REOPEN_EVERY_MS)
        watch.frames(9, t0 + FeedWatch.REOPEN_EVERY_MS + 100)
        assertFalse(watch.reopenDue(shown = true, nowMs = t0 + FeedWatch.REOPEN_EVERY_MS + 200))
    }
}
