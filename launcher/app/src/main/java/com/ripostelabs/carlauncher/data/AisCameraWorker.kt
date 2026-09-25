package com.ripostelabs.carlauncher.data

import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AisCameraWorker — [AisCamera] off the caller's thread, with a deadline on open.
 *
 * `open_camera` connects to `ais_server` over qcarcam and blocks while the server is absent.
 * On 2026-09-24 the server never linked, the call ran on the main thread, and the launcher froze
 * on reverse. Every client call now runs on one worker thread, in call order; answers come back
 * through [post] (the main looper in the app).
 *
 *     open(s)       ─▶ worker: [no lock: warm up, see warmUp] ─▶ camera.open
 *                             [─▶ close, wait, open: up to twice on a failure] ─▶ post(onResult(state))
 *                   └▶ timer: no answer after openTimeoutMs ─▶ post(onResult(Failed(NOT_ANSWERING)))
 *     close(release) ─▶ worker: camera.close ─▶ release()     queued behind a stuck open
 *
 * A late answer still counts (a server that comes up late gives the picture after all); an
 * answer from before the last [close] is dropped. [open] and [close] belong to one thread.
 */
class AisCameraWorker<S : Any>(
    private val camera: AisCamera<S>,
    private val worker: ExecutorService,
    private val timer: ScheduledExecutorService,
    private val post: (() -> Unit) -> Unit,
    private val openTimeoutMs: Long = OPEN_TIMEOUT_MS,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    private val signal: Signal? = null,
    private val warmTimeoutMs: Long = WARM_TIMEOUT_MS,
    private val warmPollMs: Long = WARM_POLL_MS,
) {
    /**
     * The PR2000's signal lock, which the worker needs to see and nudge. On the bench it only
     * detects the camera while a stream runs, and the server sizes a stream from that lock:
     * with no lock yet every open is 0 x 0 and never streams (2026-09-25). [DecoderSignal] is the
     * app's root-shell side.
     */
    interface Signal {
        /** True when the decoder has a signal the server can size (camera_status 1..13). */
        fun locked(): Boolean

        /** A fixed mode the server can size, so a first stream starts before any detection. */
        fun forceStreamable()

        /** Reset and auto-detect on the reverse input, as stock does after a bad signal. */
        fun redetect()
    }

    /** Bumped by every open and close: an answer tagged with an older value is stale. */
    @Volatile
    private var generation = 0

    fun open(surface: S, onResult: (AisCamera.State) -> Unit) {
        generation++
        val mine = generation
        val answered = AtomicBoolean(false)
        val deliver = { state: AisCamera.State ->
            post {
                if (generation == mine) {
                    onResult(state)
                }
            }
        }

        val deadline = timer.schedule({
            if (!answered.get()) {
                Log.w(TAG, "open_camera gave no answer in $openTimeoutMs ms")
                deliver(AisCamera.State.Failed(NOT_ANSWERING))
            }
        }, openTimeoutMs, TimeUnit.MILLISECONDS)

        worker.execute {
            // No lock yet (first reverse after power-on): stream in a fixed mode, let the decoder
            // detect under it, then open for real. The notice covers the ~15 s it takes.
            if (signal != null && !signal.locked()) {
                answered.set(true)
                deadline.cancel(false)
                deliver(AisCamera.State.Failed(WARMING_UP))
                warmUp(surface, signal, mine)
            }

            var state = camera.open(surface)

            // The first stream start after a server start can fail while the PR2000 relocks
            // (open_camera -> -8, bench 2026-09-25). Stock closes and reopens on a bad signal
            // (BackcarEvent.java:1428-1434); so does this, unless a close came in meanwhile.
            var retries = 0
            while (state is AisCamera.State.Failed && retries < OPEN_RETRIES && generation == mine) {
                retries++
                Log.i(TAG, "open failed (${state.reason}), retry $retries of $OPEN_RETRIES")
                camera.close()
                Thread.sleep(retryDelayMs)
                state = camera.open(surface)
            }

            answered.set(true)
            deadline.cancel(false)
            deliver(state)
        }
    }

    /**
     * The order that brought the picture on the bench, twice: a PAL stream (status 2, sized
     * 720x576), a redetect under it, a wait for the lock (AHD 720p30 came up as 7), a close.
     */
    private fun warmUp(surface: S, signal: Signal, mine: Int) {
        Log.i(TAG, "decoder has no lock: warming up under a fixed-mode stream")
        signal.forceStreamable()
        camera.open(surface)
        signal.redetect()

        var waited = 0L
        while (!signal.locked() && waited < warmTimeoutMs && generation == mine) {
            Thread.sleep(warmPollMs)
            waited += warmPollMs
        }
        Log.i(TAG, "decoder ${if (signal.locked()) "locked" else "still unlocked"} after $waited ms")
        camera.close()
    }

    /** Frames drawn so far (null when nothing streams), asked on the worker like every other call. */
    fun frames(onFrames: (Int?) -> Unit) {
        val mine = generation
        worker.execute {
            val count = camera.frames()
            post {
                if (generation == mine) {
                    onFrames(count)
                }
            }
        }
    }

    /** Closes the camera on the worker, then runs [release] there: the surface goes after the client. */
    fun close(release: () -> Unit) {
        generation++
        worker.execute {
            camera.close()
            release()
        }
    }

    companion object {
        private const val TAG = "AisCameraWorker"

        /** Room for a cold server start; a call still stuck after it has no server to talk to. */
        const val OPEN_TIMEOUT_MS = 5_000L

        /** Two tries after the first, a second apart: both fit inside [OPEN_TIMEOUT_MS]. */
        private const val OPEN_RETRIES = 2
        private const val RETRY_DELAY_MS = 1_000L

        /** Detection reported ~15 s after the redetect on the bench; 20 s leaves room. */
        private const val WARM_TIMEOUT_MS = 20_000L
        private const val WARM_POLL_MS = 500L

        const val WARMING_UP = "Finding the camera signal"

        const val NOT_ANSWERING = "AIS camera server not answering"
    }
}
