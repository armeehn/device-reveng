package com.ripostelabs.carlauncher.carlib

import android.content.Context
import android.util.Log
import android.hardware.usb.UsbManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CanableSource — the CANable as a service: start it, watch a [StateFlow], stop it.
 *
 * Sits between the UI and [CanableUsbLink] so no screen imports `android.hardware.usb`. Owns the
 * reader thread, because a bulk read blocks and Compose must not.
 *
 *     CanCaptureScreen ──> CanableSource ──> CanableUsbLink ──> UsbManager
 *          (UI)              (service)          (driver)         (Android)
 */
sealed class CanableStatus {

    /** Not started, or stopped. */
    object Idle : CanableStatus()

    /** No CANable is plugged into either USB port. */
    object NoAdapter : CanableStatus()

    /** The adapter is there but the launcher has not been granted access to it yet. */
    object NoPermission : CanableStatus()

    /** Claimed and opened, and reporting what it has heard. */
    data class Running(
        val version: String?,
        val frames: Long,
        val ratePerSec: Int,
        val rejected: Long,
        val ids: List<Pair<Int, Int>>,
    ) : CanableStatus()

    /** Enumerated, but could not be claimed or opened. */
    data class Failed(val reason: String) : CanableStatus()
}

class CanableSource private constructor(
    private val link: CanableUsbLink,
    private val context: Context,
) {

    private val _status = MutableStateFlow<CanableStatus>(CanableStatus.Idle)
    val status: StateFlow<CanableStatus> = _status.asStateFlow()

    private var worker: Thread? = null

    @Volatile
    private var running = false

    /** Start reading. Safe to call twice; the second call does nothing. */
    fun start(bitrate: SlcanBitrate = SlcanBitrate.KBIT_500) {
        if (running) {
            return
        }

        running = true
        worker = Thread({ pump(bitrate) }, "canable-read").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        worker = null
        publish(CanableStatus.Idle)
    }

    /**
     * Ask the system for access to the attached adapter. The reply arrives as a broadcast we do
     * not listen for: the pump re-checks permission every cycle, so a granted dialog simply makes
     * the next cycle succeed.
     */
    fun requestAccess() {
        val device = link.find() ?: return
        link.requestPermission(context, device)
    }

    /**
     * Claim the adapter, open the channel, then read until stopped. Every failure loops back to a
     * retry rather than ending, so unplugging and replugging recovers on its own.
     */
    private fun pump(bitrate: SlcanBitrate) {
        while (running) {
            val session = connect(bitrate)
            if (session == null) {
                Thread.sleep(RETRY_MS)
                continue
            }

            try {
                read(session)
            } finally {
                session.close()
            }
        }
    }

    /** One connection attempt. Publishes why it failed so the screen can say something useful. */
    private fun connect(bitrate: SlcanBitrate): CanableUsbLink.Session? {
        val device = link.find()
        if (device == null) {
            publish(CanableStatus.NoAdapter)
            return null
        }

        if (!link.hasPermission(device)) {
            publish(CanableStatus.NoPermission)
            return null
        }

        val session = link.open(device, bitrate)
        if (session == null) {
            publish(CanableStatus.Failed("claim or open refused"))
            return null
        }

        // Ask the firmware to identify itself. Its reply proves the USB path end to end even when
        // CAN-H/CAN-L are not wired to anything, which no frame count can distinguish.
        session.askVersion()

        return session
    }

    private fun read(session: CanableUsbLink.Session) {
        val stats = CanableStats()
        var published = 0L

        while (running) {
            for (event in session.poll()) {
                stats.record(event)
            }

            // Repainting per frame would push ~1215 recompositions a second at the UI.
            val now = System.currentTimeMillis()
            if (now - published < PUBLISH_MS) {
                continue
            }

            published = now
            publish(CanableStatus.Running(
                version = stats.version,
                frames = stats.frames,
                ratePerSec = stats.ratePerSec(),
                rejected = stats.rejected,
                ids = stats.ids().take(MAX_IDS_SHOWN),
            ))
        }
    }

    /**
     * Publish a state, and log it when it says something different from the last one.
     *
     * The screen shows this, but the screen is in a car. Logging every transition means the same
     * result is readable over `adb logcat -s Canable` from a desk, which is the only way to check
     * the adapter without sitting in the driver seat.
     */
    private fun publish(next: CanableStatus) {
        val previous = _status.value
        _status.value = next

        if (summarise(previous) != summarise(next)) {
            Log.i(LOG_TAG, summarise(next))
        }
    }

    /** One line per state. Frame counts are deliberately included: a rate that stops climbing is
     *  the signal that the bus went quiet, and that has to be visible in a log read after the
     *  fact rather than only while watching the screen. */
    private fun summarise(status: CanableStatus): String = when (status) {
        is CanableStatus.Idle -> "idle"
        is CanableStatus.NoAdapter -> "no adapter on USB"
        is CanableStatus.NoPermission -> "adapter found, no USB permission"
        is CanableStatus.Failed -> "adapter found, unusable: ${status.reason}"
        is CanableStatus.Running -> "open firmware=${status.version ?: "-"} " +
            "frames=${status.frames} rate=${status.ratePerSec}/s " +
            "rejected=${status.rejected} ids=${status.ids.size}"
    }

    companion object {
        private const val LOG_TAG = "Canable"
        private const val RETRY_MS = 1_000L
        private const val PUBLISH_MS = 500L
        private const val MAX_IDS_SHOWN = 16

        /** Build a source for [context]. Callers never see the driver or [UsbManager]. */
        fun create(context: Context): CanableSource {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            return CanableSource(CanableUsbLink(manager), context.applicationContext)
        }
    }
}
