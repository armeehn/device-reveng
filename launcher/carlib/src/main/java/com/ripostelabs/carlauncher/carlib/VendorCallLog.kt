package com.ripostelabs.carlauncher.carlib

import android.content.Context
import android.database.Cursor
import android.net.Uri

/**
 * The vendor bt module's call history, read from its content provider.
 *
 * `com.szchoiceway.btsuite.CallListProvider` is exported with no `android:permission`
 * (`btsuite/AndroidManifest.xml:89-93`), so any uid can query it. It answers only
 * `content://<authority>/query` and takes the call type as `projection[0]`
 * (`CallListProvider.java:query`); everything else returns null. Rows come from the
 * `calllist` table, newest first, at most 150 for [CallType.ALL] / 50 per type
 * (`CallRecManager.java:142-161`).
 *
 * ```
 *  column     meaning                          example
 *  _id        row id
 *  name       contact name, may be blank        "Alice"
 *  num        number as the module gave it      "+16041234567"
 *  date       "%d-%02d-%02d"                    "2026-09-02"
 *  time       "%02d:%02d:%02d"                  "14:05:09"
 *  calltype   2 received / 3 dialed / 4 missed
 *  timeDetail sort key, digits concatenated
 * ```
 * (`CallRecManager.java:41-48,68`, columns read at `:129-135`.)
 *
 * ⚠ UNVERIFIED on the car: the manifest says readable, but a query from a normal uid has not
 * been observed to return rows. [read] returns an empty list on ANY failure, so a screen must
 * offer the vendor call-record page as the fallback rather than trust an empty answer.
 */
object VendorCallLog {

    const val AUTHORITY = "com.szchoiceway.btsuite.CallListProvider"
    const val QUERY_URI = "content://$AUTHORITY/query"

    const val COL_NAME = "name"
    const val COL_NUMBER = "num"
    const val COL_DATE = "date"
    const val COL_TIME = "time"
    const val COL_TYPE = "calltype"

    /** `projection[0]` values (`CallRecManager.java:150-153,261`). */
    enum class CallType(val code: Int) {
        RECEIVED(2),
        DIALED(3),
        MISSED(4),
        ALL(5);

        companion object {
            fun of(code: Int?): CallType? = entries.firstOrNull { it.code == code }
        }
    }

    data class Entry(
        val name: String?,
        val number: String,
        val date: String,
        val time: String,
        val type: CallType?,
    ) {
        /** Name when the phone book had one, else the bare number. */
        val label: String get() = name?.takeIf { it.isNotBlank() } ?: number
    }

    /** One row's columns as the cursor read them; a fake in tests, a [Cursor] in [read]. */
    fun interface Row {
        fun string(column: String): String?
    }

    /**
     * Map one row. A row without a number is useless (nothing to dial or show) and is dropped;
     * [CallType.ALL] is a query selector, never a stored type, so it maps to null.
     */
    fun entry(row: Row): Entry? {
        val number = row.string(COL_NUMBER)?.takeIf { it.isNotBlank() } ?: return null
        val type = CallType.of(row.string(COL_TYPE)?.toIntOrNull())?.takeIf { it != CallType.ALL }
        return Entry(
            name = row.string(COL_NAME),
            number = number,
            date = row.string(COL_DATE).orEmpty(),
            time = row.string(COL_TIME).orEmpty(),
            type = type,
        )
    }

    /** Query the provider; call off the main thread. Empty on any failure (see class doc). */
    fun read(context: Context, type: CallType = CallType.ALL): List<Entry> = runCatching {
        val projection = arrayOf(type.code.toString())
        context.contentResolver
            .query(Uri.parse(QUERY_URI), projection, null, null, null)
            ?.use { cursor -> entries(cursor) }
            ?: emptyList()
    }.getOrDefault(emptyList())

    private fun entries(cursor: Cursor): List<Entry> {
        val row = Row { column ->
            val index = cursor.getColumnIndex(column)
            if (index < 0) null else cursor.getString(index)
        }
        val out = ArrayList<Entry>(cursor.count)
        while (cursor.moveToNext()) {
            out += entry(row) ?: continue
        }
        return out
    }
}
