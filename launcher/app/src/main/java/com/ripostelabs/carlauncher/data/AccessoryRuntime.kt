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
            var lastPoll = 0L
            while (true) {
                val now = System.currentTimeMillis()
                step(now, poll = now - lastPoll >= POLL_MS)
                if (now - lastPoll >= POLL_MS) {
                    lastPoll = now
                }
                delay(TICK_MS)
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

    /** Start a sequence by hand. Anything running is cancelled, as the runner documents. */
    fun run(sequence: AccessorySequence) {
        synchronized(this) {
            runner?.start(sequence, System.currentTimeMillis())
            _sequence.value = runner?.state ?: SequenceState.Idle
        }
    }

    fun cancel() {
        synchronized(this) {
            runner?.cancel(System.currentTimeMillis())
            _sequence.value = runner?.state ?: SequenceState.Idle
        }
    }

    /**
     * One turn of the loop. Triggers are read from the snapshot the capture service keeps, so a
     * door opening on either bus can start a sequence. Fired sequences run last-wins, in
     * registration order, exactly as the engine hands them back.
     */
    private fun step(now: Long, poll: Boolean) {
        synchronized(this) {
            val c = controller ?: return
            val r = runner ?: return
            val e = engine ?: return

            e.evaluate(CanCaptureService.vehicle().snapshot.value, now).forEach { r.start(it, now) }
            r.tick(now)
            if (poll) {
                c.refreshAll(now)
            }

            _states.value = c.states.value
            _sequence.value = r.state
        }
    }

    /** A base URL nothing answers on, so an unconfigured board reads UNREACHABLE, never "off". */
    private const val UNCONFIGURED_URL = "http://127.0.0.1:1"
}
