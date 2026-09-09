package com.ripostelabs.carlauncher.carlib

/**
 * CaptureRotation — how an unattended capture stays bounded without ever stopping.
 *
 * A drive is open-ended. The old rule, "write until the cap then stop", loses the end of every
 * long drive, and the end is usually the interesting part: it is where the thing you drove out to
 * reproduce actually happened.
 *
 * So the capture rolls instead. Files are bounded in size, the set is bounded in count, and the
 * oldest is dropped to make room. What survives is always the most recent window rather than the
 * first few minutes of an eight-hour ignition cycle.
 *
 *     can-0.log  can-1.log  can-2.log  can-3.log      maxFiles = 4
 *      oldest ─────────────────────────► newest
 *        └ deleted when can-4.log opens
 *
 * At ~1215 frames/s this bus writes roughly 3 MB a minute, so the defaults hold about half an
 * hour. Pure and injectable so both bounds are checked at a desk rather than after a drive.
 */
class CaptureRotation(
    private val maxBytesPerFile: Long = MAX_BYTES_PER_FILE,
    private val maxFiles: Int = MAX_FILES,
) {

    private var index = 0

    /** Name of the file currently being written. */
    fun currentName(): String = nameFor(index)

    /** Whether [bytes] in the current file means it is time to open the next one. */
    fun shouldRoll(bytes: Long): Boolean = bytes >= maxBytesPerFile

    /**
     * Advance to the next file. Returns the name of the file that must now be deleted to stay
     * within [maxFiles], or null while the set is still filling up.
     */
    fun roll(): String? {
        index++

        val oldest = index - maxFiles
        return if (oldest < 0) null else nameFor(oldest)
    }

    private fun nameFor(i: Int): String = "$PREFIX$i$SUFFIX"

    companion object {
        private const val PREFIX = "can-"
        private const val SUFFIX = ".log"
        /** About 5 minutes of this bus: small enough to pull mid-drive over Tailscale. */
        const val MAX_BYTES_PER_FILE = 16L * 1024 * 1024

        /** Six files, so roughly the last half hour survives regardless of drive length. */
        const val MAX_FILES = 6
    }
}
