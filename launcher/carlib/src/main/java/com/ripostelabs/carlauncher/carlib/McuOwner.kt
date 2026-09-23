package com.ripostelabs.carlauncher.carlib

import android.util.Log
import java.time.LocalDateTime
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
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
    /** Test seam: the POWER key pauses go through here, so a test records them instead of waiting. */
    private val sleep: (Long) -> Unit = { ms -> Thread.sleep(ms) },
    private val canBoxTiming: CanBoxTiming = CanBoxTiming(),
    /** The car the CAN box is told it is in until [selectCar] says otherwise. */
    initialCar: CarProfile = CarProfiles.DEFAULT,
    /** Runs the CAN box round off the owner and pump threads; tests inspect its queue. */
    private val canBoxScheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mcu-owner-canbox").apply { isDaemon = true } },
) {

    /** When the CAN box round runs ([McuOwnerProtocol.canBoxInit]); tests shorten it. */
    data class CanBoxTiming(
        val afterHandshakeMs: Long = McuOwnerProtocol.CAN_BOX_START_DELAY_MS,
        val afterWakeMs: Long = McuOwnerProtocol.CAN_BOX_WAKE_DELAY_MS,
        val repeatGapMs: Long = McuOwnerProtocol.CAN_BOX_REPEAT_GAP_MS,
    )

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

        /** An `88` learned-slot mask; the vendor's STEER_WHEEL_STATUS ([WheelLearn] reads it). */
        fun onWheelState(mask: Int) {}
        /** One `73` RADIO_EVENT; [RadioStateHolder] folds them into what the tuner screen reads. */
        fun onRadio(event: McuOwnerProtocol.RadioEvent) {}

        fun onCanSignal(signal: CanSignal, atMs: Long) {}

        /** A framed body no handler claims; logged by the caller, never dropped silently. */
        fun onOther(command: McuSerial.Command) {}

        /** RX `96 01`: the MCU reports it woke (onCmdMcuSleepState, EventService.java:2270-2280). */
        fun onWake() {}

        /** RX `83`: the MCU's battery-backed clock, decoded (onCmdSysRTCTimeEvt, :3041-3058). */
        fun onRtc(time: LocalDateTime) {}
    }

    /** One port, several consumers: every callback goes to each of [targets], in order. */
    class FanOut(private vararg val targets: Listener) : Listener {
        override fun onSysEvent(event: McuOwnerProtocol.SysEvent) = targets.forEach { it.onSysEvent(event) }

        override fun onMainVolume(volume: McuOwnerProtocol.MainVolume) = targets.forEach { it.onMainVolume(volume) }

        override fun onMute(mute: McuOwnerProtocol.Mute) = targets.forEach { it.onMute(mute) }

        override fun onKey(key: Int) = targets.forEach { it.onKey(key) }

        override fun onPanelKey(key: McuOwnerProtocol.PanelKey) = targets.forEach { it.onPanelKey(key) }

        override fun onWheelKey(key: McuOwnerProtocol.WheelKey) = targets.forEach { it.onWheelKey(key) }

        override fun onWheelState(mask: Int) = targets.forEach { it.onWheelState(mask) }

        // Missing until the tuner screen stayed "unavailable" on the farm: the `73` events reached
        // the fan-out and stopped at the interface's no-op default. Every callback goes through.
        override fun onRadio(event: McuOwnerProtocol.RadioEvent) = targets.forEach { it.onRadio(event) }

        override fun onCanSignal(signal: CanSignal, atMs: Long) = targets.forEach { it.onCanSignal(signal, atMs) }

        override fun onOther(command: McuSerial.Command) = targets.forEach { it.onOther(command) }

        override fun onWake() = targets.forEach { it.onWake() }

        override fun onRtc(time: LocalDateTime) = targets.forEach { it.onRtc(time) }
    }

    sealed class Status {
        object Idle : Status()

        data class Blocked(val reason: String) : Status()

        data class Failed(val reason: String) : Status()

        /**
         * [acked] is whether SRC_NULL was acknowledged; the session runs either way. [frames] is
         * every frame dispatched, [badChecksum] how many of those the CK formula did not fit.
         */
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

    /** Whose frames [McuOwnerProtocol.canBoxInit] builds; the driver's choice in Settings. */
    @Volatile
    private var car: CarProfile = initialCar

    /** The pending CAN box sends; a new round or [stop] cancels them. */
    private var canBoxRound: List<ScheduledFuture<*>> = emptyList()
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
        cancelCanBox()
        session?.close()
        session = null
        _status.value = Status.Idle
    }

    /**
     * Tell the CAN box it is in [profile]. A change re-sends the startup at once, so the box
     * does not keep the old car until the next wake; with no port open it waits for the handshake.
     */
    fun selectCar(profile: CarProfile) {
        if (profile == car) {
            return
        }

        car = profile
        Log.i(LOG_TAG, "CAN box car: ${profile.label}")
        if (session != null) {
            scheduleCanBox(0)
        }
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

    /** The POWER key path: clock stamp, the vendor's sync pause, then SRC_POWEROFF five times 50 ms apart. */
    fun powerOff() {
        val s = session ?: return
        val frames = McuOwnerProtocol.powerOff(clock())
        write(s, frames.first())
        sleep(McuOwnerProtocol.POWER_OFF_SYNC_DELAY_MS)
        for (frame in frames.drop(1)) {
            write(s, frame)
            sleep(McuOwnerProtocol.POWER_OFF_GAP_MS)
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
        if (session === s) {
            scheduleCanBox(canBoxTiming.afterHandshakeMs)
        }
    }

    /**
     * canbus2's box startup: [McuOwnerProtocol.canBoxInit] after [delayMs], then the car type
     * [McuOwnerProtocol.CAN_BOX_CAR_TYPE_REPEATS] more times. A newer round replaces a pending
     * one, as the vendor's removeMessages does. Scheduling only: the pump never waits on it.
     */
    @Synchronized
    private fun scheduleCanBox(delayMs: Long) {
        cancelCanBox()

        val init = canBoxScheduler.schedule({ sendCanBox(McuOwnerProtocol.canBoxInit(car)) }, delayMs, TimeUnit.MILLISECONDS)
        val repeats = (1..McuOwnerProtocol.CAN_BOX_CAR_TYPE_REPEATS).map { n ->
            val at = delayMs + n * canBoxTiming.repeatGapMs
            canBoxScheduler.schedule({ sendCanBox(listOf(McuOwnerProtocol.canBoxCarType(car))) }, at, TimeUnit.MILLISECONDS)
        }
        canBoxRound = listOf(init) + repeats
    }

    @Synchronized
    private fun cancelCanBox() {
        canBoxRound.forEach { it.cancel(false) }
        canBoxRound = emptyList()
    }

    /** Logged per frame so the first car session shows the box startup went out. */
    private fun sendCanBox(frames: List<ByteArray>) {
        val s = session ?: return
        for (frame in frames) {
            write(s, frame)
            Log.i(LOG_TAG, "CAN box tx: ${frame.joinToString(" ") { "%02X".format(it) }}")
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
        canRelay = McuCanRelay()
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

                    // The vendor never checks CK on this direction (SerialReadThread.parseRxData
                    // hands every LEN-delimited frame on), and the car's first tally said the
                    // outbound formula disagrees with what the MCU sends. Dropping here would
                    // lose real frames on the owner path; count it, dispatch it, let the tally
                    // say how often the formula holds.
                    is McuSerial.BadChecksum -> {
                        bad++
                        frames++
                        dispatch(event.command)
                    }
                    is McuSerial.Skipped -> skipped += event.bytes
                }
            }

            val current = _status.value
            if (current is Status.Running) {
                _status.value = current.copy(frames = frames, badChecksum = bad, skipped = skipped)
            }
        }
    }

    private val loggedUnhandled = mutableSetOf<Int>()

    /** The box's stream as the 0xA5 relays rebuild it; [pump] starts a fresh one per session. */
    private var canRelay = McuCanRelay()

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
        McuOwnerProtocol.wheelState(command)?.let { listener.onWheelState(it); return }
        McuOwnerProtocol.radioEvent(command)?.let { listener.onRadio(it); return }
        McuOwnerProtocol.rtcTime(command)?.let { listener.onRtc(it); return }
        if (McuOwnerProtocol.isWake(command)) {
            scheduleCanBox(canBoxTiming.afterWakeMs)
            listener.onWake()
            return
        }

        // 0xA5 relays a slice of the CAN box's stream, not always one whole frame; see McuCanRelay.
        if (command.opcode == McuSerial.OP_CAN) {
            canRelay.feed(command.payload).forEach(::onRelayed)
            return
        }

        // Once per opcode: the MCU streams 0x8E (G-sensor, RADAR_3DH) at 10 Hz for the whole
        // drive and the vendor only stores the bytes; the log is for the first sighting.
        if (loggedUnhandled.add(command.opcode)) {
            Log.i(LOG_TAG, "unhandled opcode 0x%02X (%d bytes): %s".format(
                command.opcode, command.payload.size, command.payload.joinToString(" ") { "%02X".format(it) }))
        }
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
    /** One box frame cut from the relay stream; the decoder keys on the box's cmd, not the relay opcode. */
    private val loggedBoxCmds = mutableSetOf<Int>()

    private fun onRelayed(inner: McuFrame.Decoded) {
        when (inner) {
            is McuFrame.Decoded.Frame -> {
                // Once per box cmd: the first 0x11 in a car log proves the wheel keys arrive.
                if (loggedBoxCmds.add(inner.cmd)) {
                    Log.i(LOG_TAG, "CAN box cmd 0x%02X first seen (%d bytes)".format(inner.cmd, inner.payload.size))
                }
                listener.onCanSignal(HiworldCanDecoder.decodePayload(inner.cmd, inner.payload), System.currentTimeMillis())
            }

            is McuFrame.Decoded.Malformed ->
                Log.w(LOG_TAG, "CAN relay frame malformed: ${inner.reason}")
        }
    }

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
