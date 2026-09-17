package com.ripostelabs.carlauncher.carlib

import android.util.Log
import java.time.LocalDateTime
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * McuOwner — our process on the MCU port, in place of the vendor's eventcenter.
 *
 * ── Where this sits ─────────────────────────────────────────────────────────────────────────────
 *
 *     MCU ◀──/dev/ttyHS1──▶ McuLink ──▶ McuSerial.Reader ──▶ Command ──┬─▶ Listener (sys/volume/key/radio)
 *                                                                    └─▶ 0xA5: HiworldCanDecoder ──▶ CanSignal
 *
 * This is the portability layer of Riposte OS: the launcher and the suite consume [Listener] and
 * [CanSignal], and a Riposte-designed head unit replaces only what is behind [McuLink].
 *
 * ── The gate is not optional ────────────────────────────────────────────────────────────────────
 * Two readers on one tty split the stream; a six-second read once stole 682 bytes from the vendor
 * stack. So [start] refuses unless [Gate] says eventcenter is absent AND the OS build enabled the
 * owner (`ro.riposte.os.car_owner=1`). On a stock or 0.1 slot this class only ever reports
 * [Status.Blocked]. There is no override.
 *
 * ── Handshake, from the vendor's own startup ────────────────────────────────────────────────────
 * [McuOwnerProtocol.startup] frames, then SRC_NULL with a MODE_ACK wait, 3 sends × 500 ms, and
 * the session runs whether or not the ACK came — the vendor continues too (`sendDataWaitAck`).
 * [Status.Running.acked] records which, so the first car session can tell "MCU silent" from
 * "MCU talking, ACK formula wrong".
 *
 * ── Threads: the caller never waits on the port ─────────────────────────────────────────────────
 *
 *     start() ──▶ "mcu-owner" thread: open ──▶ handshake ──▶ pump (read loop) ──▶ retry after RETRY_DELAY_MS
 *     send()  ──▶ "mcu-owner-tx" thread: link.write            "mcu-owner-watch": write still pending
 *                                                              after WRITE_TIMEOUT_MS ──▶ Failed(LINK_DEAD)
 *
 * RAV4-96: a wired port with nobody reading behind it (carsim stopped, a dead ttyS1 reader on the
 * car) made `write` block, and [start] ran it on the main thread from `MainActivity.onCreate`,
 * so the launcher ANR'd on every start. Now [start] returns after the gate check; every write is
 * bounded by [writeTimeoutMs] and a stuck one closes the link, reports [LINK_DEAD] and the owner
 * thread reopens the port after [retryDelayMs], for as long as [stop] has not been called.
 *
 * ── Untested against hardware ───────────────────────────────────────────────────────────────────
 * Everything here is derived from the decompile and covered by JVM tests with a fake link. No
 * byte has crossed a real port under this class yet.
 */
class McuOwner(
    private val gate: Gate,
    private val listener: Listener,
    private val openLink: () -> McuLink = { TtyLink.open() },
    private val config: McuOwnerProtocol.StartupConfig = McuOwnerProtocol.StartupConfig(),
    private val ackTimeoutMs: Long = McuOwnerProtocol.ACK_TIMEOUT_MS,
    private val clock: () -> LocalDateTime = { LocalDateTime.now() },
    private val writeTimeoutMs: Long = WRITE_TIMEOUT_MS,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    /** Test seam: a Thread whose start() dawdles is how the start race below is reproduced. */
    private val newThread: (Runnable, String) -> Thread = { body, name -> Thread(body, name) },
) {

    /** What must be true before the port is touched. The app answers from PackageManager and getprop. */
    interface Gate {
        fun eventcenterPresent(): Boolean

        fun ownerEnabled(): Boolean
    }

    interface Listener {
        fun onSysEvent(event: McuOwnerProtocol.SysEvent) {}

        fun onMainVolume(volume: McuOwnerProtocol.MainVolume) {}

        fun onMute(mute: McuOwnerProtocol.Mute) {}

        fun onKey(key: Int) {}

        /** A `72` panel key, after the owner applied what eventcenter did with it (see [onPanelKey]). */
        fun onPanelKey(key: McuOwnerProtocol.PanelKey) {}

        /** A `74` resistive-wheel edge; the vendor's STEER_WHEEL_INFOR, unbroadcast. */
        fun onWheelKey(key: McuOwnerProtocol.WheelKey) {}
        /** One `73` RADIO_EVENT; [RadioStateHolder] folds them into what the tuner screen reads. */
        fun onRadio(event: McuOwnerProtocol.RadioEvent) {}

        fun onCanSignal(signal: CanSignal, atMs: Long) {}

        /** A framed body no handler claims; logged by the caller, never dropped silently. */
        fun onOther(command: McuSerial.Command) {}

        /** RX `96 01`: the MCU reports it woke (onCmdMcuSleepState, EventService.java:2270-2280). */
        fun onWake() {}
    }

    /** One port, several consumers: every callback goes to each of [targets], in order. */
    class FanOut(private vararg val targets: Listener) : Listener {
        override fun onSysEvent(event: McuOwnerProtocol.SysEvent) = targets.forEach { it.onSysEvent(event) }

        override fun onMainVolume(volume: McuOwnerProtocol.MainVolume) = targets.forEach { it.onMainVolume(volume) }

        override fun onMute(mute: McuOwnerProtocol.Mute) = targets.forEach { it.onMute(mute) }

        override fun onKey(key: Int) = targets.forEach { it.onKey(key) }

        override fun onPanelKey(key: McuOwnerProtocol.PanelKey) = targets.forEach { it.onPanelKey(key) }

        override fun onWheelKey(key: McuOwnerProtocol.WheelKey) = targets.forEach { it.onWheelKey(key) }

        // Missing until the tuner screen stayed "unavailable" on the farm: the `73` events reached
        // the fan-out and stopped at the interface's no-op default. Every callback goes through.
        override fun onRadio(event: McuOwnerProtocol.RadioEvent) = targets.forEach { it.onRadio(event) }

        override fun onCanSignal(signal: CanSignal, atMs: Long) = targets.forEach { it.onCanSignal(signal, atMs) }

        override fun onOther(command: McuSerial.Command) = targets.forEach { it.onOther(command) }

        override fun onWake() = targets.forEach { it.onWake() }
    }

    sealed class Status {
        object Idle : Status()

        data class Blocked(val reason: String) : Status()

        data class Failed(val reason: String) : Status()

        /** [acked] is whether SRC_NULL was acknowledged; the session runs either way. */
        data class Running(val acked: Boolean, val frames: Long, val badChecksum: Long, val skipped: Long) : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** One open link and its writer thread; replaced on every reopen so a stuck write cannot queue behind itself. */
    private class Session(val link: McuLink) {
        val tx: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "mcu-owner-tx").apply { isDaemon = true } }

        fun close() {
            tx.shutdownNow()
            runCatching { link.close() }
        }
    }

    @Volatile
    private var session: Session? = null

    /** The owner thread; the loops end when it is no longer this thread ([stop] clears it). */
    @Volatile
    private var worker: Thread? = null

    private val watchdog: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mcu-owner-watch").apply { isDaemon = true } }
    }

    private val ackLock = Object()

    @Volatile
    private var awaitingAck: McuOwnerProtocol.Mode? = null

    @Volatile
    private var ackReceived = false

    /** Mirrors `mBackcarConnected`: while set, most panel keys are dropped as the vendor drops them. */
    @Volatile
    private var reversing = false
    /** The last [setMode] argument, so a wake can resume it ([McuOwnerProtocol.reload]). */
    @Volatile
    var lastMode: McuOwnerProtocol.Mode? = null
        private set

    fun start() {
        if (worker != null) {
            return
        }

        if (gate.eventcenterPresent()) {
            _status.value = Status.Blocked("com.szchoiceway.eventcenter is installed and owns the port")
            return
        }
        if (!gate.ownerEnabled()) {
            _status.value = Status.Blocked("ro.riposte.os.car_owner is not 1")
            return
        }

        // Everything from here touches the port and may block: never on the caller's thread.
        // The field is written BEFORE the thread starts: run() gates on isCurrent(), and a
        // thread that ran first read a null and returned without ever opening the port.
        val w = newThread({ run() }, "mcu-owner").apply { isDaemon = true }
        worker = w
        w.start()
    }

    fun stop() {
        val w = worker
        worker = null
        w?.interrupt()
        session?.close()
        session = null
        _status.value = Status.Idle
    }

    /** Raw send for callers that build their own frames with [McuOwnerProtocol]. */
    fun send(frame: ByteArray) {
        val s = session ?: return
        write(s, frame)
    }

    fun setMode(mode: McuOwnerProtocol.Mode): Boolean {
        val s = session ?: return false
        lastMode = mode
        return sendWithAck(s, mode)
    }

    /** The POWER key path: clock stamp, then SRC_POWEROFF five times 50 ms apart. */
    fun powerOff() {
        val s = session ?: return
        for (frame in McuOwnerProtocol.powerOff(clock())) {
            write(s, frame)
            Thread.sleep(McuOwnerProtocol.POWER_OFF_GAP_MS)
        }
    }

    private fun isCurrent() = worker === Thread.currentThread()

    /** The owner thread: open, handshake, pump; after any failure wait [retryDelayMs] and open again. */
    private fun run() {
        while (isCurrent()) {
            val opened = try {
                openLink()
            } catch (e: Exception) {
                _status.value = Status.Failed(e.message ?: e.javaClass.simpleName)
                pause()
                continue
            }

            // stop() ran while the open was in flight: the port belongs to the next start() now.
            if (!isCurrent()) {
                runCatching { opened.close() }
                return
            }

            // The reader runs beside the handshake: the MODE_ACK has to be read while we wait for it.
            val s = Session(opened)
            session = s
            val rx = Thread({ pump(s) }, "mcu-owner-rx").apply {
                isDaemon = true
                start()
            }
            handshake(s)
            try {
                rx.join()
            } catch (e: InterruptedException) {
                // stop(): the loop ends below.
            }
            if (isCurrent()) {
                pause()
            }
        }
    }

    private fun pause() {
        try {
            Thread.sleep(retryDelayMs)
        } catch (e: InterruptedException) {
            // stop() woke us; isCurrent() is false now and the loop ends.
        }
    }

    /** Startup frames, then SRC_NULL with the ACK wait; Running unless the link died underneath. */
    private fun handshake(s: Session) {
        for (frame in McuOwnerProtocol.startup(config)) {
            write(s, frame)
        }
        val acked = sendWithAck(s, McuOwnerProtocol.Mode.NULL)
        synchronized(ackLock) {
            if (session === s) {
                _status.value = Status.Running(acked = acked, frames = 0, badChecksum = 0, skipped = 0)
            }
        }
    }

    /**
     * Hand [frame] to the writer thread and come back at once. The watchdog looks in after
     * [writeTimeoutMs]: a write still pending, or one that threw, ends the session as [LINK_DEAD].
     */
    private fun write(s: Session, frame: ByteArray) {
        val pending: Future<*> = try {
            s.tx.submit { s.link.write(frame) }
        } catch (e: RejectedExecutionException) {
            return
        }
        watchdog.schedule({ settle(s, frame.size, pending) }, writeTimeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun settle(s: Session, size: Int, pending: Future<*>) {
        if (!pending.isDone) {
            linkDead(s, "write of $size bytes blocked for $writeTimeoutMs ms")
            return
        }
        try {
            pending.get()
        } catch (e: ExecutionException) {
            linkDead(s, e.cause?.message ?: "write failed")
        } catch (e: Exception) {
            // Cancelled by close(): the session is already gone.
        }
    }

    private fun linkDead(s: Session, reason: String) = endSession(s, "$LINK_DEAD: $reason")

    /** Ends [s] if it is still the current session; the owner thread then reopens after [retryDelayMs]. */
    private fun endSession(s: Session, reason: String) {
        synchronized(ackLock) {
            if (session !== s) {
                return
            }
            session = null
            _status.value = Status.Failed(reason)
            ackLock.notifyAll()
        }
        Log.w(LOG_TAG, reason)
        s.close()
    }

    private fun sendWithAck(s: Session, mode: McuOwnerProtocol.Mode): Boolean {
        val frame = McuOwnerProtocol.mode(mode)
        synchronized(ackLock) {
            ackReceived = false
            awaitingAck = mode
            repeat(McuOwnerProtocol.ACK_ATTEMPTS) {
                if (ackReceived || session !== s) {
                    return@synchronized
                }
                write(s, frame)
                ackLock.wait(ackTimeoutMs)
            }
        }
        awaitingAck = null
        return ackReceived
    }

    private fun pump(s: Session) {
        val reader = McuSerial.Reader()
        val buffer = ByteArray(READ_BUFFER)
        var frames = 0L
        var bad = 0L
        var skipped = 0L

        while (session === s) {
            val n = try {
                s.link.read(buffer)
            } catch (e: Exception) {
                -1
            }
            if (n < 0) {
                endSession(s, "link closed")
                return
            }

            for (event in reader.feed(buffer, n)) {
                when (event) {
                    is McuSerial.Command -> {
                        frames++
                        dispatch(event)
                    }

                    is McuSerial.BadChecksum -> bad++
                    is McuSerial.Skipped -> skipped += event.bytes
                }
            }

            val current = _status.value
            if (current is Status.Running) {
                _status.value = current.copy(frames = frames, badChecksum = bad, skipped = skipped)
            }
        }
    }

    private fun dispatch(command: McuSerial.Command) {
        val awaited = awaitingAck
        if (awaited != null && McuOwnerProtocol.isModeAck(command, awaited)) {
            synchronized(ackLock) {
                ackReceived = true
                ackLock.notifyAll()
            }
            return
        }

        McuOwnerProtocol.sysEvent(command)?.let { reversing = it.reverse; listener.onSysEvent(it); return }
        McuOwnerProtocol.mainVolume(command)?.let { listener.onMainVolume(it); return }
        McuOwnerProtocol.mute(command)?.let { listener.onMute(it); return }
        McuOwnerProtocol.panelKey(command)?.let { onPanelKey(it); return }
        McuOwnerProtocol.wheelKey(command)?.let { listener.onWheelKey(it); return }
        McuOwnerProtocol.radioEvent(command)?.let { listener.onRadio(it); return }
        if (McuOwnerProtocol.isWake(command)) {
            listener.onWake()
            return
        }

        // 0xA5 relays the CAN box's own frame; the decoder keys on the box's cmd, not the relay opcode.
        when (val inner = command.innerFrame()) {
            is McuFrame.Decoded.Frame -> {
                listener.onCanSignal(HiworldCanDecoder.decodePayload(inner.cmd, inner.payload), System.currentTimeMillis())
                return
            }

            is McuFrame.Decoded.Malformed -> {
                Log.w(LOG_TAG, "CAN relay frame malformed: ${inner.reason}")
                return
            }

            null -> Unit
        }

        Log.i(LOG_TAG, "unhandled opcode 0x%02X (%d bytes)".format(command.opcode, command.payload.size))
        listener.onOther(command)
    }

    /**
     * The port owner's share of a `72` key, before the apps hear of it (onCmdKeyEvent,
     * EventService.java:2401-2699). With the camera up only [McuOwnerProtocol.panelKeyPassesReverse]
     * keys count. VOL+/VOL-/MUTE go back to the MCU as `08 xx` (sendSystemKey, :4263), which is
     * what moves the amplifier; the MCU then reports the result on `79`/`78`. POWER runs
     * [powerOff] after the notify: the dex confirms the switch's default arm falls through to
     * notifyValidModeEvt(4098) and then powerOff() (:2695-2698), which jadx renders as unreachable.
     */
    private fun onPanelKey(key: McuOwnerProtocol.PanelKey) {
        if (reversing && !McuOwnerProtocol.panelKeyPassesReverse(key.code)) {
            return
        }

        listener.onKey(key.code)
        listener.onPanelKey(key)

        val echo = McuOwnerProtocol.panelSystemKey(key.code)
        if (echo != null) {
            send(McuOwnerProtocol.systemKey(echo))
            return
        }

        if (key.code == McuOwnerProtocol.Key.POWER) {
            powerOff()
        }
    }

    companion object {
        private const val LOG_TAG = "McuOwner"
        private const val READ_BUFFER = 512

        /** Prefix of [Status.Failed.reason] when a write hung or threw; a read EOF stays "link closed". */
        const val LINK_DEAD = "link dead"

        /** A UART write that has not returned in half a second has nobody behind it. */
        const val WRITE_TIMEOUT_MS = 500L
        const val RETRY_DELAY_MS = 5_000L
    }
}
