package com.ripostelabs.carlauncher.carlib

import android.util.Log
import java.time.LocalDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * McuOwner — our process on the MCU port, in place of the vendor's eventcenter.
 *
 * ── Where this sits ─────────────────────────────────────────────────────────────────────────────
 *
 *     MCU ◀──/dev/ttyHS1──▶ McuLink ──▶ McuSerial.Reader ──▶ Command ──┬─▶ Listener (sys/volume/key)
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
) {

    /** What must be true before the port is touched. The app answers from PackageManager and getprop. */
    interface Gate {
        fun eventcenterPresent(): Boolean

        fun ownerEnabled(): Boolean
    }

    interface Listener {
        fun onSysEvent(event: McuOwnerProtocol.SysEvent) {}

        fun onMainVolume(volume: McuOwnerProtocol.MainVolume) {}

        fun onKey(key: Int) {}

        fun onCanSignal(signal: CanSignal, atMs: Long) {}

        /** A framed body no handler claims; logged by the caller, never dropped silently. */
        fun onOther(command: McuSerial.Command) {}
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

    private var link: McuLink? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    private val ackLock = Object()

    @Volatile
    private var awaitingAck: McuOwnerProtocol.Mode? = null

    @Volatile
    private var ackReceived = false

    fun start() {
        if (running) {
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

        val opened = try {
            openLink()
        } catch (e: Exception) {
            _status.value = Status.Failed(e.message ?: e.javaClass.simpleName)
            return
        }

        link = opened
        running = true
        worker = Thread({ pump(opened) }, "mcu-owner").apply {
            isDaemon = true
            start()
        }

        for (frame in McuOwnerProtocol.startup(config)) {
            opened.write(frame)
        }
        val acked = sendWithAck(opened, McuOwnerProtocol.Mode.NULL)
        _status.value = Status.Running(acked = acked, frames = 0, badChecksum = 0, skipped = 0)
    }

    fun stop() {
        running = false
        link?.close()
        link = null
        worker = null
        _status.value = Status.Idle
    }

    /** Raw send for callers that build their own frames with [McuOwnerProtocol]. */
    fun send(frame: ByteArray) {
        link?.write(frame)
    }

    fun setMode(mode: McuOwnerProtocol.Mode): Boolean {
        val l = link ?: return false
        return sendWithAck(l, mode)
    }

    /** The POWER key path: clock stamp, then SRC_POWEROFF five times 50 ms apart. */
    fun powerOff() {
        val l = link ?: return
        for (frame in McuOwnerProtocol.powerOff(clock())) {
            l.write(frame)
            Thread.sleep(McuOwnerProtocol.POWER_OFF_GAP_MS)
        }
    }

    private fun sendWithAck(l: McuLink, mode: McuOwnerProtocol.Mode): Boolean {
        val frame = McuOwnerProtocol.mode(mode)
        synchronized(ackLock) {
            ackReceived = false
            awaitingAck = mode
            repeat(McuOwnerProtocol.ACK_ATTEMPTS) {
                if (ackReceived) {
                    return@synchronized
                }
                l.write(frame)
                ackLock.wait(ackTimeoutMs)
            }
        }
        awaitingAck = null
        return ackReceived
    }

    private fun pump(l: McuLink) {
        val reader = McuSerial.Reader()
        val buffer = ByteArray(READ_BUFFER)
        var frames = 0L
        var bad = 0L
        var skipped = 0L

        while (running) {
            val n = try {
                l.read(buffer)
            } catch (e: Exception) {
                -1
            }
            if (n < 0) {
                if (running) {
                    _status.value = Status.Failed("link closed")
                }
                running = false
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

        McuOwnerProtocol.sysEvent(command)?.let { listener.onSysEvent(it); return }
        McuOwnerProtocol.mainVolume(command)?.let { listener.onMainVolume(it); return }
        McuOwnerProtocol.key(command)?.let { listener.onKey(it); return }

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

    companion object {
        private const val LOG_TAG = "McuOwner"
        private const val READ_BUFFER = 512
    }
}
