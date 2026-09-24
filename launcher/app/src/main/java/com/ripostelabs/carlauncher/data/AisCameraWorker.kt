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
 *     open(s)       ─▶ worker: camera.open ─▶ post(onResult(state))
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
) {
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
            val state = camera.open(surface)
            answered.set(true)
            deadline.cancel(false)
            deliver(state)
        }
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

        const val NOT_ANSWERING = "AIS camera server not answering"
    }
}
