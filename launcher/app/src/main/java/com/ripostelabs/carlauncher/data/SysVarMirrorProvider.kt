package com.ripostelabs.carlauncher.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.ripostelabs.carlauncher.BuildConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves [SysVarContract] from rows [publish]ed by the owner path (RAV4-98). In-process state:
 * the provider and MainActivity share the launcher's process, and a row only exists once the
 * MCU has reported, so there is nothing worth persisting across a restart.
 *
 * A key with no row answers an empty cursor, which the suite's readers already treat as
 * "unknown"; before the first SYS_EVENT that is the honest answer.
 */
class SysVarMirrorProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val ctx = context ?: return null
        val cursor = MatrixCursor(SysVarContract.COLUMNS)

        // `keyname=?` picks one row; anything else lists them all, so a reader can dump the table.
        val wanted = selectionArgs?.firstOrNull()?.takeIf { selection == SysVarContract.SELECT_BY_KEY }
        for ((key, value) in rows) {
            if (wanted != null && key != wanted) {
                continue
            }
            cursor.addRow(arrayOf(key, value))
        }

        // The uri the client queried, so a debug install notifies its own observers.
        cursor.setNotificationUri(ctx.contentResolver, uri)
        return cursor
    }

    override fun getType(uri: Uri): String =
        "vnd.android.cursor.dir/vnd.${SysVarContract.AUTHORITY}.${SysVarContract.PATH}"

    // Read-only: the rows are the car's state as the MCU reported it, not a setting.

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read-only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only")

    companion object {
        private val rows = ConcurrentHashMap<String, String>()

        /** Store a row and wake every observer of this install's table. */
        fun publish(context: Context, key: String, value: String) {
            rows[key] = value
            val table = SysVarContract.tableUri(SysVarContract.authorityFor(BuildConfig.APPLICATION_ID))
            context.contentResolver.notifyChange(table, null)
        }
    }
}
