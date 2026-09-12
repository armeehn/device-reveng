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

        /** The ECU's own km/h from OBD PID 0x0D, or null until it answers. Diagnostic only. */
        val obdKmh: Int? = null,
        val obdReplies: Long = 0,

        /** Latest value for every standard parameter the car has answered. */
        val obdReadings: Map<ObdPid, Double> = emptyMap(),

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

    /**
     * A probe watching every frame, or null. Set while a guided test runs and cleared after.
     *
     * A hook rather than a frame flow: at ~1215 frames/s a shared flow means an allocation and a
     * dispatch per frame whether or not anyone is listening, and nobody is listening almost all
     * of the time. A null check costs nothing.
     */
    @Volatile
    private var probe: SignalProbe? = null

    /** Watch, or stop watching, every frame. Passing null detaches. */
    fun attachProbe(watcher: SignalProbe?) {
        probe = watcher
    }

    @Volatile
    private var running = false

    /**
     * Whether an adapter is attached AND already permitted, so recording can begin unattended.
     *
     * Both halves matter and only together. An attached adapter with no permission cannot be
     * claimed, and asking for permission needs a dialog, which is the one thing a car at
     * power-on must not produce. So this is deliberately false in that case rather than
     * optimistic: the capture screen is where a human grants it, once.
     *
     * Kept here so nothing above carlib has to import the USB API to answer the question.
     */
    fun adapterReady(): Boolean {
        val device = link.find() ?: return false

        return link.hasPermission(device)
    }

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
            rotation.roll()?.let { stale ->
                dir?.let { d -> File(d, stale).delete() }
            }
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

        // The one thing this service transmits: a bounded-rate request for the ECU's own speed,
        // the independent reference the raw-bus decode is checked against. Every reply is also
        // in the capture file, since it is just another frame on the bus.
        val poller = ObdPoller()

        // Several parameters, but no more transmit than one: the questions take turns rather than
        // each getting its own timer. See ObdRotation.
        val rotation = ObdRotation()

        while (running) {
            for (event in session.poll()) {
                stats.record(event)

                // Written straight through rather than buffered in memory: at ~1215 frames/s a
                // capture held in RAM until the screen closes is both large and lost on a crash.
                if (event is SlcanEvent.Received) {
                    capture.record(event.frame)
                    probe?.accept(event.frame.id, ByteArray(event.frame.data.size) { event.frame.data[it].toByte() })
                    vehicle.onRawFrame(event.frame, System.currentTimeMillis())
                    Obd.parse(event.frame)?.let { reply ->
                        stats.recordObd(reply)
                        vehicle.onObd(reply, System.currentTimeMillis())
                    }
                }
            }

            if (poller.shouldSend(System.currentTimeMillis())) {
                session.send(Obd.request(rotation.next()))
            }

            // Unplugging the adapter mid-session does not fail the reads, it just makes them
            // empty forever. Without this the loop span on a dead handle, still publishing the
            // last good state, and only a restart recovered it.
            watchdog.record(session.lastRead)
            if (watchdog.shouldVerifyDevice() && link.find() == null) {
                publish(CanableStatus.NoAdapter)
                return
            }

            // The adapter is still attached and has stopped delivering anyway. Returning here
            // closes the session and the outer loop opens a new one: a fresh connection, a fresh
            // interface claim and a fresh channel open, which is what a physical replug does.
            //
            // Gated on having received frames THIS session, so it only fires on a link that
            // demonstrably worked and then stopped. A link that never delivered is not stalled,
            // and reopening it would just spin.
            if (stats.frames > 0 && watchdog.shouldReclaim()) {
                // The read result and its duration go in the line because they say WHY it
                // stalled, and the answer decides what to try next: an instant failure is a
                // halted endpoint, which reopening clears, while a failure at the full timeout
                // is a link that is up and receiving nothing.
                Log.i(
                    LOG_TAG,
                    "link silent with the adapter still attached after ${stats.frames} frames; " +
                        "lastRead=${session.lastRead} in ${session.lastReadMs}ms; reopening",
                )
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
                obdKmh = stats.obdKmh,
                obdReplies = stats.obdReplies,
                obdReadings = stats.obdReadings(),
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
            "obd=${status.obdKmh?.let { "${it}km/h" } ?: "-"} " +
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
