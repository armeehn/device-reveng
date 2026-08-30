package com.ripostelabs.carlauncher.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * v0.4.7 - dump the whole live SysVar table to a JSON file for offline analysis.
 *
 * The Advanced browser shows every vendor key on-screen, but reverse-engineering them is a desk
 * job: a snapshot pulled to a computer can be diffed across vehicle states (engine on/off, reverse
 * engaged, radio tuned) to find which keys move. This writes the [CarSettingsController] snapshot
 * as pretty-printed JSON into the external files dir - no runtime permission on API 33, reachable
 * over adb, the same directory rationale as [ThemeTransfer].
 *
 *     adb pull /sdcard/Android/data/<applicationId>/files/sysvar-dumps/
 */
object SysVarExport {

    private const val TAG = "SysVarExport"
    private const val DIR = "sysvar-dumps"
    private const val PREFIX = "sysvar-"
    private const val EXT = ".json"
    private const val JSON_INDENT = 2

    /** External files/sysvar-dumps, created on demand. Null if external storage is unavailable. */
    fun directory(context: Context): File? {
        val base = context.applicationContext.getExternalFilesDir(null) ?: return null
        val dir = File(base, DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "could not create " + dir.absolutePath)
            return null
        }
        return dir
    }

    /** Write [snapshot] as one sorted JSON object into a timestamped file. Null if empty/failed. */
    fun export(context: Context, snapshot: Map<String, String>, nowMillis: Long): File? {
        if (snapshot.isEmpty()) return null
        val dir = directory(context) ?: return null
        val obj = JSONObject()
        snapshot.toSortedMap().forEach { entry -> obj.put(entry.key, entry.value) }
        val file = File(dir, PREFIX + nowMillis + EXT)
        return runCatching {
            file.writeText(obj.toString(JSON_INDENT))
            file
        }.getOrElse {
            Log.e(TAG, "export failed", it)
            null
        }
    }

    fun timestampOf(file: File): Long? =
        file.name.removePrefix(PREFIX).removeSuffix(EXT).toLongOrNull()

    fun list(context: Context): List<File> {
        val dir = directory(context) ?: return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(EXT) } ?: return emptyList()
        return files.sortedByDescending { timestampOf(it) ?: 0L }
    }

    fun delete(file: File): Boolean = file.delete()
}
