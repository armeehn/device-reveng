package com.ripostelabs.carlauncher.carlib

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

/**
 * DozeGuard — keeps the panel interactive on the owner path.
 *
 * The GSI (TrebleDroid AOSP 14, ci-20240226) ships doze and dreams on; stock had neither. Car,
 * 2026-09-23 (diag-20260923-061514.log), 2 s after the launcher started, with ACC on and nothing
 * from [McuSleepWake]:
 *
 *     DreamManagerService: Entering dreamland.
 *     DreamController: Starting dream: name=...com.android.systemui.doze.DozeService
 *     PowerManagerService: Dozing...            (no "Going to sleep due to" line)
 *
 * The panel stayed black, backlit and deaf to taps until a key woke it. riposte-firstboot.sh
 * turns the settings off; this is the belt for the same fault at run time.
 *
 *     SCREEN_OFF ─┐
 *                 ├─▶ decide(McuSleepWake.state, ACC) ─▶ WAKE ─▶ `input keyevent KEYCODE_WAKEUP`
 *     DREAMING_STARTED ─┘                                 └─▶ LEAVE   (root shell)
 *
 * It never fights [McuSleepWake]: SLEEPING or ASLEEP means ACC off or the POWER key asked for
 * the sleep, and an ACC off reading says the same before the 1 s poll has moved the machine.
 */
class DozeGuard(
    private val machine: () -> McuSleepWake?,
    private val acc: McuSleepWake.AccSource,
    private val wake: () -> RootShell.Result = { RootShell.exec(WAKE_CMD) },
) {

    enum class Trigger(val action: String) {
        SCREEN_OFF(Intent.ACTION_SCREEN_OFF),
        DREAM_STARTED(Intent.ACTION_DREAMING_STARTED),
    }

    enum class Action { WAKE, LEAVE }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val trigger = Trigger.entries.firstOrNull { it.action == intent.action } ?: return
            onTrigger(trigger)
        }
    }

    fun start(context: Context) {
        val filter = IntentFilter().apply {
            Trigger.entries.forEach { addAction(it.action) }
        }
        context.registerReceiver(receiver, filter)
    }

    fun stop(context: Context) {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun onTrigger(trigger: Trigger) {
        val state = machine()?.state
        val reading = acc.read()
        if (decide(state, reading) == Action.LEAVE) {
            Log.i(LOG_TAG, "${trigger.action} left alone (state=$state acc=$reading)")
            return
        }

        // The receiver runs on the main thread; the root shell must not.
        Thread({
            val r = wake()
            Log.i(LOG_TAG, "woke the panel (dream=${trigger.action}, exit=${r.code})")
        }, "doze-guard").start()
    }

    companion object {
        private const val LOG_TAG = "DozeGuard"

        /** PowerManager.wakeUp with WAKE_REASON_WAKE_KEY: leaves a doze the way a fascia key does. */
        const val WAKE_CMD = "input keyevent KEYCODE_WAKEUP"

        /**
         * The whole decision, with no Android in it. `null` state means no owner machine
         * (a stock-derived build): the vendor's eventcenter owns the screen there.
         */
        fun decide(state: McuSleepWake.State?, acc: McuSleepWake.Acc?): Action {
            if (state == null) {
                return Action.LEAVE
            }
            if (state == McuSleepWake.State.SLEEPING || state == McuSleepWake.State.ASLEEP) {
                return Action.LEAVE
            }
            if (acc == McuSleepWake.Acc.OFF) {
                return Action.LEAVE
            }

            return Action.WAKE
        }
    }
}
