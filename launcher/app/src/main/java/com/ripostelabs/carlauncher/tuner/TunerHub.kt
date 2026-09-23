package com.ripostelabs.carlauncher.tuner

import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TunerHub — the one [ITuner] behind [TunerService], for as long as HOME holds a car link.
 *
 * ── Where this sits ─────────────────────────────────────────────────────────────────────────────
 *
 *     com.ripostelabs.radio ──bind──▶ TunerService ──▶ TunerHub.binder ──▶ TunerPort ──▶ CarService
 *                          ◀─onState─────────────────────┘  ◀── RadioStateHolder.state
 *
 * MainActivity owns the CarService and attaches it here on create, detaches on destroy. A caller
 * bound while no port is attached gets the idle answers (false, empty state), never a crash:
 * the radio app binds at its own start-up, which may beat HOME's.
 *
 * Callbacks live in a plain list rather than RemoteCallbackList so the fan-out is testable on
 * the JVM; a dead callback throws on the next push and is dropped there.
 */
object TunerHub {
    private const val TAG = "TunerHub"

    @Volatile
    private var port: TunerPort? = null
    private var pump: Job? = null
    private val callbacks = CopyOnWriteArrayList<ITunerCallback>()

    /** Wire the car link in. [scope] outlives the binder calls; the state pump runs on it. */
    fun attach(newPort: TunerPort, scope: CoroutineScope) {
        detach()
        port = newPort
        pump = scope.launch {
            launch { newPort.state.collect { s -> push { it.onState(TunerState.of(s)) } } }
            launch { newPort.sourceLost.drop(1).collect { push { it.onSourceLost() } } }
        }
    }

    fun detach() {
        pump?.cancel()
        pump = null
        port = null
    }

    /** Idle answers: what an unattached hub tells a caller. */
    private val idle = TunerState.of(com.ripostelabs.carlauncher.carlib.RadioState())

    val binder: ITuner.Stub = object : ITuner.Stub() {
        override fun claim(): Boolean = port?.claim() ?: false
        override fun release() { port?.release() }
        override fun isClaimed(): Boolean = port?.isClaimed() ?: false
        override fun sendKey(key: Int) { port?.sendKey(key) }
        override fun tune(freq: Int, fm: Boolean) { port?.tune(freq, fm) }
        override fun getState(): TunerState = port?.let { TunerState.of(it.state.value) } ?: idle

        override fun registerCallback(cb: ITunerCallback?) {
            if (cb == null || callbacks.contains(cb)) {
                return
            }

            callbacks.add(cb)
            // A late binder gets the cache at once, as the vendor's setRadioCallback did with REFRESH.
            runCatching { cb.onState(getState()) }
        }

        override fun unregisterCallback(cb: ITunerCallback?) { callbacks.remove(cb) }
        override fun selectPreset(slot: Int) { port?.selectPreset(slot) }
        override fun storePreset(slot: Int) { port?.storePreset(slot) }
    }

    private fun push(call: (ITunerCallback) -> Unit) {
        for (cb in callbacks) {
            try {
                call(cb)
            } catch (e: RemoteException) {
                Log.i(TAG, "callback gone: ${e.message}")
                callbacks.remove(cb)
            }
        }
    }
}
