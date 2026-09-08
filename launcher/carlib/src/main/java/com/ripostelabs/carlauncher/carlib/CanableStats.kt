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

    var frames: Long = 0
        private set

    var rejected: Long = 0
        private set

    /** Lines that parsed as neither a frame nor an ack — banners, or corruption. */
    var unparsed: Long = 0
        private set

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
                if (event.text.startsWith(VERSION_PREFIX)) {
                    version = event.text
                }
            }

            is SlcanEvent.Ack -> Unit
        }

        rollWindow()
    }

    /** Frames per second over the last completed window. Zero until a full window has elapsed. */
    fun ratePerSec(): Int {
        rollWindow()
        return lastRate
    }

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
        const val WINDOW_MS = 1_000L
        const val MS_PER_SEC = 1_000L
    }
}
