package com.ripostelabs.carlauncher.data

/**
 * FeedWatch — is the reverse picture live, judged by the AIS client's frame counter.
 *
 * A stream that stops keeps its last frame on the texture. With the feed kept warm all drive
 * (ReverseCameraWindow), that frame can be minutes old by the time R is selected: the owner saw a
 * blurry black-and-white frame from driving shown as the reverse picture (2026-10-09). The
 * picture now shows only while the counter moves:
 *
 *     poll get_frame_count ─▶ frames(count) ─▶ moved within STALE_MS? ─▶ live: the picture
 *                                                                  └─▶ not: black + notice
 *     shown (reverse) and not live ─▶ reopenDue ─▶ reopen through AisCameraWorker, at most
 *                                                  once per REOPEN_EVERY_MS
 *
 * A poll that never answers (a stuck client) is no frame. The counter restarts at 0 on a reopen,
 * so any new non-zero value counts as frames, not only a larger one.
 */
class FeedWatch {

    private var lastCount: Int? = null
    private var lastFrameAt: Long? = null
    private var lastReopenAt: Long? = null

    /** One poll's answer at [nowMs]: the frames drawn so far, or null while nothing streams. */
    fun frames(count: Int?, nowMs: Long) {
        if (count != null && count > 0 && count != lastCount) {
            lastFrameAt = nowMs
        }
        lastCount = count
    }

    /** True while a new frame came within [STALE_MS]. */
    fun live(nowMs: Long): Boolean {
        val at = lastFrameAt ?: return false
        return nowMs - at < STALE_MS
    }

    /** Reopen now: the picture is [shown] (reverse), not live, and no reopen is still warming up. */
    fun reopenDue(shown: Boolean, nowMs: Long): Boolean {
        if (!shown || live(nowMs)) {
            return false
        }
        val at = lastReopenAt ?: return true
        return nowMs - at >= REOPEN_EVERY_MS
    }

    /** The stream was reopened (at [nowMs]); the frame on the texture is old until new ones come. */
    fun reopened(nowMs: Long? = null) {
        lastCount = null
        lastFrameAt = null
        if (nowMs != null) {
            lastReopenAt = nowMs
        }
    }

    companion object {
        /** ~30 frames at the PR2000's 30 fps: one missed poll is not a lost picture. */
        const val STALE_MS = 1_000L

        /** Longer than a warm-up (AisCameraWorker, 20 s) plus its opens, so a reopen never cuts one short. */
        const val REOPEN_EVERY_MS = 25_000L

        /** How often the session asks the client for its counter. */
        const val POLL_MS = 250L
    }
}
