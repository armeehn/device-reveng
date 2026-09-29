package com.ripostelabs.carlauncher.carlib

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * CarAcc — ACC for [McuSleepWake] and [DozeGuard] on an image without stock's PowerManagerService.
 *
 * Stock learned ACC from `sys.gotoSleep.state`, which its own services.jar wrote on every sleep
 * and wake. The GSI never writes it, so [AndroidAccSource] reads empty and the machine never saw
 * ACC off; DozeGuard then woke the panel the MCU had just darkened (car, 2026-09-28 10:55:04).
 *
 *     SYS_EVENT accLine (every ~4 s while the port is open) ─┐
 *                                                            ├─▶ latest wins ─▶ read()
 *     SCREEN_ON (the MCU's POWER press at ACC on) ───────────┘
 *
 * The port is shut while asleep, so no SYS_EVENT can say ACC is back; the panel waking is the
 * edge stock's property carried. A panel lit by hand while parked reads on until the reopened
 * port reports ACC off again, and the machine sleeps once more.
 */
class CarAcc : McuSleepWake.AccSource, McuOwner.Listener {

    @Volatile
    private var latest: McuSleepWake.Acc? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_ON) {
                return
            }
            onScreenOn()
        }
    }

    override fun read(): McuSleepWake.Acc? = latest

    override fun onSysEvent(event: McuOwnerProtocol.SysEvent) {
        latest = if (event.accLine) McuSleepWake.Acc.ON else McuSleepWake.Acc.OFF
    }

    fun onScreenOn() {
        latest = McuSleepWake.Acc.ON
    }

    fun start(context: Context) {
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    fun stop(context: Context) {
        runCatching { context.unregisterReceiver(receiver) }
    }
}
