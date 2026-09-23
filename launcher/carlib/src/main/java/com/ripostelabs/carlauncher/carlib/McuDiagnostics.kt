package com.ripostelabs.carlauncher.carlib

import java.util.TreeMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * McuDiagnostics — what the vendor's canbusdebug window showed, kept for Settings > Diagnostics.
 *
 *     McuOwner ──▶ FanOut ──▶ McuDiagnostics ──▶ snapshot ──▶ DiagnosticsScreen
 *                                             └▶ report() ──▶ logcat ──▶ `rav4 car diag`
 *
 * canbusdebug listened to eventcenter's broadcasts and listed every 0xA5 relay body as one
 * "Rx : a5 5a a5 …" hex line in a 500-line list (CanbusDebugService.java:118-124, :169-176,
 * DataUI.java:313-316), with a checkbox that appended the same lines to a file (:191, :228-255).
 * The owner path has no broadcasts, so the same view hangs off the listener: the last
 * [capacity] relay bodies with what [McuCanRelay] cut from each, the newest decoded signal per
 * type with its age, the MCU version string from the SRC_MCU_VERSION ack, and the car the box
 * was last told it is in.
 */
class McuDiagnostics(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val now: () -> Long = System::currentTimeMillis,
) : McuOwner.Listener {

    /** One 0xA5 body and what the reassembler cut from it; an empty [cut] is a slice still held. */
    class Relay(val atMs: Long, val body: ByteArray, val cut: List<McuFrame.Decoded>)

    class Signal(val signal: CanSignal, val atMs: Long)

    data class Snapshot(
        val mcuVersion: String? = null,
        val canBoxCar: CarProfile? = null,
        val canBoxToldAtMs: Long? = null,
        /** Oldest first, at most [capacity]. */
        val relays: List<Relay> = emptyList(),
        /** One per signal type, by type name. */
        val signals: List<Signal> = emptyList(),
        val relayCount: Long = 0,
        val boxFrames: Long = 0,
        val boxMalformed: Long = 0,
    )

    private val lock = Any()
    private val relays = ArrayDeque<Relay>()
    private val signals = TreeMap<String, Signal>()

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    override fun onMcuVersion(version: String) {
        update { it.copy(mcuVersion = version) }
    }

    override fun onCanBoxCar(car: CarProfile) {
        update { it.copy(canBoxCar = car, canBoxToldAtMs = now()) }
    }

    override fun onCanRelay(body: ByteArray, cut: List<McuFrame.Decoded>) {
        val frames = cut.count { it is McuFrame.Decoded.Frame }
        update {
            relays.addLast(Relay(now(), body.copyOf(), cut))
            while (relays.size > capacity) {
                relays.removeFirst()
            }
            it.copy(
                relays = relays.toList(),
                relayCount = it.relayCount + 1,
                boxFrames = it.boxFrames + frames,
                boxMalformed = it.boxMalformed + (cut.size - frames),
            )
        }
    }

    override fun onCanSignal(signal: CanSignal, atMs: Long) {
        update {
            signals[signal.javaClass.simpleName] = Signal(signal, atMs)
            it.copy(signals = signals.values.toList())
        }
    }

    private fun update(change: (Snapshot) -> Snapshot) {
        synchronized(lock) { _snapshot.value = change(_snapshot.value) }
    }

    /**
     * The page's lines and the "copy to log" dump, one source so they cannot drift. The link
     * [status] is the owner's, passed in: this listener never sees the port.
     */
    fun report(status: McuOwner.Status, now: Long = this.now()): List<String> {
        val s = snapshot.value
        val car = s.canBoxCar
        val told = if (car == null || s.canBoxToldAtMs == null) {
            "not told yet"
        } else {
            "${car.label} (0x%02X), told ${Format.age(s.canBoxToldAtMs, now)} ago".format(car.carType)
        }

        return listOf(
            "link: ${Format.status(status)}",
            "mcu version: ${s.mcuVersion ?: "unknown"}",
            "can box car: $told",
            "relays: ${s.relayCount}, box frames: ${s.boxFrames}, malformed: ${s.boxMalformed}",
        ) +
            Format.relayLines(s.relays, now).map { "relay: $it" } +
            s.signals.map { "signal: ${Format.age(it.atMs, now)}  ${Format.signal(it.signal)}" }
    }

    /** Text for the page and the log; pure, so the shape is pinned by tests. */
    object Format {

        private const val SECOND_MS = 1_000L
        private const val MINUTE_MS = 60 * SECOND_MS
        private const val HOUR_MS = 60 * MINUTE_MS

        fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it) }

        /** "<1 s", "3 s", "2 min", "2 h": enough to tell live from stale at a glance. */
        fun age(atMs: Long, now: Long): String {
            val ms = now - atMs
            return when {
                ms < SECOND_MS -> "<1 s"
                ms < MINUTE_MS -> "${ms / SECOND_MS} s"
                ms < HOUR_MS -> "${ms / MINUTE_MS} min"
                else -> "${ms / HOUR_MS} h"
            }
        }

        fun status(status: McuOwner.Status): String = when (status) {
            McuOwner.Status.Idle -> "idle"
            is McuOwner.Status.Blocked -> "blocked: ${status.reason}"
            is McuOwner.Status.Failed -> "failed: ${status.reason}"
            is McuOwner.Status.Running -> "running, SRC_NULL ${if (status.acked) "acked" else "not acked"}, " +
                "${status.frames} frames, ${status.badChecksum} bad CK, ${status.skipped} bytes skipped"
        }

        /** `age  size  hex  ->  what the reassembler made of it`, oldest first. */
        fun relayLines(relays: List<Relay>, now: Long): List<String> = relays.map { r ->
            "${age(r.atMs, now)}  ${r.body.size} B  ${hex(r.body)}  ->  ${cut(r.cut)}"
        }

        private fun cut(cut: List<McuFrame.Decoded>): String {
            if (cut.isEmpty()) {
                return "held"
            }
            return cut.joinToString(", ") {
                when (it) {
                    is McuFrame.Decoded.Frame -> "cmd 0x%02X (%d B)".format(it.cmd, it.payload.size)
                    is McuFrame.Decoded.Malformed -> "malformed: ${it.reason}"
                }
            }
        }

        /** A data class prints itself; Unknown holds a ByteArray, which would print as an address. */
        fun signal(signal: CanSignal): String = when (signal) {
            is CanSignal.Unknown -> "Unknown(opcode=0x%02X, payload=%s)".format(signal.opcode, hex(signal.payload))
            else -> signal.toString()
        }
    }

    private companion object {
        /** canbusdebug kept 500 lines; a screen reads a few dozen and the log carries the same. */
        const val DEFAULT_CAPACITY = 32
    }
}
