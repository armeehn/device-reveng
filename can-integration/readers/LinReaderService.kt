package com.ripostelabs.carlauncher.carlib

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * LinReader — Android glue turning the RAV4 climate **LIN bus** (tapped through a TJA1020-class LIN
 * transceiver into a USB-UART) into a live [StateFlow]<[LinClimateState]>, using [LinClimateDecoder]
 * for the byte→signal step. This is the THIRD independent car-data source in the launcher; see
 * [CanableReader] (raw powertrain CAN) and [HiworldCanDecoder] (vendor digest). All three fold into
 * one model — see [LinClimateState] and the [combineWithVehicle] / [VehicleState] merge notes below.
 *
 * **Deliberately a plain class, not an `android.app.Service`** — same reasoning as [CanableReader]:
 * the launcher scopes its car sources itself; lifecycle is explicit [start] / [stop].
 *
 * ── Wire format (why this is not slcan-style line parsing) ──────────────────────────────────────
 * The LIN bus is **19200 baud, 8N1**, and a frame is a raw byte sequence — BREAK, sync `0x55`, PID,
 * data bytes, checksum — NOT ASCII lines. A plain USB-UART (no LIN hardware framing) usually shows
 * the BREAK as a stray `0x00`, so we ignore everything until a `0x55`, take the next byte as the
 * PID, slice [LinClimateDecoder.frameDataLenFor] data bytes + 1 checksum byte, verify, and decode.
 * A `0x55` that happens to appear inside data can cause a rare false sync; the known-length +
 * checksum gate makes that self-correct within a frame or two (we only frame on a PID we have a
 * length for). Partial frames straddling a `read()` are carried in [carry] to the next read.
 *
 * ── Device node: FTDI vs CDC ────────────────────────────────────────────────────────────────────
 * A **FTDI**-based USB-UART enumerates as `/dev/ttyUSB0`; a **CDC-ACM** one as `/dev/ttyACM*`. The
 * CANable (CAN adapter) is already on `ttyACM0`, so the LIN USB-UART is *most likely* `ttyUSB0` —
 * hence [DEFAULT_NODE]. [resolveNode] tries [preferredNode] first, then any `ttyUSB*`, then any
 * `ttyACM*` NOT already taken by the CANable, so a CDC LIN dongle still works. Hardware is offline
 * as of writing, so the node is a documented best-guess — override [preferredNode] once confirmed.
 *
 * ── Port config: 19200 8N1 via root `stty` ──────────────────────────────────────────────────────
 * We take the pragmatic **root + `stty`** path (this head unit is rooted, SELinux Permissive):
 * [configurePort] runs `stty -F <node> 19200 cs8 -cstopb -parenb raw -echo` through [RootShell].
 * For an FTDI node the 19200 baud is a REAL divisor and mandatory; for a CDC-ACM node the baud is
 * virtual (the slave sets the real rate) but `raw -echo` still stops the tty layer cooking bytes.
 * A JNI/termios path would avoid the `stty` dependency but buys nothing on a rooted unit — noted in
 * LIN_INTEGRATION.md.
 *
 * ── LISTEN-ONLY, always ─────────────────────────────────────────────────────────────────────────
 * We only ever open the node for reading (a `cat`/`FileInputStream`). Nothing here writes a byte to
 * the LIN bus. The only privileged writes are `chmod`/`stty` on the *device node*, never bus frames.
 *
 * All blocking I/O runs on [Dispatchers.IO]; [state] is safe to collect from the main thread.
 */
class LinReader(
    /** Serial node. FTDI → `/dev/ttyUSB0` (default, since the CANable owns `ttyACM0`). See [resolveNode]. */
    private val preferredNode: String = DEFAULT_NODE,
) {

    companion object {
        private const val TAG = "LinReader"

        /** FTDI USB-UART default; the CANable already occupies `/dev/ttyACM0`. */
        const val DEFAULT_NODE = "/dev/ttyUSB0"

        /** `stty` line settings for LIN: 19200 baud, 8 data bits, no parity, 1 stop bit, raw. */
        private const val STTY_ARGS = "19200 cs8 -cstopb -parenb -icrnl -inpck -istrip raw -echo"

        /** LIN sync byte — every frame begins here once the BREAK is past. */
        private const val SYNC = 0x55

        /** Reconnect backoff bounds (adapter unplugged / bus asleep with the car off). */
        private const val BACKOFF_START_MS = 500L
        private const val BACKOFF_MAX_MS = 8_000L

        private const val READ_BUF = 2048

        /** Cap on retained un-synced bytes, so a node that emits garbage can't grow [carry] forever. */
        private const val MAX_CARRY = 512
    }

    private val _state = MutableStateFlow(LinClimateState())
    /** Latest climate snapshot. Starts invalid/empty; fields fill in as frames arrive. */
    val state: StateFlow<LinClimateState> = _state.asStateFlow()

    private val _connected = MutableStateFlow(false)
    /** true while the node is open and bytes are flowing. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _frameCount = MutableStateFlow(0L)
    /** Count of successfully-decoded LIN frames (status + buttons), for a diagnostics view. */
    val frameCount: StateFlow<Long> = _frameCount.asStateFlow()

    /** Partial frame bytes carried across [InputStream.read] boundaries. */
    private var carry = ByteArray(0)

    private var job: Job? = null

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /** Start the read/reconnect loop on [scope]. Idempotent — a second call is ignored. */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    /** Stop reading. Safe to call more than once. */
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
        while (isActive()) {
            val node = resolveNode()
            if (node == null) {
                Log.d(TAG, "no ttyUSB*/ttyACM* LIN node present (adapter unplugged?); retry in ${backoff}ms")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(BACKOFF_MAX_MS)
                continue
            }
            val ok = runCatching { session(node) }
                .onFailure { Log.w(TAG, "LIN session on $node ended: ${it.message}") }
                .getOrDefault(false)

            _connected.value = false
            backoff = if (ok) BACKOFF_START_MS else (backoff * 2).coerceAtMost(BACKOFF_MAX_MS)
            delay(backoff)
        }
    }

    /** One open→read session. Returns true if it opened and ended normally (EOF/cancel), else false. */
    private suspend fun session(node: String): Boolean {
        ensureNodeReadable(node)
        configurePort(node)
        carry = ByteArray(0)
        val input = openInput(node) ?: return false
        try {
            _connected.value = true
            Log.i(TAG, "LIN node open on $node @ 19200 8N1 (listen-only)")
            readLoop(input)
            return true
        } finally {
            runCatching { input.close() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Device open (root) — mirrors CanableReader's approach
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Pick [preferredNode] if present, else the first `ttyUSB*`, else the first `ttyACM*` that is not
     * the CANable's `ttyACM0`. Uses root to enumerate since `/dev` may not be world-listable.
     */
    private fun resolveNode(): String? {
        if (File(preferredNode).exists()) return preferredNode
        val usb = RootShell.exec("ls /dev/ttyUSB* 2>/dev/null").out
            .firstOrNull { it.trim().startsWith("/dev/ttyUSB") }?.trim()
        if (usb != null) return usb
        // CDC fallback: any ttyACM that isn't the CANable (ttyACM0).
        return RootShell.exec("ls /dev/ttyACM* 2>/dev/null").out
            .map { it.trim() }
            .firstOrNull { it.startsWith("/dev/ttyACM") && it != CanableReader.DEFAULT_NODE }
    }

    /** `su chmod 666 <node>` so our app uid can open it directly (SELinux Permissive here). */
    private fun ensureNodeReadable(node: String) {
        if (File(node).canRead()) return
        val r = RootShell.exec("chmod 666 ${RootShell.quote(node)}")
        if (!r.ok) Log.w(TAG, "chmod on $node failed (${r.code}); will try su cat fallback")
    }

    /** Set 19200 8N1 raw on the node via root `stty`. Best-effort; some CDC stacks ignore it. */
    private fun configurePort(node: String) {
        val r = RootShell.exec("stty -F ${RootShell.quote(node)} $STTY_ARGS")
        if (!r.ok) Log.w(TAG, "stty on $node failed (${r.code}: ${r.err.joinToString()}); continuing")
    }

    /**
     * Open a read stream on [node]. Primary: a direct [FileInputStream] after the chmod. Fallback:
     * pipe through a persistent root `stdbuf -o0 cat <node>` (unbuffered so frames arrive promptly),
     * for a stricter policy where the chmod was refused.
     */
    private fun openInput(node: String): InputStream? {
        runCatching { return FileInputStream(node) }
            .onFailure { Log.d(TAG, "direct open failed: ${it.message}; trying su cat pipe") }
        return runCatching {
            ProcessBuilder("su", "-c", "stdbuf -o0 cat ${RootShell.quote(node)}")
                .redirectErrorStream(false).start().inputStream
        }.onFailure { Log.w(TAG, "su cat open failed: ${it.message}") }.getOrNull()
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // Read loop + byte-level LIN framing
    // ─────────────────────────────────────────────────────────────────────────────────────────

    /** Steady-state loop: read until cancelled or the node goes away (EOF/IOException). */
    private suspend fun readLoop(input: InputStream) {
        val buf = ByteArray(READ_BUF)
        while (isActive()) {
            val n = input.read(buf)          // blocking; EOF (-1) when node removed
            if (n < 0) return
            if (n == 0) continue
            // Append the new bytes to any carried partial frame, then parse out complete frames.
            val merged = if (carry.isEmpty()) buf.copyOf(n) else carry + buf.copyOfRange(0, n)
            val consumed = parseFrames(merged)
            carry = if (consumed >= merged.size) ByteArray(0) else merged.copyOfRange(consumed, merged.size)
            // Guard against unbounded growth if we never re-sync (garbage / wrong baud).
            if (carry.size > MAX_CARRY) carry = carry.copyOfRange(carry.size - MAX_CARRY, carry.size)
        }
    }

    /**
     * Scan [b] for complete LIN frames, emitting each decoded [LinSignal]. Returns the number of
     * bytes consumed; the caller retains the unconsumed tail (a partial frame) for the next read.
     *
     * Framing rule: find `0x55` (sync); the next byte is the PID. If we have a known length for that
     * PID and enough bytes for `data + checksum`, decode the frame and advance past it. If the PID is
     * unknown-length (a real unmapped id, or a false `0x55` inside data), we can't safely slice it —
     * advance ONE byte past the `0x55` and keep scanning (self-resync). A known PID with not-yet-
     * enough bytes stops the scan at that `0x55`, so the partial frame is carried forward intact.
     */
    private fun parseFrames(b: ByteArray): Int {
        var i = 0
        while (i < b.size) {
            if ((b[i].toInt() and 0xFF) != SYNC) { i++; continue }
            // Need at least the PID byte after the sync.
            if (i + 1 >= b.size) return i            // keep from this 0x55; wait for more
            val pid = b[i + 1].toInt() and 0xFF
            val dataLen = LinClimateDecoder.frameDataLenFor(pid)
            if (dataLen == null) {
                // Unknown/unframeable id after sync: log once for capture, resync past the 0x55.
                Log.v(TAG, "LIN unknown/unframeable id after sync: 0x%02X (rawId 0x%02X)"
                    .format(pid, LinClimateDecoder.rawId(pid)))
                i++
                continue
            }
            val frameEnd = i + 2 + dataLen + 1       // 0x55 + PID + data + checksum
            if (frameEnd > b.size) return i          // partial frame; carry from this 0x55
            val data = b.copyOfRange(i + 2, i + 2 + dataLen)
            val checksum = b[i + 2 + dataLen].toInt() and 0xFF
            emit(pid, data, checksum)
            i = frameEnd
        }
        return i
    }

    /** Verify the checksum, decode, and fold the signal into [_state]. */
    private fun emit(pid: Int, data: ByteArray, checksum: Int) {
        val kind = LinClimateDecoder.verifyChecksum(pid, data, checksum)
        if (kind == LinClimateDecoder.ChecksumKind.NONE) {
            // Bad checksum → likely a false sync or line noise. Note it, but DON'T fold garbage in.
            Log.v(TAG, "LIN checksum mismatch id=0x%02X data=%s cks=0x%02X"
                .format(pid, data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }, checksum))
            return
        }
        val signal = LinClimateDecoder.decode(pid, data) ?: return
        _frameCount.value = _frameCount.value + 1
        _state.value = _state.value.apply(signal, nowMs())
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────
    // small platform / coroutine shims
    // ─────────────────────────────────────────────────────────────────────────────────────────

    private fun nowMs() = android.os.SystemClock.elapsedRealtime()

    private suspend fun isActive(): Boolean =
        kotlin.coroutines.coroutineContext[Job]?.isActive ?: true
}

/**
 * LinClimateState — immutable, framework-free snapshot of the HVAC state decoded off the LIN bus.
 *
 * Named `LinClimateState` to avoid colliding with the existing vendor-AIDL [ClimateState] in this
 * package (that one decodes the gateway's `getAirData()` byte frame; this one decodes the real LIN
 * bus). All fields default to "not seen yet" ([valid] = false) so the UI shows a placeholder rather
 * than fabricated numbers until a status frame arrives. Raw temp bytes are kept alongside the
 * UNCONFIRMED °C best-guess (see [LinClimateDecoder.setpointGuessC]).
 */
data class LinClimateState(
    val valid: Boolean = false,
    val fanSpeed: Int = 0,
    val mode: LinClimateDecoder.Mode = LinClimateDecoder.Mode.UNKNOWN,
    val modeRaw: Int = 0,
    val driverTempRaw: Int = -1,
    val passengerTempRaw: Int = -1,
    /** UNCONFIRMED °C best-guess set-points; pair with the raw bytes in the UI. */
    val driverSetpointC: Double = Double.NaN,
    val passengerSetpointC: Double = Double.NaN,
    val acOn: Boolean = false,
    val eco: Boolean = false,
    val illumination: Boolean = false,
    val rearDefrost: Boolean = false,
    val sync: Boolean = false,
    /** Last button the panel reported (0x39 frame), latched for the UI; null until one is seen. */
    val lastButton: LinClimateDecoder.Button? = null,
    val lastButtonAtMs: Long = 0L,
    /** Last update time (elapsedRealtime ms) for the whole snapshot. */
    val atMs: Long = 0L,
) {
    /** Human-ish set-temp label, or "--" when unknown (raw byte < 0). */
    fun driverTempLabel(): String = tempLabel(driverTempRaw)
    fun passengerTempLabel(): String = tempLabel(passengerTempRaw)

    private fun tempLabel(raw: Int): String =
        if (raw < 0) "--" else "%.1f°".format(LinClimateDecoder.setpointGuessC(raw))

    /**
     * Fold one decoded [LinSignal] in, returning a new snapshot. [atMs] is supplied by the caller
     * (the reader passes `SystemClock.elapsedRealtime()`) so this type stays framework-free.
     */
    fun apply(sig: LinSignal, atMs: Long = this.atMs): LinClimateState = when (sig) {
        is LinSignal.ClimateStatus -> copy(
            valid = true,
            fanSpeed = sig.fanSpeed,
            mode = sig.mode,
            modeRaw = sig.modeRaw,
            driverTempRaw = sig.driverTempRaw,
            passengerTempRaw = sig.passengerTempRaw,
            driverSetpointC = sig.driverSetpointC,
            passengerSetpointC = sig.passengerSetpointC,
            acOn = sig.acOn,
            eco = sig.eco,
            illumination = sig.illumination,
            rearDefrost = sig.rearDefrost,
            sync = sig.sync,
        )
        is LinSignal.ClimateButtons -> {
            val b = sig.primary
            if (b == null) this else copy(lastButton = b, lastButtonAtMs = atMs)
        }
        is LinSignal.Unknown -> this
    }.copy(atMs = atMs)
}

/**
 * Unified snapshot carrying BOTH buses in one value: the CAN [VehicleState] and the LIN
 * [LinClimateState]. This is the standalone, compile-today seam that fulfils "one StateFlow carries
 * both buses" **without editing** the CAN reader's [VehicleState].
 *
 * The PREFERRED long-term integration is a one-line field on [VehicleState] itself, mirroring its
 * existing `applyHiworld` seam — i.e. in `CanableReaderService.kt`:
 *
 * ```
 * data class VehicleState(
 *     …,
 *     val climate: LinClimateState? = null,          // ← add this field
 * ) {
 *     …
 *     /** Merge seam for the LIN climate bus, mirroring applyHiworld(). */
 *     fun applyLin(sig: LinSignal): VehicleState =
 *         copy(climate = (climate ?: LinClimateState()).apply(sig, atMs))
 * }
 * ```
 *
 * With that field in place, [LinReader] can push straight into the same `MutableStateFlow<VehicleState>`
 * the CANable reader owns (call `state.value = state.value.applyLin(sig)` in [LinReader.emit]),
 * collapsing the two flows into one. Until that edit lands, [combineWithVehicle] gives the same
 * single-stream result by pairing the two independent flows.
 */
data class UnifiedCarState(
    val vehicle: VehicleState = VehicleState(),
    val climate: LinClimateState = LinClimateState(),
)

/**
 * Combine a CAN [VehicleState] flow and a LIN [LinClimateState] flow into one hot
 * [StateFlow]<[UnifiedCarState]> on [scope] — the interim "one stream, both buses" seam described in
 * [UnifiedCarState]. Uses `combine` + `stateIn` so the UI collects a single flow.
 */
fun combineWithVehicle(
    vehicle: StateFlow<VehicleState>,
    climate: StateFlow<LinClimateState>,
    scope: CoroutineScope,
): StateFlow<UnifiedCarState> =
    combine(vehicle, climate) { v, c -> UnifiedCarState(v, c) }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            UnifiedCarState(vehicle.value, climate.value),
        )
