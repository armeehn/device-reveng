package com.reveng.carlauncher.carlib

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * CanableReader — the Android glue that turns a CANable 2.0 (slcan firmware) on `/dev/ttyACM0`
 * into a live [StateFlow]<[VehicleState]>, using [RawCanDecoder] for the byte→signal step.
 *
 * **Deliberately a plain class, not an `android.app.Service`.** The launcher wires its car data
 * sources as plain classes taking a `Context`/scope (see [CarService], [CarEvents]) and holds them
 * in its own DI, not the framework service registry. A started `Service` would add a manifest entry
 * and lifecycle we don't want for a background reader that the launcher already scopes. Lifecycle
 * here is explicit: [start] once, [stop] on teardown. (Rename to a Service only if you later need it
 * to outlive the launcher process.)
 *
 * ── Data path (independent of the HiWorld one) ──────────────────────────────────────────────────
 * CANable(slcan) → `/dev/ttyACM0` (USB CDC-ACM) → this reader → [RawCanDecoder] → [VehicleState].
 * This is the *raw vehicle bus*; [HiworldCanDecoder] is the vendor digest. Both can run at once and
 * fold into one [VehicleState] — see [VehicleState.applyHiworld] for the merge seam.
 *
 * ── LISTEN-ONLY, always ─────────────────────────────────────────────────────────────────────────
 * The init sequence opens the channel with `L` (listen-only) when the firmware supports it, falling
 * back to `O`. NOTHING here ever writes a `t`/`T` transmit frame — the only bytes sent to the
 * adapter are the slcan *control* commands `C`/`S6`/`L`/`O`, which configure the adapter, not the
 * CAN bus. We never put a frame on the car's bus.
 *
 * ── Root / device-node access ───────────────────────────────────────────────────────────────────
 * `/dev/ttyACM*` is typically `crw-rw---- root:dialout` (or `root:root`), so an app uid cannot open
 * it directly. On this rooted head unit [ensureNodeReadable] runs `su chmod 666 <node>` (SELinux is
 * Permissive here, so a plain-uid open then succeeds). If chmod is refused we fall back to reading
 * through `su -c cat` and writing through `su -c` — see [openStreams]. Nothing here needs the app
 * itself to hold any dangerous permission.
 *
 * All blocking I/O runs on [Dispatchers.IO]; [state] is safe to collect from the main thread.
 */
class CanableReader(
    /** Serial node. Auto-detected among `/dev/ttyACM*` if this one is absent (see [resolveNode]). */
    private val preferredNode: String = DEFAULT_NODE,
    /** CAN bitrate as an slcan `S` code. Toyota powertrain = 500 kbps = `S6`. */
    private val bitrateCode: String = SLCAN_500K,
) {

    companion object {
        private const val TAG = "CanableReader"

        const val DEFAULT_NODE = "/dev/ttyACM0"

        // slcan control commands (ASCII, each terminated by CR). See CANABLE_INTEGRATION.md.
        private const val CR = '\r'
        private const val CMD_CLOSE = "C"        // close channel (safe even if already closed)
        private const val SLCAN_500K = "S6"      // bitrate: S6 = 500 kbps (Toyota bus)
        private const val CMD_OPEN_LISTEN = "L"  // open LISTEN-ONLY (no ACKs, cannot transmit)
        private const val CMD_OPEN_NORMAL = "O"  // open normal (fallback; we still never transmit)

        /** If no frame arrives within this after an `L` open, retry the open with `O`. */
        private const val LISTEN_PROBE_MS = 1_500L

        /** Reconnect backoff bounds (adapter unplugged / bus asleep). */
        private const val BACKOFF_START_MS = 500L
        private const val BACKOFF_MAX_MS = 8_000L

        private const val READ_BUF = 4096
    }

    private val _state = MutableStateFlow(VehicleState())
    /** Latest merged snapshot. Starts empty (all-null); fields fill in as frames arrive. */
    val state: StateFlow<VehicleState> = _state.asStateFlow()

    private val _connected = MutableStateFlow(false)
    /** true while a channel is open and bytes are flowing. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** Raw frame counter, for a capture/diagnostic view (mirrors RadarCapture's intent). */
    private val _frameCount = MutableStateFlow(0L)
    val frameCount: StateFlow<Long> = _frameCount.asStateFlow()

    private var job: Job? = null

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /** Start the read/reconnect loop on [scope]. Idempotent — a second call is ignored. */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    /** Stop reading and close the adapter cleanly (`C`). Safe to call more than once. */
    fun stop() {
        job?.cancel()
        job = null
        _connected.value = false
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Reconnect loop
    // ─────────────────────────────────────────────────────────────────────────────────────────

    private suspend fun runLoop() {
        var backoff = BACKOFF_START_MS
        while (currentCoroutineIsActive()) {
            val node = resolveNode()
            if (node == null) {
                Log.d(TAG, "no /dev/ttyACM* present (adapter unplugged?); retrying in ${backoff}ms")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(BACKOFF_MAX_MS)
                continue
            }
            val ok = runCatching { session(node) }
                .onFailure { Log.w(TAG, "session on $node ended: ${it.message}") }
                .getOrDefault(false)

            _connected.value = false
            // A clean end (device removed mid-stream) resets backoff so a re-plug reconnects fast;
            // a hard failure grows it so we don't spin on a wedged node.
            backoff = if (ok) BACKOFF_START_MS else (backoff * 2).coerceAtMost(BACKOFF_MAX_MS)
            delay(backoff)
        }
    }

    /**
     * One open→read→close session. Returns true if it ran and ended normally (EOF / cancellation),
     * false if it could not open. Always attempts a clean `C` on exit.
     */
    private suspend fun session(node: String): Boolean {
        ensureNodeReadable(node)
        val streams = openStreams(node) ?: return false
        try {
            // Init: close (in case already open), set bitrate, open listen-only.
            writeCmd(streams.out, CMD_CLOSE)
            writeCmd(streams.out, bitrateCode)
            var listenOnly = true
            writeCmd(streams.out, CMD_OPEN_LISTEN)

            _connected.value = true
            val gotFrame = readInto(streams.input, probeMs = LISTEN_PROBE_MS)

            // Some slcan builds NAK `L`; if nothing came, reopen normal (we still never transmit).
            if (!gotFrame) {
                Log.i(TAG, "no frames after L open; retrying with O (normal, still listen-only in SW)")
                writeCmd(streams.out, CMD_CLOSE)
                writeCmd(streams.out, CMD_OPEN_NORMAL)
                listenOnly = false
            }
            Log.i(TAG, "channel open on $node (${if (listenOnly) "L" else "O"})")

            // Main read loop — runs until cancelled or the node disappears (EOF/IOException).
            readLoop(streams.input)
            return true
        } finally {
            runCatching { writeCmd(streams.out, CMD_CLOSE) }
            streams.close()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Device open (root)
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /** Pick [preferredNode] if it exists, else the first `/dev/ttyACM*`, else null. */
    private fun resolveNode(): String? {
        if (File(preferredNode).exists()) return preferredNode
        // Use root to enumerate — /dev may not be world-listable for our uid.
        val ls = RootShell.exec("ls /dev/ttyACM* 2>/dev/null")
        val first = ls.out.firstOrNull { it.trim().startsWith("/dev/ttyACM") }?.trim()
        return first
    }

    /** `su chmod 666 <node>` so our app uid can open it directly (SELinux Permissive here). */
    private fun ensureNodeReadable(node: String) {
        if (File(node).canRead() && File(node).canWrite()) return
        val r = RootShell.exec("chmod 666 ${RootShell.quote(node)}")
        if (!r.ok) Log.w(TAG, "chmod on $node failed (${r.code}); will try su cat/echo fallback")
    }

    private open class Streams(val input: java.io.InputStream, val out: java.io.OutputStream) {
        open fun close() { runCatching { input.close() }; runCatching { out.close() } }
    }

    /**
     * Open read + write streams on [node]. Primary path: direct [FileInputStream]/[FileOutputStream]
     * after the chmod. If that throws (chmod refused / stricter policy), fall back to piping through
     * a persistent `su` shell: `cat <node>` for reads and the shell's stdin for control writes.
     */
    private fun openStreams(node: String): Streams? {
        // Primary: direct fds.
        runCatching {
            val input = FileInputStream(node)
            val out = FileOutputStream(node)
            return Streams(input, out)
        }.onFailure { Log.d(TAG, "direct open failed: ${it.message}; trying su pipe") }

        // Fallback: one root shell, cat for input, its stdin for output. `stdbuf -o0` keeps cat from
        // buffering so frames arrive promptly. The control commands are echoed into the SAME node
        // via the shell (`printf '...\r' > node`) — done by writeCmd when out is this shell stdin.
        return runCatching {
            val p = ProcessBuilder("su", "-c", "stdbuf -o0 cat ${shQuote(node)}")
                .redirectErrorStream(false).start()
            // Writes go through a second short-lived `su -c printf` per command (see writeCmd);
            // here we only need the read stream, so the "out" is a sink that writeCmd overrides.
            SuStreams(p, node)
        }.onFailure { Log.w(TAG, "su pipe open failed: ${it.message}") }.getOrNull()
    }

    /** Fallback streams: read from `su cat`, write each control cmd via a fresh `su -c printf`. */
    private class SuStreams(private val proc: Process, node: String) :
        Streams(proc.inputStream, NullOut(node)) {
        override fun close() { super.close(); runCatching { proc.destroy() } }
    }

    /** OutputStream whose writes are ignored; [writeCmd] detects it and routes via `su -c printf`. */
    private class NullOut(val node: String) : java.io.OutputStream() {
        override fun write(b: Int) {}
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // slcan write / read
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /** Send one slcan control command + CR. Never a transmit (`t`/`T`) frame. */
    private fun writeCmd(out: java.io.OutputStream, cmd: String) {
        require(!cmd.startsWith("t") && !cmd.startsWith("T")) { "listen-only: refuse to transmit" }
        if (out is NullOut) {
            // su-pipe fallback: printf the command+CR into the node as root.
            RootShell.exec("printf %s ${RootShell.quote(cmd + CR)} > ${RootShell.quote(out.node)}")
            return
        }
        out.write((cmd + CR).toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    /**
     * Read for up to [probeMs] and feed any complete lines to the parser. Returns true if at least
     * one valid frame was decoded — used to decide L-vs-O. probeMs = Long.MAX_VALUE means "loop
     * until cancelled/EOF" (the steady-state read loop calls [readLoop] which uses that).
     */
    private suspend fun readInto(input: java.io.InputStream, probeMs: Long): Boolean {
        val buf = ByteArray(READ_BUF)
        val line = StringBuilder(64)
        val deadline = if (probeMs == Long.MAX_VALUE) Long.MAX_VALUE else nowMs() + probeMs
        var any = false
        while (currentCoroutineIsActive() && nowMs() < deadline) {
            val n = input.read(buf)               // blocking; EOF (-1) when node removed
            if (n < 0) return any
            for (i in 0 until n) {
                val c = buf[i].toInt().toChar()
                if (c == CR || c == '\n') {
                    if (line.isNotEmpty()) { if (handleLine(line.toString())) any = true; line.setLength(0) }
                } else if (line.length < 64) {
                    line.append(c)                // guard against a runaway line with no CR
                } else {
                    line.setLength(0)
                }
            }
        }
        return any
    }

    /** Steady-state loop: read until cancelled or the node goes away. */
    private suspend fun readLoop(input: java.io.InputStream) {
        readInto(input, probeMs = Long.MAX_VALUE)
    }

    /**
     * Parse one slcan line and, if it is a standard/extended data frame, decode + merge it.
     * @return true if a frame decoded to a known signal (drives the L/O probe).
     */
    private fun handleLine(raw: String): Boolean {
        val s = raw.trim()
        if (s.isEmpty()) return false
        val kind = s[0]
        val (idHexLen, isData) = when (kind) {
            't' -> 3 to true    // standard 11-bit data frame
            'T' -> 8 to true    // extended 29-bit data frame (Toyota is 11-bit, but parse anyway)
            else -> 0 to false  // 'V'/'v' version, bell, 'z'/'Z' tx-ack (we never tx) → ignore
        }
        if (!isData) return false
        // Layout: <kind><id:idHexLen><dlc:1><data:dlc*2 hex>
        if (s.length < 1 + idHexLen + 1) return false
        val id = s.substring(1, 1 + idHexLen).toIntOrNull(16) ?: return false
        val dlc = s.substring(1 + idHexLen, 2 + idHexLen).toIntOrNull(16) ?: return false
        val dataHex = s.substring(2 + idHexLen)
        if (dataHex.length < dlc * 2) return false
        val data = ByteArray(dlc) { j ->
            dataHex.substring(j * 2, j * 2 + 2).toIntOrNull(16)?.toByte() ?: 0
        }

        _frameCount.value = _frameCount.value + 1
        val signal = RawCanDecoder.decode(id, data) ?: return false
        _state.value = _state.value.apply(signal, nowMs())
        return true
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // small platform shims (kept here so the class has one Android import surface)
    // ─────────────────────────────────────────────────────────────────────────────────────────

    private fun nowMs() = android.os.SystemClock.elapsedRealtime()
    private fun shQuote(s: String) = RootShell.quote(s)

    /**
     * Coroutine-active check without importing the whole coroutines context surface at call sites.
     * Uses [kotlinx.coroutines.currentCoroutineContext] via [isActive] on the running job.
     */
    private suspend fun currentCoroutineIsActive(): Boolean =
        kotlin.coroutines.coroutineContext[Job]?.isActive ?: true
}

/**
 * VehicleState — an immutable snapshot merging the latest of each decoded signal. Pure Kotlin (no
 * Android import) on purpose: it is the shared model the launcher UI reads, and keeping it framework
 * -free lets the *same* type be fed by BOTH CAN paths (raw CANable via [apply], vendor HiWorld via
 * [applyHiworld]) and unit-tested off-device.
 *
 * All fields nullable = "not yet seen". [atMs] fields let the UI grey out a stale reading.
 */
data class VehicleState(
    val speedKmh: Double? = null,
    val wheelSpeeds: RawCanSignal.WheelSpeeds? = null,
    val gear: RawCanSignal.GearPos? = null,
    val steeringAngleDeg: Double? = null,
    val gasFraction: Double? = null,
    val brakePressed: Boolean? = null,
    val brakeForceN: Int? = null,
    val cruiseActive: Boolean? = null,
    val cruiseAdaptive: Boolean? = null,
    val cruiseMainOn: Boolean? = null,
    val cruiseSetSpeedKmh: Int? = null,
    val doors: RawCanSignal.Doors? = null,
    val blinkerLeft: Boolean? = null,
    val blinkerRight: Boolean? = null,
    val hazard: Boolean? = null,
    /** Last update time (elapsedRealtime ms) for the whole snapshot. */
    val atMs: Long = 0L,
) {

    /**
     * Fold one raw-CAN signal in, returning a new snapshot. Unknown signals pass through unchanged.
     * [atMs] is supplied by the caller (the reader passes `SystemClock.elapsedRealtime()`) so this
     * type stays framework-free and unit-testable.
     */
    fun apply(sig: RawCanSignal, atMs: Long = this.atMs): VehicleState = when (sig) {
        is RawCanSignal.Speed -> copy(speedKmh = sig.kmh)
        is RawCanSignal.WheelSpeeds -> copy(wheelSpeeds = sig)
        is RawCanSignal.Gear -> copy(gear = sig.position)
        is RawCanSignal.SteeringAngle -> copy(steeringAngleDeg = sig.degrees)
        is RawCanSignal.GasPedal -> copy(gasFraction = sig.fraction)
        is RawCanSignal.Brake -> copy(brakePressed = sig.pressed, brakeForceN = sig.forceN)
        is RawCanSignal.Cruise -> copy(cruiseActive = sig.active, cruiseAdaptive = sig.adaptiveEngaged)
        is RawCanSignal.Cruise2 ->
            copy(cruiseMainOn = sig.mainOn, cruiseSetSpeedKmh = sig.setSpeedKmh,
                 // PCM_CRUISE_2's brake bit is authoritative if BRAKE (0xA6) hasn't been seen.
                 brakePressed = brakePressed ?: sig.brakePressed)
        is RawCanSignal.Doors -> copy(doors = sig)
        is RawCanSignal.Blinkers -> copy(blinkerLeft = sig.left, blinkerRight = sig.right, hazard = sig.hazard)
        is RawCanSignal.Unknown -> this
    }.copy(atMs = atMs)

    /**
     * Merge seam for the OTHER path: fold a [HiworldCanDecoder] `CanSignal` into the same snapshot.
     * Only fills fields the raw path did not (raw bus is higher fidelity), so running both is safe.
     * Left minimal on purpose — extend per field as the launcher needs it.
     */
    fun applyHiworld(sig: CanSignal): VehicleState = when (sig) {
        is CanSignal.VehicleInfo -> copy(speedKmh = speedKmh ?: sig.speedKmh)
        is CanSignal.BasicStatus -> copy(steeringAngleDeg = steeringAngleDeg ?: sig.steerAngleDeg)
        else -> this
    }
}
