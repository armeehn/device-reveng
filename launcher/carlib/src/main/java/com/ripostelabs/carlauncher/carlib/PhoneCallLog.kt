package com.ripostelabs.carlauncher.carlib

import android.content.Context
import android.provider.CallLog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * RAV4-162 — the phone's call history on Riposte OS 0.2, where btsuite (and [VendorCallLog]) is gone.
 *
 * The stack's PBAP client pulls the phone's all / missed / dialled lists into the ordinary
 * `CallLog.Calls` provider when the phone connects (`PbapClientService`, `CallLogPullRequest`).
 * Rows map onto [VendorCallLog.Entry] so the Phone screen draws one list whichever slot fed it.
 *
 * ```
 *  CallLog.Calls.TYPE        entry type
 *  1 incoming                RECEIVED
 *  2 outgoing                DIALED
 *  3 missed, 5 rejected      MISSED     (both are calls to return)
 *  7 answered elsewhere      RECEIVED
 *  4 voicemail, 6 blocked    null       (listed under All only)
 * ```
 *
 * Needs READ_CALL_LOG (the 0.2 image grants it at first boot); without it [read] is empty.
 */
object PhoneCallLog {

    /** The newest rows only: the screen is a recent-calls list, not an archive. */
    private const val LIMIT = 150

    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** Map one row; null when it carries no number to show or dial. */
    fun entry(name: String?, number: String?, dateMs: Long, type: Int?, zone: ZoneId): VendorCallLog.Entry? {
        val dialable = number?.takeIf { it.isNotBlank() } ?: return null
        val at = Instant.ofEpochMilli(dateMs).atZone(zone)
        return VendorCallLog.Entry(
            name = name,
            number = dialable,
            date = DATE.format(at),
            time = TIME.format(at),
            type = type(type),
        )
    }

    private fun type(code: Int?): VendorCallLog.CallType? = when (code) {
        CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> VendorCallLog.CallType.RECEIVED
        CallLog.Calls.OUTGOING_TYPE -> VendorCallLog.CallType.DIALED
        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> VendorCallLog.CallType.MISSED
        else -> null
    }

    /** Query the provider, newest first; call off the main thread. Empty on any failure. */
    fun read(context: Context): List<VendorCallLog.Entry> = runCatching {
        val columns = arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.TYPE)
        val zone = ZoneId.systemDefault()
        context.contentResolver
            .query(CallLog.Calls.CONTENT_URI, columns, null, null, "${CallLog.Calls.DATE} DESC")
            ?.use { c ->
                val out = ArrayList<VendorCallLog.Entry>()
                while (c.moveToNext() && out.size < LIMIT) {
                    out += entry(c.getString(0), c.getString(1), c.getLong(2), c.getInt(3), zone) ?: continue
                }
                out
            }
            ?: emptyList()
    }.getOrDefault(emptyList())
}
