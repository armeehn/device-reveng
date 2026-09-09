package com.ripostelabs.carlauncher.data

import android.util.Log
import com.ripostelabs.carlauncher.carlib.AccessoryCommand
import com.ripostelabs.carlauncher.carlib.AccessoryConfig
import com.ripostelabs.carlauncher.carlib.AccessoryController
import com.ripostelabs.carlauncher.carlib.AccessorySequence
import com.ripostelabs.carlauncher.carlib.AccessoryState
import com.ripostelabs.carlauncher.carlib.CommandResult
import com.ripostelabs.carlauncher.carlib.HttpAccessoryTransport
import com.ripostelabs.carlauncher.carlib.SequenceRunner
import com.ripostelabs.carlauncher.carlib.SequenceState
import com.ripostelabs.carlauncher.carlib.TriggerEngine
import com.ripostelabs.carlauncher.service.CanCaptureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AccessoryRuntime — the one place the accessory engine runs.
 *
 * Owns the controller, the sequence runner and the trigger engine for the current config, and
 * drives them from a single loop: tick the runner, poll the board, evaluate triggers against the
 * vehicle snapshot the capture service keeps. Started by the HOME activity for as long as it
 * lives, which for a car launcher is the whole drive — so "reverse → work lights" works with no
 * settings screen open.
 *
 * Reconfigured, not restarted, when the blob changes: the old engine is dropped whole and a new
 * one built, because a half-updated set of triggers is how a light ends up wired to nothing.
 * Everything a screen reads is a StateFlow; nothing a screen calls blocks.
 */
object AccessoryRuntime {

    private const val LOG_TAG = "Accessories"

    /** Runner ticks. Fine enough that a 400 ms hold is honoured to within a frame or two. */
    private const val TICK_MS = 100L

    /** Board polls. A physical switch flipped behind our back shows up within this. */
    private const val POLL_MS = 5_000L

    private var controller: AccessoryController? = null
    private var runner: SequenceRunner? = null
    private var engine: TriggerEngine? = null
    private var loop: Job? = null

    private val _config = MutableStateFlow(AccessoryConfig())
    val config: StateFlow<AccessoryConfig> = _config.asStateFlow()

    private val _states = MutableStateFlow<Map<String, AccessoryState>>(emptyMap())
    val states: StateFlow<Map<String, AccessoryState>> = _states.asStateFlow()

    private val _sequence = MutableStateFlow<SequenceState>(SequenceState.Idle)
    val sequence: StateFlow<SequenceState> = _sequence.asStateFlow()

    /** Rebuild the engine for [json]. Called on every change of the stored blob. */
    @Synchronized
    fun configure(json: String) {
        val parsed = AccessoryConfig.parse(json)
        parsed.problems.forEach { Log.w(LOG_TAG, "config: $it") }

        val transport = HttpAccessoryTransport(parsed.baseUrl.ifBlank { UNCONFIGURED_URL })
        val c = AccessoryController(transport, parsed.accessories)
        controller = c
        runner = SequenceRunner(c)
        engine = TriggerEngine(parsed.toTriggers())

        _config.value = parsed
        _states.value = emptyMap()
        _sequence.value = SequenceState.Idle
        Log.i(LOG_TAG, "configured: ${parsed.accessories.size} accessories, " +
            "${parsed.sequences.size} sequences, ${parsed.triggers.size} triggers")
    }

    /** Run the loop in [scope]. Idempotent; a second start is ignored while the first lives. */
    fun start(scope: CoroutineScope) {
        if (loop?.isActive == true) {
            return
        }

        loop = scope.launch(Dispatchers.IO) {
            // Two jobs, not one loop. On the emulator the 5 s poll ran inside the tick's lock,
            // and three HTTP round trips to the board stretched a 400 ms hold to a second and a
            // 2 s hold to four. A poll is slow and does not care about timing; a tick is cheap
            // and is the timing. They no longer wait for each other.
            launch {
                while (true) {
                    step(System.currentTimeMillis())
                    delay(TICK_MS)
                }
            }
            launch {
                while (true) {
                    poll(System.currentTimeMillis())
                    delay(POLL_MS)
                }
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }

    /** One command from a screen. Runs on IO; the result is what the board confirmed. */
    suspend fun send(id: String, command: AccessoryCommand): CommandResult = withContext(Dispatchers.IO) {
        val c = controller ?: return@withContext CommandResult.REJECTED
        val result = c.send(id, command, System.currentTimeMillis())
        _states.value = c.states.value
        result
    }

    /**
     * Start a sequence by hand. Anything running is cancelled, as the runner documents.
     *
     * Deferred to the IO loop rather than started here. [SequenceRunner.start] runs the first
     * step at once, and a step is an HTTP call; on the emulator, tapping "Welcome" from the page
     * threw NetworkOnMainThreadException and took the launcher down. Single commands already
     * went through Dispatchers.IO; this path did not. The loop picks the request up within one
     * tick, which is faster than a finger can notice.
     */
    fun run(sequence: AccessorySequence) {
        pendingStart = sequence
    }

    /** A sequence handed to [run], waiting for the IO loop to start it off the main thread. */
    @Volatile
    private var pendingStart: AccessorySequence? = null

    fun cancel() {
        synchronized(this) {
            runner?.cancel(System.currentTimeMillis())
            _sequence.value = runner?.state ?: SequenceState.Idle
        }
    }

    /**
     * One tick. Triggers are read from the snapshot the capture service keeps, so a door opening
     * on either bus can start a sequence. Fired sequences run last-wins, in registration order,
     * exactly as the engine hands them back. A step that sends a command still does one HTTP
     * round trip here; that is the one network call a hold has to absorb, and it is bounded by
     * the transport's 1 s timeouts.
     */
    private fun step(now: Long) {
        synchronized(this) {
            val c = controller ?: return
            val r = runner ?: return
            val e = engine ?: return

            e.evaluate(CanCaptureService.vehicle().snapshot.value, now).forEach { r.start(it, now) }

            // A manual start from the page, taken here so its first step runs on this thread.
            pendingStart?.let { r.start(it, now); pendingStart = null }

            val before = r.state
            val t0 = System.currentTimeMillis()
            r.tick(now)
            val after = r.state
            if (before != after) {
                Log.d(LOG_TAG, "tick@$now advanced ${before::class.simpleName}->${after::class.simpleName} " +
                    "in ${System.currentTimeMillis() - t0}ms; next due ${(after as? SequenceState.Running)?.dueAt?.let { it - now } ?: "-"}ms")
            }

            _states.value = c.states.value
            _sequence.value = r.state
        }
    }

    /**
     * Ask the board what every accessory is doing. Deliberately not under the tick lock: three
     * accessories at up to 1 s each is three seconds a sequence must not wait for. The controller
     * merges states atomically, so a reply landing mid-tick cannot lose a command's result.
     */
    private fun poll(now: Long) {
        val c = controller ?: return
        c.refreshAll(now)
        _states.value = c.states.value
    }

    /** A base URL nothing answers on, so an unconfigured board reads UNREACHABLE, never "off". */
    private const val UNCONFIGURED_URL = "http://127.0.0.1:1"
}
