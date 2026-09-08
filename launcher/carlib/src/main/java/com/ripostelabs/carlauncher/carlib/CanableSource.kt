package com.ripostelabs.carlauncher.carlib

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
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

        /** Every distinct id on the bus, not just the [ids] the screen shows. */
        val distinctIds: Int = 0,

        /** Where the capture is being written, and how much of it exists so far. */
        val capturePath: String? = null,
        val captureBytes: Long = 0,

        /**
         * Lines that were neither a frame nor an acknowledgement. Carried because a read that
         * returns bytes which decode to nothing is a real state — a wrong line terminator, or a
         * protocol that is not slcan — and without this counter it is reported as pure silence.
         */
        val unparsed: Long = 0,
    ) : CanableStatus()

    /** Enumerated, but could not be claimed or opened. */
    data class Failed(val reason: String) : CanableStatus()
}

class CanableSource private constructor(
    private val link: CanableUsbLink,
    private val context: Context,

    /**
     * Where decoded frames go, beyond the capture file. The raw bus is a second source for the
     * same vehicle fields the MCU feeds, so it lands in the same [VehicleState] and a screen never
     * has to know which protocol a reading arrived on.
     */
    private val vehicle: VehicleState,
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
        val watchdog = ReadWatchdog()
        var published = 0L

        val capture = Capture(captureDir())
        Log.i(LOG_TAG, "capture -> ${capture.path() ?: "unavailable"}")

        try {
            pump(session, stats, watchdog, capture, published)
        } finally {
            capture.close()
        }
    }

    /**
     * A rolling capture. Owns the current file and the rotation policy so [pump] does not have to
     * think about either; when a file fills, the next one opens and the oldest is deleted.
     */
    private inner class Capture(private val dir: File?) {

        private val rotation = CaptureRotation()
        private var writer: BufferedWriter? = null
        private var recorder: CanableRecorder? = null

        init {
            open()
        }

        val bytes: Long get() = recorder?.bytesWritten ?: 0

        fun path(): String? = dir?.let { File(it, rotation.currentName()).absolutePath }

        fun record(frame: SlcanFrame) {
            recorder?.record(frame, System.currentTimeMillis())

            if (rotation.shouldRoll(bytes)) {
                roll()
            }
        }

        fun close() {
            runCatching { writer?.flush() }
            runCatching { writer?.close() }
            writer = null
        }

        private fun roll() {
            close()

            // Evicting before opening keeps the directory at its bound even if the next open
            // fails, which matters on a device whose storage is already tight.
            rotation.roll()?.let { stale -> dir?.let { File(it, stale).delete() } }
            open()
        }

        private fun open() {
            val target = dir?.let { File(it, rotation.currentName()) } ?: return
            writer = runCatching { BufferedWriter(FileWriter(target)) }.getOrNull()
            recorder = writer?.let { CanableRecorder(it) }
        }
    }

    /**
     * Where a capture goes. The app's own external directory, so `adb pull` reaches it without
     * root and uninstalling cleans it up.
     */
    private fun captureDir(): File? {
        val dir = context.getExternalFilesDir(CAPTURE_DIR) ?: return null
        dir.mkdirs()

        return dir
    }

    private fun pump(
        session: CanableUsbLink.Session,
        stats: CanableStats,
        watchdog: ReadWatchdog,
        capture: Capture,
        publishedAt: Long,
    ) {
        var published = publishedAt

        while (running) {
            for (event in session.poll()) {
                stats.record(event)

                // Written straight through rather than buffered in memory: at ~1215 frames/s a
                // capture held in RAM until the screen closes is both large and lost on a crash.
                if (event is SlcanEvent.Received) {
                    capture.record(event.frame)
                    vehicle.onRawFrame(event.frame, System.currentTimeMillis())
                }
            }

            // Unplugging the adapter mid-session does not fail the reads, it just makes them
            // empty forever. Without this the loop span on a dead handle, still publishing the
            // last good state, and only a restart recovered it.
            watchdog.record(session.lastRead)
            if (watchdog.shouldVerifyDevice() && link.find() == null) {
                publish(CanableStatus.NoAdapter)
                return
            }

            // Repainting per frame would push ~1215 recompositions a second at the UI.
            val now = System.currentTimeMillis()
            if (now - published < PUBLISH_MS) {
                continue
            }

            published = now
            publish(CanableStatus.Running(
                version = stats.version ?: stats.banner,
                frames = stats.frames,
                ratePerSec = stats.ratePerSec(),
                rejected = stats.rejected,
                ids = stats.ids().take(MAX_IDS_SHOWN),
                distinctIds = stats.distinctIds,
                unparsed = stats.unparsed,
                capturePath = capture.path(),
                captureBytes = capture.bytes,
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
            "rejected=${status.rejected} unparsed=${status.unparsed} ids=${status.distinctIds} " +
            "captured=${status.captureBytes}B"
    }

    companion object {
        private const val LOG_TAG = "Canable"

        /** Under the app's external files dir, so adb pull needs no root. */
        private const val CAPTURE_DIR = "can"
        private const val RETRY_MS = 1_000L
        private const val PUBLISH_MS = 500L
        private const val MAX_IDS_SHOWN = 16

        /** Build a source for [context]. Callers never see the driver or [UsbManager]. */
        fun create(context: Context, vehicle: VehicleState): CanableSource {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            return CanableSource(CanableUsbLink(manager), context.applicationContext, vehicle)
        }
    }
}
