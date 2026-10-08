package com.ripostelabs.carlauncher.carlib

/**
 * UpdateEngine: the root shell lines that drive Android's update_engine, and the reading of what
 * it prints. update_engine writes only the other slot (dm-snapshots, COW in /data), so nothing
 * here can touch the running system. Its client cannot use the SOCKS uplink, so the launcher
 * downloads the payload and hands it a file (share carlauncher/os-ota.md, section 4).
 *
 *     apply   restorecon, then --update with the file, its size and the four headers
 *     status  --follow prints one line per change; the first comes at once:
 *               onStatusUpdate(UPDATE_STATUS_DOWNLOADING (3), 0.5)
 *             and an apply that ends prints
 *               onPayloadApplicationComplete(ErrorCode::kSuccess (0))
 *     suspend / resume / cancel
 *
 * The status line format is AOSP update_engine_client_android.cc; not yet seen on the unit.
 */
object UpdateEngine {

    /** update_engine's UpdateStatus, by number (update_engine/client_library/include/update_engine/update_status.h). */
    enum class Status(val code: Int) {
        IDLE(0),
        CHECKING_FOR_UPDATE(1),
        UPDATE_AVAILABLE(2),
        DOWNLOADING(3),
        VERIFYING(4),
        FINALIZING(5),
        UPDATED_NEED_REBOOT(6),
        REPORTING_ERROR_EVENT(7),
        ATTEMPTING_ROLLBACK(8),
        DISABLED(9),
        NEED_PERMISSION_TO_UPDATE(10),
        CLEANUP_PREVIOUS_UPDATE(11),
        UNKNOWN(-1),
    }

    data class Progress(val status: Status, val fraction: Float)

    /** Where the launcher leaves the payload: update_engine may read files labelled ota_package_file. */
    const val PACKAGE_DIR = "/data/ota_package"

    /** Reads the current status and returns: --follow never ends while an apply runs. */
    const val STATUS = "timeout 3 update_engine_client --follow"
    const val SUSPEND = "update_engine_client --suspend"
    const val RESUME = "update_engine_client --resume"
    const val CANCEL = "update_engine_client --cancel"

    private val HEADER_KEYS = listOf("FILE_HASH", "FILE_SIZE", "METADATA_HASH", "METADATA_SIZE")
    private val HEADER_VALUE = Regex("^[A-Za-z0-9+/=]+$")
    private val STATUS_LINE = Regex("""onStatusUpdate\(\S+ \((\d+)\), ([0-9.]+)\)""")
    private val ERROR_LINE = Regex("""onPayloadApplicationComplete\(\S+ \((\d+)\)\)""")

    /**
     * The four headers in mkpayload's order, or null when the text is not exactly them. Values are
     * base64 or digits only: they go into a root shell line.
     */
    private fun headerPairs(text: String): List<Pair<String, String>>? {
        val pairs = text.lines().filter { it.isNotBlank() }.map { line ->
            val eq = line.indexOf('=')
            if (eq <= 0) {
                return null
            }
            line.substring(0, eq) to line.substring(eq + 1)
        }
        if (pairs.map { it.first } != HEADER_KEYS) {
            return null
        }
        if (pairs.any { !HEADER_VALUE.matches(it.second) }) {
            return null
        }
        return pairs
    }

    /** FILE_SIZE from valid headers, else null. The manifest row's size must equal it. */
    fun headerSize(text: String): Long? = headerPairs(text)?.first { it.first == "FILE_SIZE" }?.second?.toLongOrNull()

    /** The root shell line that starts an apply of [file], or null when [headers] are not safe to pass. */
    fun apply(file: String, size: Long, headers: String): String? {
        val pairs = headerPairs(headers) ?: return null
        val joined = pairs.joinToString("\n") { "${it.first}=${it.second}" }
        return "restorecon -R $PACKAGE_DIR; update_engine_client --update " +
            "--payload=file://$file --offset=0 --size=$size --headers='$joined'"
    }

    /** The last status line in [out], or null when there is none. */
    fun status(out: String): Progress? {
        val m = STATUS_LINE.findAll(out).lastOrNull() ?: return null
        val code = m.groupValues[1].toInt()
        val status = Status.entries.firstOrNull { it.code == code } ?: Status.UNKNOWN
        return Progress(status, m.groupValues[2].toFloatOrNull() ?: 0f)
    }

    /** The ErrorCode of a finished apply (0 is success), or null when [out] has none. */
    fun error(out: String): Int? = ERROR_LINE.findAll(out).lastOrNull()?.groupValues?.get(1)?.toInt()
}
