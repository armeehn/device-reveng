package com.ripostelabs.carlauncher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.ripostelabs.carlauncher.BuildConfig
import com.ripostelabs.carlauncher.carlib.BtCarKit
import com.ripostelabs.carlauncher.carlib.BtCarKitSnapshot
import com.ripostelabs.carlauncher.carlib.CarPlayState
import com.ripostelabs.carlauncher.carlib.HfCall
import com.ripostelabs.carlauncher.carlib.HfCallState
import com.ripostelabs.carlauncher.carlib.IncomingCallGate
import com.ripostelabs.carlauncher.carlib.LauncherFront
import com.ripostelabs.carlauncher.data.CallRinger
import com.ripostelabs.carlauncher.data.CallerNames
import com.ripostelabs.carlauncher.ui.theme.CarTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RAV4-152 — wires [IncomingCallGate] to the window, the ringer and the phonebook.
 *
 * ```
 *  BtCarKit.snapshot ──┐ (or the debug RING broadcast on a debug build)
 *  carplayState ───────┼─▶ gate ─▶ IncomingCallView ─┬─▶ CallerNames (IO) ─▶ IncomingCallWindow
 *  front ──────────────┘                            └─▶ CallRinger
 * ```
 *
 * Riposte OS 0.2 only: on the vendor slot [carKit] is null and btsuite floats its own window.
 * A debug build also listens for [ACTION_DEBUG_CALL] so the farm (no HF client calls) can put
 * the window up:
 * `am broadcast -a com.ripostelabs.carlauncher.debug.CALL --es number +12505550142`
 * (`--ei state 7` ends it).
 */
class IncomingCalls(
    private val context: Context,
    private val carKit: BtCarKit?,
    private val names: CallerNames,
) {

    private val gate = IncomingCallGate()
    private val ringer = CallRinger(context)
    private val window = IncomingCallWindow(context, onAnswer = ::answer, onDecline = ::decline)
    private val debugCall = MutableStateFlow<BtCarKitSnapshot?>(null)

    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val state = intent.getIntExtra(EXTRA_STATE, HfCallState.INCOMING)
            if (state == HfCallState.TERMINATED) {
                debugCall.value = null
                return
            }
            val call = HfCall(state = state, number = intent.getStringExtra(EXTRA_NUMBER))
            debugCall.value = BtCarKitSnapshot(adapterOn = true, hfConnected = true, calls = listOf(call))
        }
    }

    fun start(scope: CoroutineScope, carPlay: StateFlow<CarPlayState>, front: Flow<LauncherFront>) {
        if (carKit == null && !BuildConfig.DEBUG) {
            return
        }
        if (BuildConfig.DEBUG) {
            // Exported so `am broadcast` from the shell reaches it; debug builds only.
            context.registerReceiver(debugReceiver, IntentFilter(ACTION_DEBUG_CALL), Context.RECEIVER_EXPORTED)
        }

        // The debug ring, while set, stands in for the car-kit's snapshot.
        val kit = carKit?.snapshot ?: flowOf(BtCarKitSnapshot())
        val source = combine(kit, debugCall) { real, fake -> fake ?: real }

        scope.launch {
            combine(source, carPlay, front) { s, cp, f -> gate.decide(s, cp, f) }.collect { call ->
                ringer.set(call.ring)
                val name = withContext(Dispatchers.IO) { names.lookup(call.number) }
                window.render(call, name)
            }
        }
    }

    fun update(theme: CarTheme, night: Boolean) = window.update(theme, night)

    fun stop() {
        if (BuildConfig.DEBUG) {
            runCatching { context.unregisterReceiver(debugReceiver) }
        }
        ringer.set(false)
        window.hide()
    }

    private fun answer() {
        Log.i(TAG, "answer from the window")
        closeRing()
        carKit?.answer()
        debugCall.value = debugCall.value?.let { s -> s.copy(calls = s.calls.map { it.copy(state = HfCallState.ACTIVE) }) }
    }

    private fun decline() {
        Log.i(TAG, "decline from the window")
        closeRing()
        carKit?.hangUp()
        debugCall.value = null
    }

    /** Close at once: the HF client's next AG_CALL_CHANGED can take a second to follow. */
    private fun closeRing() {
        gate.onAction()
        ringer.set(false)
        window.hide()
    }

    private companion object {
        const val TAG = "IncomingCalls"
        const val ACTION_DEBUG_CALL = "com.ripostelabs.carlauncher.debug.CALL"
        const val EXTRA_STATE = "state"
        const val EXTRA_NUMBER = "number"
    }
}
