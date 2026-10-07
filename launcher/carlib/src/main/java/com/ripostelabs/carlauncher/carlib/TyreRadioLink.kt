package com.ripostelabs.carlauncher.carlib

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/** What the RTL-SDR receiver is doing, for the Tyres page. */
enum class TyreRadioState { STARTING, LISTENING, NO_RECEIVER }

/**
 * The driver for the tyre radio: runs rtl_433 under `su` and hands each output line on.
 *
 *     su -c rtl_433 -f 315M -F json ──stdout+stderr──▶ [onLine] (JSON) / [onState] (tuner found)
 *
 * Root, because rtl_433 opens /dev/bus/usb itself through libusb and detaches any kernel driver;
 * Android's UsbManager would need a permission dialog on every plug-in. No receiver is ordinary
 * (the dongle shares the unit's only USB controller with zero's adb), so a start that never finds
 * a tuner retries every [ABSENT_RETRY_MS] quietly. Lines arrive on this class's own thread.
 */
class TyreRadioLink(
    private val binary: String,
    private val onLine: (String) -> Unit,
    private val onState: (TyreRadioState) -> Unit,
) {

    companion object {
        private const val TAG = "TyreRadioLink"

        /** North American Toyota sensors (and most others sold here) send at 315 MHz. */
        private const val FREQUENCY = "315M"

        /** rtl_433 prints this to stderr once the dongle is open and tuned. */
        private const val TUNED_MARKER = "Tuned to"

        private const val ABSENT_RETRY_MS = 30_000L
        private const val RESTART_DELAY_MS = 3_000L

        /**
         * JSON readings plus log messages: [TUNED_MARKER] is a log message, and `-F json` alone
         * silences them ("Use -F log if you want any messages", unit 2026-10-07).
         */
        internal fun command(binary: String): String =
            "exec ${RootShell.quote(binary)} -f $FREQUENCY -F json -F log"
    }

    @Volatile
    private var running = false

    @Volatile
    private var process: Process? = null
    private val processLock = Any()
    private var thread: Thread? = null

    fun start() {
        if (running) {
            return
        }
        running = true
        thread = Thread({ pump() }, "tyre-radio").apply {
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

    private fun pump() {
        while (running) {
            onState(TyreRadioState.STARTING)
            val tuned = runOnce()

            // Unplugged mid-drive: try again soon. Never found: the dongle is not there.
            if (!tuned) {
                onState(TyreRadioState.NO_RECEIVER)
            }
            val delay = if (tuned) RESTART_DELAY_MS else ABSENT_RETRY_MS
            runCatching { Thread.sleep(delay) }.onFailure { return }
        }
    }

    /** @return true when the receiver was tuned at some point before rtl_433 exited. */
    private fun runOnce(): Boolean {
        val proc = runCatching {
            ProcessBuilder("su", "-c", command(binary)).redirectErrorStream(true).start()
        }.getOrElse {
            Log.d(TAG, "su unavailable: ${it.message}")
            return false
        }

        // Publish under the lock: a stop() racing the start finds the process or this bail-out.
        synchronized(processLock) {
            if (!running) {
                runCatching { proc.destroy() }
                return false
            }
            process = proc
        }

        var tuned = false
        var lastNoise = ""
        runCatching {
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                while (running) {
                    val line = reader.readLine() ?: break

                    if (line.startsWith("{")) {
                        onLine(line)
                        continue
                    }
                    if (!tuned && line.contains(TUNED_MARKER)) {
                        tuned = true
                        Log.i(TAG, "receiver tuned to $FREQUENCY")
                        onState(TyreRadioState.LISTENING)
                    }
                    lastNoise = line
                }
            }
        }

        runCatching { proc.destroy() }
        synchronized(processLock) {
            if (process === proc) {
                process = null
            }
        }
        if (!tuned) {
            Log.d(TAG, "no receiver: $lastNoise")
        }
        return tuned
    }
}
