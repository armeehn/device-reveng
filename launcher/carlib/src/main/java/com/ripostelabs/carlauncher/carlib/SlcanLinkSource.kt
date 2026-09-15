package com.ripostelabs.carlauncher.carlib

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SlcanLinkSource — the raw body bus over any [McuLink], in slcan text.
 *
 * ── Where this sits ─────────────────────────────────────────────────────────────────────────────
 *
 *     carsim / bench adapter ──▶ McuLink ──▶ SlcanReader ──▶ SlcanFrame ──▶ VehicleState.onRawFrame
 *                                                                              └─▶ RawCanDecoder ──▶ speed, gear, doors
 *
 * [CanableSource] is the same stream off a USB CDC-ACM adapter; this is the same stream off a
 * character device or socket, which is what the emulator farm can offer (no USB there) and what
 * a Riposte-designed head unit would offer if its CAN transceiver hangs off a UART. The parked-only
 * safety gate reads speed from this path only (`CarEvents.CAN_SPEED_TRUSTED` is false for the
 * MCU's digest), so a desk drive must come in here to move that gate.
 *
 * No `O`/`S` commands are sent, because there is no adapter to configure. A line that is not a
 * frame is counted, never dropped silently.
 *
 * ── OBD ─────────────────────────────────────────────────────────────────────────────────────────
 * The same standard queries [CanableSource] puts on the USB adapter go out here too, so the
 * ECU's coolant, load and throttle reach the Vehicle page whichever carrier the bus arrives on.
 * Requests are paced by [ObdPoller] and only leave while frames are arriving: a silent bus is a
 * car that is off, and asking it questions would be noise on the desk rig's log.
 */
class SlcanLinkSource(
    private val openLink: () -> McuLink,
    private val vehicle: VehicleState,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    sealed class Status {
        object Idle : Status()

        data class Failed(val reason: String) : Status()

        data class Running(val frames: Long, val unparsed: Long) : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var link: McuLink? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) {
            return
        }

        val opened = try {
            openLink()
        } catch (e: Exception) {
            _status.value = Status.Failed(e.message ?: e.javaClass.simpleName)
            return
        }

        link = opened
        running = true
        _status.value = Status.Running(frames = 0, unparsed = 0)
        Thread({ pump(opened) }, THREAD_NAME).apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        link?.close()
        link = null
        _status.value = Status.Idle
    }

    private fun pump(l: McuLink) {
        val reader = SlcanReader()
        val buffer = ByteArray(READ_BUFFER)
        var frames = 0L
        var unparsed = 0L

        // One bounded-rate question at a time, the PIDs taking turns. See ObdRotation.
        val poller = ObdPoller()
        val rotation = ObdRotation()

        while (running) {
            val n = try {
                l.read(buffer)
            } catch (e: Exception) {
                -1
            }
            if (n < 0) {
                if (running) {
                    _status.value = Status.Failed("link closed")
                    Log.w(LOG_TAG, "bus link closed after $frames frames")
                }
                running = false
                return
            }

            for (event in reader.feed(buffer, n)) {
                when (event) {
                    is SlcanEvent.Received -> {
                        frames++
                        vehicle.onRawFrame(event.frame, clock())
                        Obd.parse(event.frame)?.let { vehicle.onObd(it, clock()) }
                    }

                    SlcanEvent.Ack, SlcanEvent.Rejected -> Unit
                    is SlcanEvent.Text -> unparsed++
                }
            }
            _status.value = Status.Running(frames = frames, unparsed = unparsed)

            // Reached only after a read returned, so the bus is alive; the poller keeps it to 2 Hz.
            if (poller.shouldSend(clock())) {
                send(l, Obd.request(rotation.next()))
            }
        }
    }

    /** A write failure is not a link failure: the next read decides that. */
    private fun send(l: McuLink, frame: SlcanFrame) {
        try {
            l.write(SlcanCodec.transmit(frame))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "obd request not sent: ${e.message}")
        }
    }

    companion object {
        private const val LOG_TAG = "SlcanLinkSource"
        private const val THREAD_NAME = "slcan-link"
        private const val READ_BUFFER = 512
    }
}
