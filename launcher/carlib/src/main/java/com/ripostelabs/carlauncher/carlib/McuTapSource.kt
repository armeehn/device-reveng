package com.ripostelabs.carlauncher.carlib

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * McuTapSource — listen to the wire between the car's decoder box and this head unit.
 *
 * ── Why a wire tap and not the port ─────────────────────────────────────────────────────────────
 * The vendor's own process owns `/dev/ttyHS1`, and a second reader on that port does not share the
 * stream, it splits it: a six-second read once stole 682 bytes the vendor stack never received.
 * That hazard belongs to opening the port, not to the wire. A separate adapter clipped onto the
 * box's transmit line takes nothing away from anybody.
 *
 * ── Why it is worth the trouble ─────────────────────────────────────────────────────────────────
 * The box decodes both of the car's buses and hands Android the result. Several things reach the
 * head unit only this way and appear nowhere on the raw CAN bus: the climate settings, the
 * selected fan step, and the turn indicators — three separate hunts for indicators on CAN found
 * nothing, because they were never there. Listening here gets all of it without guessing.
 *
 * ── It never transmits ──────────────────────────────────────────────────────────────────────────
 * The session is opened in [CanableUsbLink.SessionMode.LISTEN], which writes nothing at startup
 * and nothing at close. This is not tidiness. The same slcan commands that open a CAN adapter
 * would, on this link, be bytes injected into a live conversation between the head unit and the
 * car, and an adapter wired to the box's transmit line has no business speaking at all.
 *
 * ── Untested against hardware ───────────────────────────────────────────────────────────────────
 * No adapter has ever been wired to this link. The framing, the checksum and the opcode decoding
 * are all covered by tests and by the vendor's own captures, but nothing here has seen a real
 * byte from the box. The status this publishes is written so the first attempt says which part
 * failed rather than just staying quiet.
 */
class McuTapSource private constructor(
    private val link: CanableUsbLink,
    private val context: Context,
    private val vehicle: VehicleState,
) {

    /** What the tap is doing, in the language of what it proves. */
    sealed class Status {
        object Idle : Status()

        /** No second serial adapter is attached, or it presents no CDC-ACM interface. */
        object NoAdapter : Status()

        object NoPermission : Status()

        data class Failed(val reason: String) : Status()

        /**
         * Reading. [frames] counts well-formed MCU messages, [badChecksum] ones that framed but
         * failed their own checksum, and [skipped] bytes discarded while hunting for a header.
         *
         * Three counters because the failures are different problems. Bytes arriving with nothing
         * framing is a wrong baud rate or the wrong wire. Frames arriving with bad checksums is
         * the right wire and a wrong assumption about the protocol. Neither looks like the other.
         */
        data class Running(
            val frames: Long,
            val badChecksum: Long,
            val skipped: Long,
            val opcodes: List<Pair<Int, Int>>,
        ) : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    private var worker: Thread? = null

    @Volatile
    private var running = false

    fun start() {
        if (running) {
            return
        }

        running = true
        worker = Thread({ pump() }, "mcu-tap").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        worker = null
        _status.value = Status.Idle
    }

    /** Ask for access to whatever serial adapter is attached, if one is. */
    fun requestAccess() {
        val device = link.findSerialListener() ?: return
        link.requestPermission(context, device)
    }

    private fun pump() {
        while (running) {
            val device = link.findSerialListener()
            if (device == null) {
                _status.value = Status.NoAdapter
                Thread.sleep(RETRY_MS)
                continue
            }

            if (!link.hasPermission(device)) {
                _status.value = Status.NoPermission
                Thread.sleep(RETRY_MS)
                continue
            }

            val session = link.open(device, SlcanBitrate.KBIT_500, CanableUsbLink.SessionMode.LISTEN)
            if (session == null) {
                _status.value = Status.Failed("claim or open refused")
                Thread.sleep(RETRY_MS)
                continue
            }

            try {
                read(session)
            } finally {
                session.close()
            }
        }
    }

    private fun read(session: CanableUsbLink.Session) {
        val reader = McuSerial.Reader()
        val counts = LinkedHashMap<Int, Int>()
        var frames = 0L
        var bad = 0L
        var skipped = 0L
        var published = 0L

        while (running) {
            // poll() returns slcan events, which this link does not carry, so the bytes are taken
            // from the transfer itself. Everything below is the MCU framing, not slcan.
            val bytes = session.readRaw() ?: break

            for (event in reader.feed(bytes, bytes.size)) {
                when (event) {
                    is McuSerial.Command -> {
                        frames++
                        counts[event.opcode] = (counts[event.opcode] ?: 0) + 1
                        val signal = HiworldCanDecoder.decodePayload(event.opcode, event.payload)
                        vehicle.onSignal(signal, System.currentTimeMillis())
                    }

                    is McuSerial.BadChecksum -> bad++
                    is McuSerial.Skipped -> skipped += event.bytes
                }
            }

            val now = System.currentTimeMillis()
            if (now - published < PUBLISH_MS) {
                continue
            }

            published = now
            _status.value = Status.Running(
                frames = frames,
                badChecksum = bad,
                skipped = skipped,
                opcodes = counts.entries.sortedByDescending { it.value }.map { it.key to it.value },
            )
            Log.i(LOG_TAG, "mcu-tap frames=$frames badCk=$bad skipped=$skipped opcodes=${counts.size}")
        }
    }

    companion object {
        private const val LOG_TAG = "Canable"
        private const val RETRY_MS = 2_000L
        private const val PUBLISH_MS = 1_000L

        fun create(context: Context, vehicle: VehicleState): McuTapSource {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            return McuTapSource(CanableUsbLink(manager), context.applicationContext, vehicle)
        }
    }
}
