package com.ripostelabs.carlauncher.carlib

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
 * The launcher ↔ gateway UI-mode exchange (CAR_API §6.2).
 *
 * Not a handshake. The gateway delegates its day/night decision to the launcher: our broadcast
 * carries ONE int extra, [EXTRA_DAY_NIGHT_UIMODE], holding a `Sys_Day_Night_Mode` value, and the
 * gateway applies it verbatim (`EvtModel.java:1076` → `EventService.setDayNightMode(int)`,
 * `EventService.java:14043`). Anything that is not 1/2/3 — including the 0 a missing or wrongly
 * typed extra decodes to — means "follow the headlamps", which is how the old boolean payload
 * silently overrode the user's setting.
 *
 * The gateway→launcher half is a *request*, not an acknowledgement: the gateway sends the mode it
 * wants applied and expects the same int echoed back; if nothing comes within 2 s it applies the
 * value itself (`EventService.java:14856-14862`). It is sent only on a `Sys_Day_Night_Mode` change
 * or a headlamp change, so it proves nothing about liveness.
 *
 * Theming does not read this class: day/night stays on [CarEvents.dayNight].
 */
class GatewayHandshake(context: Context) {

    /** `Sys_Day_Night_Mode` values (`SysProviderOpt.java:288`, `EventService.java:14043-14087`). */
    enum class UiMode(val code: Int) {
        /** Follow the headlamps. Also what the gateway reads for any unknown code. */
        HEADLAMPS(0),
        DAY(1),
        NIGHT(2),
        /** Sunrise/sunset, computed by the gateway. */
        BY_TIME(3);

        companion object {
            /** Same fallthrough as the gateway's own switch: an unknown code is [HEADLAMPS]. */
            fun fromCode(code: Int): UiMode = entries.firstOrNull { it.code == code } ?: HEADLAMPS
        }
    }

    companion object {
        private const val TAG = "GatewayHandshake"

        /** gateway → launcher (`EventUtils.java:66`). */
        const val ACTION_EVENTCENTER_TO_LAUNCHER_UIMODE =
            "com.szchoiceway.eventcenter.EventUtils.ACTION_EVENTCENTER_TO_LAUNCHER_UIMODE_EVENT"

        /** launcher → gateway (`EventUtils.java:76`). */
        const val ACTION_LAUNCHER_TO_EVENTCENTER_UIMODE =
            "com.szchoiceway.eventcenter.EventUtils.ACTION_LAUNCHER_TO_EVENTCENTER_UIMODE_EVENT"

        /** The one extra both halves carry, int (`EventUtils.java:1235`). */
        const val EXTRA_DAY_NIGHT_UIMODE = "Extra_Day_Night_UiMode"

        /** The vendor gateway package, so the broadcast is explicit rather than a shout. */
        private const val GATEWAY_PACKAGE = "com.szchoiceway.eventcenter"
    }

    private val appContext = context.applicationContext

    private val _requested = MutableStateFlow<UiMode?>(null)
    /** The last mode the gateway asked us to echo, or null until it asks. Diagnostic only. */
    val requested: StateFlow<UiMode?> = _requested.asStateFlow()

    private var registered = false

    // The gateway's request: echo the same value back so it applies at once instead of after
    // its 2 s fallback. The value is the gateway's decision, so echoing cannot change it.
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_EVENTCENTER_TO_LAUNCHER_UIMODE) {
                return
            }
            val mode = UiMode.fromCode(intent.getIntExtra(EXTRA_DAY_NIGHT_UIMODE, UiMode.HEADLAMPS.code))
            _requested.value = mode
            Log.i(TAG, "gateway requested UI mode $mode")
            sendUiMode(mode)
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
     * Tell the gateway which day/night mode to apply. Fire-and-forget; safe to repeat. Every call
     * cancels the gateway's own pending day/night timers (`EventService.java:14046-14047`), so send
     * on change, not on a clock.
     */
    fun sendUiMode(mode: UiMode) {
        val intent = Intent(ACTION_LAUNCHER_TO_EVENTCENTER_UIMODE).apply {
            setPackage(GATEWAY_PACKAGE)
            putExtra(EXTRA_DAY_NIGHT_UIMODE, mode.code)
        }
        runCatching { appContext.sendBroadcast(intent) }
            .onFailure { Log.w(TAG, "UIMODE send failed", it) }
    }
}
