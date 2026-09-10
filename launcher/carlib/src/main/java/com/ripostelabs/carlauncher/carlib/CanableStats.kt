package com.ripostelabs.carlauncher.carlib

/**
 * CanableStats — what the adapter has actually said, accumulated for the capture screen.
 *
 * Kept apart from the USB plumbing so "is the link alive, and is the bus wired" can be answered
 * by a desk test rather than only in a car. The three counters answer three different questions:
 *
 *     version  → the USB path works at all (the adapter answered `V`, no CAN wiring needed)
 *     frames   → CAN-H/CAN-L are connected to a live bus
 *     rejected → the adapter refused a command, so the channel is probably not open
 *
 * A screen showing only a frame count cannot tell "unplugged" from "plugged in but not wired",
 * which is the exact ambiguity that wasted an hour on the listen-only bug.
 */
class CanableStats(private val clock: () -> Long = System::currentTimeMillis) {

    /** The adapter's reply to `V`, e.g. `V1013`. Null until it answers. */
    var version: String? = null
        private set

    /**
     * The build string the adapter prints on connect, e.g.
     * `16e7497-dirty github.com/normaldotcom/canable2.git`.
     *
     * Kept apart from [version] rather than folded into it: one is an answer to a question we
     * asked, the other is volunteered at connect time. This firmware sends the banner and does not
     * answer `V`, so without this the adapter identifies itself and the screen still says nothing.
     */
    var banner: String? = null
        private set

    var frames: Long = 0
        private set

    var rejected: Long = 0
        private set

    /** Lines that parsed as neither a frame nor an ack — banners, or corruption. */
    var unparsed: Long = 0
        private set

    /**
     * The ECU's own speed, from the last OBD PID 0x0D reply. Null until the car answers.
     *
     * Kept in stats rather than the vehicle snapshot on purpose: this is a diagnostic reference
     * for calibrating the candidate speed fields, and the rule that no speed reaches the Vehicle
     * tiles until one is calibrated stands. It appears on the capture screen only.
     */
    var obdKmh: Int? = null
        private set

    var obdReplies: Long = 0
        private set

    /** Negative responses to service 01. A steady count here means the ECU will not play. */
    var obdRefusals: Long = 0
        private set

    /** The latest answer for each parameter the car has replied about. */
    private val readings = LinkedHashMap<ObdPid, Double>()

    /** Latest OBD readings, in the order the parameters were first answered. */
    fun obdReadings(): Map<ObdPid, Double> = LinkedHashMap(readings)

    fun recordObd(reply: Obd.Reply) {
        when (reply) {
            is Obd.Reply.Value -> {
                readings[reply.pid] = reply.value
                if (reply.pid == ObdPid.SPEED_KMH) {
                    obdKmh = reply.value.toInt()
                }
                obdReplies++
            }

            is Obd.Reply.Refused -> obdRefusals++
        }
    }

    private val counts = LinkedHashMap<Int, Int>()

    private var windowStart: Long = clock()
    private var windowFrames: Long = 0
    private var lastRate: Int = 0

    fun record(event: SlcanEvent) {
        when (event) {
            is SlcanEvent.Received -> {
                frames++
                windowFrames++
                counts[event.frame.id] = (counts[event.frame.id] ?: 0) + 1
            }

            is SlcanEvent.Rejected -> rejected++

            is SlcanEvent.Text -> {
                unparsed++
                when {
                    event.text.startsWith(VERSION_PREFIX) -> version = event.text

                    // First volunteered line only. Later text is noise, and letting it overwrite
                    // the banner would make the identity flap.
                    banner == null && looksLikeBanner(event.text) -> banner = event.text
                }
            }

            is SlcanEvent.Ack -> Unit
        }

        rollWindow()
    }

    /**
     * Whether a volunteered line is plausibly the adapter identifying itself.
     *
     * A length floor alone is not enough. On 2026-09-09 the car reported
     * `firmware=402AA028800AE` — a mangled frame line, 13 characters of hex, accepted as the
     * adapter's identity. That matters more than it looks: this field is the reading that
     * separates *the USB path works* from *the adapter never answered at all*, so a stray frame
     * landing here makes the diagnostic claim health it has not observed.
     *
     * A frame line is hex digits only. The real banner is
     * `16e7497-dirty github.com/normaldotcom/canable2.git`, which cannot be — it carries spaces,
     * dots and slashes. So requiring one non-hex character separates them without pinning the
     * text of a string the vendor may change.
     */
    private fun looksLikeBanner(text: String): Boolean {
        if (text.length < MIN_BANNER) {
            return false
        }

        return text.any { it !in HEX_DIGITS }
    }

    /** Frames per second over the last completed window. Zero until a full window has elapsed. */
    fun ratePerSec(): Int {
        rollWindow()
        return lastRate
    }

    /**
     * How many distinct ids have been seen. Reported separately from [ids] because callers take
     * only the busiest few to display, and the size of that slice is a property of the screen,
     * not of the bus — the head unit read 1214 frames/s and reported "16 ids" purely because 16
     * was the display cap.
     */
    val distinctIds: Int get() = counts.size

    /** Distinct CAN ids seen, busiest first. The tapped body bus carries 111 of them. */
    fun ids(): List<Pair<Int, Int>> = counts.entries
        .sortedByDescending { it.value }
        .map { it.key to it.value }

    /**
     * Close the counting window once it is full, so a rate reflects a measured second rather than
     * however long the screen happened to be open.
     */
    private fun rollWindow() {
        val now = clock()
        val elapsed = now - windowStart
        if (elapsed < WINDOW_MS) {
            return
        }

        lastRate = (windowFrames * MS_PER_SEC / elapsed).toInt()
        windowStart = now
        windowFrames = 0
    }

    private companion object {
        const val VERSION_PREFIX = "V"

        /** Shorter than this is a stray line, not an identity. The real banner is 51 characters. */
        const val MIN_BANNER = 8

        /** A frame line rendered as text is only these; the banner is not. See [looksLikeBanner]. */
        val HEX_DIGITS = ('0'..'9') + ('a'..'f') + ('A'..'F')
        const val WINDOW_MS = 1_000L
        const val MS_PER_SEC = 1_000L
    }
}
