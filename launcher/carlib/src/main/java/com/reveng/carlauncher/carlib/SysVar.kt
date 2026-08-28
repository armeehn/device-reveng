package com.reveng.carlauncher.carlib

import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * SysVar — typed access to the vendor settings store
 * `content://com.szchoiceway.eventcenter.SysVarProvider/SysVar` (CAR_API §2).
 *
 * Reads go through the ordinary [android.content.ContentResolver] (open to any app).
 * Writes require system uid or root, so they are routed through [RootShell] using the
 * `content update`/`content insert` shell commands documented in CAR_API §2.2.
 *
 * Everything in the provider is stored as TEXT under two columns: `keyname`, `keyvalue`.
 */
class SysVar(private val context: Context) {

    companion object {
        private const val TAG = "SysVar"

        const val AUTHORITY = "com.szchoiceway.eventcenter.SysVarProvider"
        const val CONTENT_URI_STRING =
            "content://com.szchoiceway.eventcenter.SysVarProvider/SysVar"
        val CONTENT_URI: Uri = Uri.parse(CONTENT_URI_STRING)

        const val COL_KEYNAME = "keyname"
        const val COL_KEYVALUE = "keyvalue"

        // ---- Selected keyname strings (CAR_API §2.3) ------------------------
        const val KEY_CAR_TYPE = "Sys_CarType"
        const val KEY_CUSTOMER_TYPE = "Sys_CustomerType"
        const val KEY_BACKCAR_TYPE = "SYS_BACKCAR_TYPE"
        const val KEY_BACKCAR_VIDEO_TYPE = "Sys_backcar_Video_Type"
        const val KEY_BACKCAR_CAMERA_MIRRORING = "Sys_Backcar_Camera_Mirroring"
        const val KEY_BACKCAR_FULLSCREEN = "Sys_backcar_fullscreen"
        const val KEY_BACKCAR_SPEED_THRESHOLD = "Sys_Backcar_speed_threshold"
        const val KEY_BACKCAR_DISPLAY_RADAR = "Sys_BackCar_Display_Radar_Key"
        const val KEY_REVERSE_ASSIST_LINE = "Sys_Reverse_Assist_Line_Key"
        const val KEY_TRACK_LINE_TYPE = "Sys_TrackLineType"
        const val KEY_LIGHT_LEVEL_SET = "Sys_Light_Level_set"
        const val KEY_DAY_NIGHT_MODE = "Sys_Day_Night_Mode"
        const val KEY_CAR_SPEED_UNIT = "Sys_Car_Speed_Unit" // 0=km/h 1=mph
        const val KEY_SHOW_CAR_SPEED = "Set_ShowCarSpeed"
        const val KEY_MCU_VERSION = "Sys_McuVersion"
        const val KEY_SCREEN_WIDTH = "Sys_Screen_Width"
        const val KEY_SCREEN_HEIGHT = "Sys_Screen_Height"
        const val KEY_SCREEN_DENSITY = "Sys_Screen_Density"
        const val KEY_LAUNCHER_APP_HIDE = "SYS_LAUNCHER_APP_HIDE_KEY"
        const val KEY_HOME_PAGE_DISPLAY = "Sys_Home_Page_Display"
    }

    // ---- Reads --------------------------------------------------------------

    /** Read a single key, or null if absent / unreadable. */
    fun getString(keyname: String): String? {
        return try {
            context.contentResolver.query(
                CONTENT_URI, null, "$COL_KEYNAME=?", arrayOf(keyname), null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(COL_KEYVALUE)
                    if (idx >= 0) c.getString(idx) else null
                } else null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "getString($keyname) failed", t)
            null
        }
    }

    fun getInt(keyname: String, def: Int = 0): Int =
        getString(keyname)?.trim()?.toIntOrNull() ?: def

    fun getLong(keyname: String, def: Long = 0L): Long =
        getString(keyname)?.trim()?.toLongOrNull() ?: def

    fun getBoolean(keyname: String, def: Boolean = false): Boolean {
        val v = getString(keyname)?.trim() ?: return def
        return v == "1" || v.equals("true", ignoreCase = true)
    }

    /** Read the whole table as a map (CAR_API §2.1). */
    fun readAll(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        try {
            context.contentResolver.query(CONTENT_URI, null, null, null, null)?.use { c ->
                val kIdx = c.getColumnIndex(COL_KEYNAME)
                val vIdx = c.getColumnIndex(COL_KEYVALUE)
                if (kIdx >= 0 && vIdx >= 0) {
                    while (c.moveToNext()) {
                        // Skip rows with a null keyname: the vendor table can contain them, and
                        // a null key later NPEs any consumer that sorts/searches by key.
                        val key = c.getString(kIdx) ?: continue
                        map[key] = c.getString(vIdx) ?: ""
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "readAll failed", t)
        }
        return map
    }

    // ---- Writes (root) ------------------------------------------------------

    /**
     * Try a direct ContentResolver.update first (works only if this app is system uid);
     * on failure/zero-rows, fall back to a root `content` shell command (CAR_API §2.2).
     * BLOCKING — call off the main thread.
     *
     * @return true if the value was written by either path.
     */
    fun putString(keyname: String, value: String): Boolean {
        // Path 1: direct update (system app only).
        runCatching {
            val values = ContentValues().apply { put(COL_KEYVALUE, value) }
            val rows = context.contentResolver.update(
                CONTENT_URI, values, "$COL_KEYNAME=?", arrayOf(keyname)
            )
            if (rows > 0) return true
            // Not present -> insert.
            val insVals = ContentValues().apply {
                put(COL_KEYNAME, keyname)
                put(COL_KEYVALUE, value)
            }
            if (context.contentResolver.insert(CONTENT_URI, insVals) != null) return true
        }.onFailure { Log.d(TAG, "direct write denied (expected for normal app): ${it.message}") }

        // Path 2: root shell.
        return putViaRoot(keyname, value)
    }

    fun putInt(keyname: String, value: Int): Boolean = putString(keyname, value.toString())

    private fun putViaRoot(keyname: String, value: String): Boolean {
        // RootShell runs the command through exactly one shell (libsu Shell.cmd or `su -c`),
        // so every interpolated value MUST be single-quoted for that one shell level — a bare
        // value with a space breaks argument splitting, and shell metacharacters (`;`, `$()`,
        // backticks) would otherwise execute as root. Inside the SQL --where clause the key
        // additionally needs SQL escaping (single quote -> two single quotes).
        val update = "content update --uri $CONTENT_URI_STRING " +
            "--bind ${sh("$COL_KEYVALUE:s:$value")} " +
            "--where ${sh("$COL_KEYNAME='${sqlEscape(keyname)}'")}"
        val res = RootShell.exec(update)
        if (res.ok) {
            // `content update` reports success even if 0 rows matched, so also insert-if-missing.
            if (getString(keyname) == value) return true
        }
        val insert = "content insert --uri $CONTENT_URI_STRING " +
            "--bind ${sh("$COL_KEYNAME:s:$keyname")} --bind ${sh("$COL_KEYVALUE:s:$value")}"
        val ins = RootShell.exec(insert)
        val done = getString(keyname) == value
        if (!done) Log.w(TAG, "putViaRoot($keyname) failed: update=$res insert=$ins")
        return done
    }

    /** Wrap [s] in single quotes, safely escaping any embedded single quote, for one shell level. */
    private fun sh(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Escape a value for embedding inside a single-quoted SQL string literal. */
    private fun sqlEscape(s: String): String = s.replace("'", "''")

    // ---- Change notifications (CAR_API §2) ----------------------------------

    /**
     * Observe provider changes. The provider notifies with a URI that encodes `key=value`
     * (CAR_API §2). Returns a handle you must [unobserve] to release.
     */
    fun observe(onChange: (uri: Uri?) -> Unit): ContentObserver {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = onChange(uri)
        }
        context.contentResolver.registerContentObserver(CONTENT_URI, true, observer)
        return observer
    }

    fun unobserve(observer: ContentObserver) {
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
    }
}
