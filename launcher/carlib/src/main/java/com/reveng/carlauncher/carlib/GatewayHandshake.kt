package com.reveng.carlauncher.carlib

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * v3.0 — the launcher ↔ gateway UI-mode handshake (CAR_API §6.2).
 *
 * The vendor gateway and the stock launcher exchange UI mode over a pair of broadcasts
 * (`EventUtils.java:66,76`). Announcing ourselves on the launcher→gateway half is what makes the
 * vendor stack treat *us* as the launcher rather than a third-party app that happens to be
 * showing: it is the difference between the gateway routing launcher-directed events to us and
 * routing them into the void.
 *
 * **What this is not.** It does not drive our theming. `CUSTOMERUI_NOTES.md` records that the
 * vendor launcher themes itself from `SystemPropertiesX.setUiModeNight/Day` plus a local
 * `uiModeNightChanged` broadcast — *not* from this pair — so treating the gateway's half as a
 * day/night source would be reading a channel the vendor does not actually theme from. Day/night
 * stays on [CarEvents.dayNight] and the `Sys_Day_Night_Mode` SysVar.
 *
 * **Payload is unverified.** The two action strings are confirmed; the extras the gateway expects
 * are not recovered anywhere in the decompile. We therefore send the action with a night flag
 * under several candidate keys — extra keys a receiver does not read are ignored by Android, so
 * a wrong guess is inert rather than harmful. Nothing here writes to the vehicle.
 */
class GatewayHandshake(context: Context) {

    companion object {
        private const val TAG = "GatewayHandshake"

        /** gateway → launcher (CAR_API §6.2). */
        const val ACTION_EVENTCENTER_TO_LAUNCHER_UIMODE =
            "com.szchoiceway.eventcenter.EventUtils.ACTION_EVENTCENTER_TO_LAUNCHER_UIMODE_EVENT"

        /** launcher → gateway (CAR_API §6.2). */
        const val ACTION_LAUNCHER_TO_EVENTCENTER_UIMODE =
            "com.szchoiceway.eventcenter.EventUtils.ACTION_LAUNCHER_TO_EVENTCENTER_UIMODE_EVENT"

        /** The vendor gateway package, so the announcement is explicit rather than a shout. */
        private const val GATEWAY_PACKAGE = "com.szchoiceway.eventcenter"

        /** ⚠ GUESSED extra keys — see the class KDoc. Unread extras are harmless. */
        private val UIMODE_EXTRA_KEYS = arrayOf(
            "uiMode",
            "UIMODE",
            "EventUtils.UIMODE",
            "uiModeNight",
        )
    }

    private val appContext = context.applicationContext

    private val _gatewayAcknowledged = MutableStateFlow(false)
    /**
     * True once the gateway has sent us its half of the pair.
     *
     * This is the only evidence available that the handshake means anything on this unit: we
     * cannot tell whether our announcement was received, but a gateway→launcher broadcast
     * arriving is proof the channel is live. Surfaced so the settings screen can report it
     * instead of claiming success it cannot see.
     */
    val gatewayAcknowledged: StateFlow<Boolean> = _gatewayAcknowledged.asStateFlow()

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_EVENTCENTER_TO_LAUNCHER_UIMODE) {
                return
            }
            _gatewayAcknowledged.value = true
            Log.i(TAG, "gateway acknowledged the launcher UIMODE channel")
        }
    }

    fun register() {
        if (registered) {
            return
        }
        val filter = IntentFilter(ACTION_EVENTCENTER_TO_LAUNCHER_UIMODE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
        registered = true
    }

    fun unregister() {
        if (!registered) {
            return
        }
        runCatching { appContext.unregisterReceiver(receiver) }
        registered = false
    }

    /**
     * Announce our current UI mode to the gateway. Safe to call on every day/night change and on
     * resume — it is a fire-and-forget broadcast, and re-announcing costs nothing.
     */
    fun announceUiMode(night: Boolean) {
        val intent = Intent(ACTION_LAUNCHER_TO_EVENTCENTER_UIMODE).apply {
            setPackage(GATEWAY_PACKAGE)
            UIMODE_EXTRA_KEYS.forEach { key -> putExtra(key, night) }
        }
        runCatching { appContext.sendBroadcast(intent) }
            .onFailure { Log.w(TAG, "UIMODE announce failed", it) }
    }
}
