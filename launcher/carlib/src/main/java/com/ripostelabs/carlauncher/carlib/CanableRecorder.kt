package com.ripostelabs.carlauncher.carlib

/**
 * CanableRecorder — writes the bus to a file in candump log format, for offline analysis.
 *
 * The format is the one `candump -l` produces, so the result loads into every existing CAN tool
 * and replays with `canplayer`. Inventing a format here would have meant writing a parser for it
 * later, and the analysis is the point of the capture.
 *
 *     (1757356789.123000) can0 4A5#0400000080000000
 *      └ seconds.micros    └ iface └ id  └ payload
 *
 * The tapped bus runs ~1215 frames/s, roughly 3 MB a minute, so the cap is not decoration: an
 * unattended capture on a drive would otherwise fill the device. Reaching the cap stops writing
 * and counts what was dropped rather than truncating silently, because a capture that quietly
 * ends early looks exactly like a bus that went quiet.
 */
class CanableRecorder(
    private val sink: Appendable,
    private val maxBytes: Long = MAX_BYTES,
) {

    var bytesWritten: Long = 0L
        private set

    /** Frames not written because the cap was reached. Zero on every healthy capture. */
    var dropped: Long = 0L
        private set

    val isFull: Boolean get() = bytesWritten >= maxBytes

    fun record(frame: SlcanFrame, atMs: Long) {
        if (isFull) {
            dropped++
            return
        }

        val line = format(frame, atMs)
        sink.append(line)
        bytesWritten += line.length
    }

    /** One candump line, newline included. */
    private fun format(frame: SlcanFrame, atMs: Long): String {
        val seconds = atMs / MS_PER_SEC
        val micros = (atMs % MS_PER_SEC) * MICROS_PER_MS

        return "(%d.%06d) %s %s#%s\n".format(seconds, micros, IFACE, idOf(frame), payloadOf(frame))
    }

    private fun idOf(frame: SlcanFrame): String {
        val width = if (frame.format == IdFormat.EXTENDED) EXT_DIGITS else STD_DIGITS
        return frame.id.toString(HEX).uppercase().padStart(width, '0')
    }

    private fun payloadOf(frame: SlcanFrame): String =
        frame.data.joinToString("") { (it and 0xFF).toString(HEX).uppercase().padStart(2, '0') }

    companion object {
        /** Name the tools expect. There is no real SocketCAN interface behind it on this device. */
        private const val IFACE = "can0"

        private const val HEX = 16
        private const val STD_DIGITS = 3
        private const val EXT_DIGITS = 8
        private const val MS_PER_SEC = 1_000L
        private const val MICROS_PER_MS = 1_000L

        /** ~20 minutes of this bus. Large enough for a drive, small enough not to fill /sdcard. */
        const val MAX_BYTES = 64L * 1024 * 1024
    }
}
