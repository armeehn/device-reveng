package com.ripostelabs.carlauncher.carlib

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * v2.9 — the launcher-side half of the protected-broadcast capture. Runs [RootBroadcastHelper]
 * under `su` and turns its stdout back into car events.
 *
 * See [RootBroadcastHelper] for why the helper exists at all. This class owns only its lifecycle:
 * start it, read it, restart it when it dies, and give up quietly when there is no root — in which
 * case [CarEvents] keeps exactly the v2.5 behaviour, on the unprotected fallbacks.
 *
 * **Failure is silent by design.** No root, an `app_process` that will not load our dex, a vendor
 * build where the reflective system context is blocked: all of these are ordinary on some unit
 * somewhere, and none of them should surface as an error to a driver who never asked for this. The
 * only visible signal is [CarEvents.rootCapture] going true when it does work.
 *
 * Events are delivered on this class's reader thread, not the main thread. Its consumers are
 * StateFlows and a CopyOnWriteArraySet, all of which are safe off the main thread.
 */
internal class RootBroadcastBridge(
    context: Context,
    private val onEvent: (action: String, ints: Map<String, Int>) -> Unit,
) {

    private companion object {
        const val TAG = "RootBroadcastBridge"

        /**
         * `app_process` needs a command directory argument before the class name; `/system/bin` is
         * what every platform shell tool passes. ANDROID_DATA is pointed at a writable scratch dir
         * because ART wants somewhere for its dalvik-cache when it loads a dex outside an app.
         */
        const val CMD_DIR = "/system/bin"
        const val SCRATCH_DIR = "/data/local/tmp"

        /** A helper that dies this many times without ever reaching READY is not going to work. */
        const val MAX_FAILED_STARTS = 3

        /** Backoff between restarts of a helper that *did* work once (ACC cycle, OOM kill). */
        const val RESTART_DELAY_MS = 2_000L
    }

    private val apkPaths: String = buildApkClasspath(context)

    @Volatile
    private var running = false

    @Volatile
    private var process: Process? = null

    /**
     * v0.4.7 — guards the [process] handoff. The su process runs before `process = proc` lands;
     * a stop() in that window destroyed nothing and left the reader blocked in readLine() on a
     * live root process forever. Both sides now hand off under this lock.
     */
    private val processLock = Any()

    /** True once any helper announced READY, so we only log the "unavailable" verdict once. */
    @Volatile
    private var reachedReadyEver = false

    private var thread: Thread? = null

    /** Start capturing. A second call while running is a no-op. */
    fun start() {
        if (running) {
            return
        }
        running = true
        thread = Thread({ pump() }, "root-broadcast-bridge").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        synchronized(processLock) {
            runCatching { process?.destroy() }
            process = null
        }
        thread?.interrupt()
        thread = null
    }

    /**
     * Supervise one helper at a time for as long as we are [running]. A helper that never reached
     * READY is counted as a failed start; [MAX_FAILED_STARTS] of those and we stop trying, because
     * the usual cause is "this unit has no root" and retrying forever would spawn an `su` every
     * couple of seconds for the life of the launcher.
     */
    private fun pump() {
        var failedStarts = 0

        while (running && failedStarts < MAX_FAILED_STARTS) {
            val reachedReady = runHelper()

            failedStarts = if (reachedReady) 0 else failedStarts + 1

            if (!running) {
                return
            }
            runCatching { Thread.sleep(RESTART_DELAY_MS) }.onFailure { return }
        }

        if (!reachedReadyEver) {
            Log.i(TAG, "root broadcast capture unavailable — staying on unprotected fallbacks")
        }
    }

    /** @return true if the helper announced READY before it exited. */
    private fun runHelper(): Boolean {
        val command = "CLASSPATH=${RootShell.quote(apkPaths)} ANDROID_DATA=$SCRATCH_DIR " +
            "app_process $CMD_DIR ${RootBroadcastHelper::class.java.name}"

        val proc = runCatching {
            // stderr merged in: an ART loader complaint is the one diagnostic that explains why
            // this path is dead on a given unit, and it would otherwise fill an undrained pipe.
            ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        }.getOrElse {
            Log.d(TAG, "su unavailable: ${it.message}")
            return false
        }

        // Publish under the lock, re-checking [running]: a stop() that raced the ProcessBuilder
        // start finds either a published process to destroy or this destroy-and-bail.
        synchronized(processLock) {
            if (!running) {
                runCatching { proc.destroy() }
                return false
            }
            process = proc
        }
        var ready = false

        runCatching {
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                while (running) {
                    val line = reader.readLine() ?: break

                    if (line == RootBroadcastHelper.READY_LINE) {
                        ready = true
                        reachedReadyEver = true
                        Log.i(TAG, "root broadcast capture live")
                        continue
                    }

                    dispatch(line)
                }
            }
        }

        runCatching { proc.destroy() }
        synchronized(processLock) {
            if (process === proc) {
                process = null
            }
        }
        return ready
    }

    /** Parse `EVT<TAB>action<TAB>key=int…`. Anything else is helper noise (ART warnings) — drop it. */
    private fun dispatch(line: String) {
        val fields = line.split(RootBroadcastHelper.SEP)
        if (fields.size < 2 || fields[0] != RootBroadcastHelper.EVENT_PREFIX) {
            return
        }

        val ints = HashMap<String, Int>()
        for (i in 2 until fields.size) {
            val eq = fields[i].indexOf('=')
            if (eq <= 0) {
                continue
            }
            val value = fields[i].substring(eq + 1).toIntOrNull() ?: continue
            ints[fields[i].substring(0, eq)] = value
        }

        runCatching { onEvent(fields[1], ints) }
            .onFailure { Log.w(TAG, "event dispatch failed: $line", it) }
    }

    /**
     * Every dex the helper may need to load. `sourceDir` alone is enough for the side-loaded
     * single APK this launcher ships as, but a split install would leave the helper's own class in
     * a split and fail with a bare ClassNotFoundException — cheap to be correct about.
     */
    private fun buildApkClasspath(context: Context): String {
        val info = context.applicationContext.applicationInfo
        val paths = mutableListOf(info.sourceDir)
        info.splitSourceDirs?.let { paths.addAll(it) }
        return paths.joinToString(":")
    }
}
