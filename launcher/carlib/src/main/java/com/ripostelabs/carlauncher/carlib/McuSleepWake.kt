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
 *
 * ── ACC off (setAccSleep, EventService.java:3522-3587) ──────────────────────────────────────────
 * No mode byte is sent. The only frame is BT state 0, `0B 00` (:3559 → sendBTState :4336-4342),
 * then message 291 closes the port 1500 ms later (:3561-3562 → :865-869 → closeSerialPort :1694).
 * The RTC + SRC_POWEROFF burst is the POWER key path only (`powerOff`, :2702-2727); it arms
 * message 289 which reaches setAccSleep 6000 ms later (CustomStatusbar.java:21) and is not part of
 * ACC off. Sleeping also kills foreground apps (msg 293, :3576-3577); out of scope here.
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
 * its runnable (`mRunnableAccSleep`, :447, no other reference), so the screen is left alone here.
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
) {

    enum class Acc { ON, OFF }

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
    private var worker: Thread? = null

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

    /** One vendor poll. Timings are deadlines against [nowMs], never sleeps. */
    fun poll(nowMs: Long) {
        val reading = acc.read()

        when (state) {
            State.AWAKE -> {
                if (reading != Acc.OFF || nowMs < guardUntilMs) {
                    return
                }
                Log.i(LOG_TAG, "ACC off: BT state 0, port closes in $PORT_CLOSE_DELAY_MS ms")
                port.send(McuOwnerProtocol.btState(McuOwnerProtocol.BT_DISCONNECTED))
                closeAtMs = nowMs + PORT_CLOSE_DELAY_MS
                state = State.SLEEPING
            }

            State.SLEEPING -> {
                // ACC back before the port closed: the vendor cancels 291 and reopens anyway (:3437-3446).
                if (reading == Acc.ON) {
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
                if (reading != Acc.ON) {
                    return
                }
                wake(nowMs)
            }

            State.WAKING -> {
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

    private fun wake(nowMs: Long) {
        Log.i(LOG_TAG, "ACC on: reopen, reload in $RELOAD_DELAY_MS ms")
        port.open()
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

    /** [Port] over the owner: stop/start are the vendor's close/open (msgs 291/292). */
    private class OwnerPort(private val owner: McuOwner) : Port {
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

        fun forOwner(
            owner: McuOwner,
            acc: AccSource,
            config: McuOwnerProtocol.StartupConfig = McuOwnerProtocol.StartupConfig(),
        ): McuSleepWake = McuSleepWake(OwnerPort(owner), acc, config)
    }
}
