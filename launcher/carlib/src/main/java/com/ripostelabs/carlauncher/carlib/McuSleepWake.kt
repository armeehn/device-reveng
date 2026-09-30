package com.ripostelabs.carlauncher.carlib

import android.util.Log

/**
 * McuSleepWake — ACC off and on for [McuOwner], the way the vendor's eventcenter does it.
 *
 * ── Where this sits ─────────────────────────────────────────────────────────────────────────────
 *
 *     sys.gotoSleep.state ──▶ AccSource ──▶ McuSleepWake ──▶ Port (McuOwner.stop / start / send)
 *         (1 s poll)                          │
 *                                             └─ AWAKE ─▶ SLEEPING ─▶ ASLEEP ─▶ WAKING ─▶ AWAKE
 *
 * ACC is not on the MCU wire. The vendor's `AccObserver` polls the system property
 * `sys.gotoSleep.state` once a second (AccObserver.java:19-45): "1" means ACC off, any other
 * non-empty value means on, and an empty read is not an event. Who writes the property is not
 * visible in the decompile; only the vendor's readers are (EventService.java:3412 and here).
 * The writer is stock's own services.jar: PowerManagerService.updateGlobalWakefulnessLocked sets
 * "1" on "Going to sleep" and "0" on "Waking up". The GSI's AOSP copy never writes it, so on
 * Riposte OS the source is [CarAcc] (the MCU's SYS_EVENT line), not [AndroidAccSource].
 *
 * ── ACC off (setAccSleep, EventService.java:3522-3587) ──────────────────────────────────────────
 * No mode byte is sent. The only frame is BT state 0, `0B 00` (:3559 → sendBTState :4336-4342),
 * then message 291 closes the port 1500 ms later (:3561-3562 → :865-869 → closeSerialPort :1694).
 * Sleeping also kills foreground apps (msg 293, :3576-3577); out of scope here.
 *
 * ── POWER key (powerOff, :2702-2727) ─────────────────────────────────────────────────────────────
 * [McuOwner.powerOff] sends the RTC stamp and the SRC_POWEROFF burst. Before that burst the
 * vendor arms message 289 for 6000 ms (:2704-2708, CustomStatusbar.java:21), which runs
 * setAccSleep if the port is still open (:841-845). [powerKey] is that timer: the same BT 0 and
 * close as ACC off, whether or not the property ever changed. The wake after it is an ACC
 * edge: AccObserver fires on a change of the property, never on its level (AccObserver.java:24-35),
 * so [accSeenOff] gates every wake on an ACC off reading first.
 *
 * ── ACC on (setAccWakeUp, EventService.java:3428-3520) ──────────────────────────────────────────
 * Message 292 reopens the port (:3446 → :870-872 → openSerialPort :1683), 100 ms later SRC_NULL
 * goes out with an ACK wait (:3450-3454), and 3000 ms after that ACC_CHANGE_EVENT (:3489 → :465)
 * runs `reloadParam` and re-sends the mode: [McuOwnerProtocol.reload]. [McuOwner.start] covers the
 * reopen and the SRC_NULL handshake, so this class only schedules the reload after it.
 *
 * The vendor ignores ACC off for the 10 polls after ACC on (`mCheckDelay`, AccObserver.java:11,
 * :27-34); [WAKE_GUARD_MS] keeps that.
 *
 * ── Screen ──────────────────────────────────────────────────────────────────────────────────────
 * `onAccGotoSleepState` (:3411-3425) sends KEYCODE_POWER to blank the screen, but nothing posts
 * its runnable (`mRunnableAccSleep`, :447, no other reference). The MCU presses the SoC's POWER
 * line itself about 3 s after ACC off (car, 2026-09-28 10:55:00 → 10:55:03). That press toggles:
 * with the panel already dark it lights it (2026-09-24 16:47:05). [Standby.darken] runs once,
 * [DARKEN_DELAY_MS] after the sleep and past that press, and is a sleep key, never a toggle.
 *
 * ── Standby ─────────────────────────────────────────────────────────────────────────────────────
 * [Standby.enter] is the Android half of setAccSleep (Utils.accOff, :3533; sys.acc.state 0,
 * :437-443), [Standby.leave] the half of setAccWakeUp (AccRev.doAcc(1) → Utils.accOn, :3466).
 *
 * ── Threads ─────────────────────────────────────────────────────────────────────────────────────
 * [poll] is the whole machine and takes the clock as an argument, so tests drive it with a fake
 * clock and a fake [AccSource]. [start] runs it on a daemon thread once a second.
 */
class McuSleepWake(
    private val port: Port,
    private val acc: AccSource,
    private val config: McuOwnerProtocol.StartupConfig = McuOwnerProtocol.StartupConfig(),
    private val clock: () -> Long = { System.nanoTime() / NANOS_PER_MS },
    private val standby: Standby = Standby.NONE,
) {

    enum class Acc { ON, OFF }

    /** The unit around the MCU: radios, the camera gates, the panel. [AccStandby] is the real one. */
    interface Standby {
        fun enter()

        fun leave()

        fun darken()

        companion object {
            val NONE = object : Standby {
                override fun enter() {}

                override fun leave() {}

                override fun darken() {}
            }
        }
    }

    /** Where ACC comes from. `null` means unreadable: hold the current state. */
    interface AccSource {
        fun read(): Acc?
    }

    /** What the machine does to the port. [McuOwner] is the real one; tests substitute their own. */
    interface Port {
        fun open()

        fun close()

        fun send(frame: ByteArray)

        fun lastMode(): McuOwnerProtocol.Mode?
    }

    enum class State { AWAKE, SLEEPING, ASLEEP, WAKING }

    @Volatile
    var state: State = State.AWAKE
        private set

    private var closeAtMs = 0L
    private var reloadAtMs = 0L
    private var guardUntilMs = 0L
    private var darkenAtMs = Long.MAX_VALUE
    private var accSeenOff = false
    private var worker: Thread? = null

    /** Deadline of the vendor's msg 289; [Long.MAX_VALUE] when no POWER key is pending. */
    @Volatile
    private var keySleepAtMs = Long.MAX_VALUE

    @Volatile
    private var running = false

    /** Assumes the owner is already started; the first poll may sleep it if ACC is off. */
    fun start() {
        if (running) {
            return
        }

        running = true
        worker = Thread({ pump() }, "mcu-sleep-wake").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        worker = null
    }

    /** The POWER key was seen: sleep in [POWER_KEY_SLEEP_DELAY_MS] unless ACC does it first. */
    fun powerKey(nowMs: Long) {
        keySleepAtMs = nowMs + POWER_KEY_SLEEP_DELAY_MS
    }

    /** One vendor poll. Timings are deadlines against [nowMs], never sleeps. */
    fun poll(nowMs: Long) {
        val reading = acc.read()
        if (reading == Acc.OFF) {
            accSeenOff = true
        }
        val accOn = reading == Acc.ON && accSeenOff

        when (state) {
            State.AWAKE -> {
                if (nowMs >= keySleepAtMs) {
                    sleep(nowMs, "POWER key")
                    return
                }
                if (reading != Acc.OFF || nowMs < guardUntilMs) {
                    return
                }
                sleep(nowMs, "ACC off")
            }

            State.SLEEPING -> {
                // ACC back before the port closed: the vendor cancels 291 and reopens anyway (:3437-3446).
                if (accOn) {
                    port.close()
                    state = State.ASLEEP
                    wake(nowMs)
                    return
                }
                if (nowMs < closeAtMs) {
                    return
                }
                port.close()
                state = State.ASLEEP
            }

            State.ASLEEP -> {
                if (!accOn) {
                    darkenOnce(nowMs)
                    return
                }
                wake(nowMs)
            }

            State.WAKING -> {
                // setAccSleep drops the pending ACC_CHANGE_EVENT (:3567): the reload never runs.
                if (nowMs >= keySleepAtMs) {
                    sleep(nowMs, "POWER key")
                    return
                }
                if (nowMs < reloadAtMs) {
                    return
                }
                for (frame in McuOwnerProtocol.reload(config, port.lastMode())) {
                    port.send(frame)
                }
                state = State.AWAKE
            }
        }
    }

    /** setAccSleep's wire part: BT state 0 now, the port closes [PORT_CLOSE_DELAY_MS] later. */
    private fun sleep(nowMs: Long, why: String) {
        Log.i(LOG_TAG, "$why: BT state 0, port closes in $PORT_CLOSE_DELAY_MS ms")
        keySleepAtMs = Long.MAX_VALUE
        port.send(McuOwnerProtocol.btState(McuOwnerProtocol.BT_DISCONNECTED))
        closeAtMs = nowMs + PORT_CLOSE_DELAY_MS
        darkenAtMs = nowMs + DARKEN_DELAY_MS
        state = State.SLEEPING
        standby.enter()
    }

    /** The panel goes dark once, after the MCU's own POWER press has come and gone. */
    private fun darkenOnce(nowMs: Long) {
        if (nowMs < darkenAtMs) {
            return
        }

        darkenAtMs = Long.MAX_VALUE
        standby.darken()
    }

    private fun wake(nowMs: Long) {
        Log.i(LOG_TAG, "ACC on: reopen, reload in $RELOAD_DELAY_MS ms")
        accSeenOff = false
        // The MCU's POWER key after ACC off must not sleep the next drive (car, 2026-09-28 10:55:10).
        keySleepAtMs = Long.MAX_VALUE
        darkenAtMs = Long.MAX_VALUE
        port.open()
        standby.leave()
        reloadAtMs = nowMs + RELOAD_DELAY_MS
        guardUntilMs = nowMs + WAKE_GUARD_MS
        state = State.WAKING
    }

    private fun pump() {
        while (running) {
            try {
                poll(clock())
            } catch (e: Exception) {
                Log.w(LOG_TAG, "poll failed in $state: ${e.message}")
            }
            Thread.sleep(POLL_MS)
        }
    }

    /**
     * Feeds [powerKey] from the owner's key stream. It sits in the owner's [McuOwner.FanOut], which
     * is built before the machine exists, so it looks the machine up on each key.
     */
    class PowerKeyListener(private val machine: () -> McuSleepWake?) : McuOwner.Listener {
        override fun onPanelKey(key: McuOwnerProtocol.PanelKey) {
            if (key.code != McuOwnerProtocol.Key.POWER) {
                return
            }
            machine()?.let { it.powerKey(it.clock()) }
        }
    }

    /** [Port] over the owner: stop/start are the vendor's close/open (msgs 291/292). */
    private class OwnerPort(private val owner: McuPort) : Port {
        override fun open() = owner.start()

        override fun close() = owner.stop()

        override fun send(frame: ByteArray) = owner.send(frame)

        override fun lastMode() = owner.lastMode
    }

    companion object {
        private const val LOG_TAG = "McuSleepWake"
        private const val NANOS_PER_MS = 1_000_000L

        /** AccObserver.java:39: one read of the property per second. */
        const val POLL_MS = 1_000L

        /** `mCheckDelay = 10` polls after ACC on during which ACC off is ignored (AccObserver.java:27-34). */
        const val WAKE_GUARD_MS = 10 * POLL_MS

        /** setAccSleep → msg 291 → closeSerialPort (EventService.java:3562). */
        const val PORT_CLOSE_DELAY_MS = 1_500L

        /** setAccWakeUp → ACC_CHANGE_EVENT → reloadParam (EventService.java:3489). */
        const val RELOAD_DELAY_MS = 3_000L

        /** powerOff → msg 289 → setAccSleep (EventService.java:2704-2708, CustomStatusbar.java:21). */
        const val POWER_KEY_SLEEP_DELAY_MS = 6_000L

        /** Past the MCU's POWER press, 2.9-3.1 s after ACC off on the car, with margin. */
        const val DARKEN_DELAY_MS = 10_000L

        fun forOwner(
            owner: McuPort,
            acc: AccSource,
            config: McuOwnerProtocol.StartupConfig = McuOwnerProtocol.StartupConfig(),
            standby: Standby = Standby.NONE,
        ): McuSleepWake = McuSleepWake(OwnerPort(owner), acc, config, standby = standby)
    }
}
