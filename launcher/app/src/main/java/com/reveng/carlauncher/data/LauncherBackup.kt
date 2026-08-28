package com.reveng.carlauncher.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import java.io.File

/**
 * v0.4.2 — back up and restore the whole launcher state as a unit.
 *
 * A reinstall keeps grants (see [SetupDoctor]) but wipes *state* — themes, favourites, the app
 * order, the app-directory overrides, launcher settings and driver profiles. Rather than
 * hand-serialise every store (heterogeneous APIs, easy to forget one when a new store is added),
 * this copies the Jetpack DataStore preference files directly. Every `preferencesDataStore(name)`
 * in the app writes `filesDir/datastore/<name>.preferences_pb`, so copying that directory captures
 * all of them at once and can never drift out of sync with a newly-added store.
 *
 * Backups land in the app's external files dir (no runtime permission on API 33, reachable over
 * adb, survives an app update — same directory rationale as [ThemeTransfer]):
 *
 *     adb pull  /sdcard/Android/data/<applicationId>/files/backups/
 *     adb push  backup-<n>/  /sdcard/Android/data/<applicationId>/files/backups/
 *
 * **Restore requires a process restart.** Preferences DataStore holds an in-memory cache and does
 * not re-read its file when it changes underneath a running process; worse, its next write would
 * clobber the restored file. So [restore] copies the files back and the caller immediately calls
 * [restartApp], which schedules a relaunch and kills this process so every store reloads from disk.
 */
object LauncherBackup {

    private const val TAG = "LauncherBackup"
    private const val BACKUPS_DIR = "backups"
    private const val DATASTORE_DIR = "datastore"
    private const val PREFIX = "backup-"
    private const val PREFS_SUFFIX = ".preferences_pb"

    /** `filesDir/datastore` — where every `preferencesDataStore` in the app persists. */
    private fun datastoreDir(context: Context): File =
        File(context.applicationContext.filesDir, DATASTORE_DIR)

    /** External `files/backups`, created on demand. Null if external storage is unavailable. */
    fun backupsRoot(context: Context): File? {
        val base = context.applicationContext.getExternalFilesDir(null) ?: return null
        val dir = File(base, BACKUPS_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "could not create ${dir.absolutePath}")
            return null
        }
        return dir
    }

    /**
     * Snapshot every DataStore preference file into a new timestamped backup folder. [nowMillis]
     * is passed in (not read here) so the caller owns the clock. Returns the folder, or null if
     * there was nothing to back up or external storage was unavailable.
     */
    fun create(context: Context, nowMillis: Long): File? {
        val root = backupsRoot(context) ?: return null
        val src = datastoreDir(context)
        val prefFiles = src.listFiles { f -> f.isFile && f.name.endsWith(PREFS_SUFFIX) }
            ?.toList().orEmpty()
        if (prefFiles.isEmpty()) {
            Log.w(TAG, "no datastore files to back up")
            return null
        }
        val dest = File(root, "$PREFIX$nowMillis")
        if (!dest.mkdirs() && !dest.isDirectory) {
            Log.w(TAG, "could not create ${dest.absolutePath}")
            return null
        }
        return runCatching {
            prefFiles.forEach { it.copyTo(File(dest, it.name), overwrite = true) }
            dest
        }.getOrElse {
            Log.e(TAG, "backup copy failed", it)
            dest.deleteRecursively()
            null
        }
    }

    /** Timestamp (epoch millis) parsed back out of a backup folder name, or null. */
    fun timestampOf(backup: File): Long? =
        backup.name.removePrefix(PREFIX).toLongOrNull()

    /** Existing backups, newest first. */
    fun list(context: Context): List<File> {
        val root = backupsRoot(context) ?: return emptyList()
        val dirs = root.listFiles { f -> f.isDirectory && f.name.startsWith(PREFIX) } ?: return emptyList()
        return dirs.sortedByDescending { timestampOf(it) ?: 0L }
    }

    fun delete(backup: File): Boolean = backup.deleteRecursively()

    /**
     * Copy a backup's preference files back over the live ones. The process MUST be restarted
     * immediately afterwards (see class docs) — the caller does that via [restartApp]. Returns
     * false without touching anything if the backup has no preference files.
     */
    fun restore(context: Context, backup: File): Boolean {
        val prefFiles = backup.listFiles { f -> f.isFile && f.name.endsWith(PREFS_SUFFIX) }
            ?.toList().orEmpty()
        if (prefFiles.isEmpty()) {
            Log.w(TAG, "backup ${backup.name} has no preference files")
            return false
        }
        val dst = datastoreDir(context)
        if (!dst.exists() && !dst.mkdirs()) return false
        return runCatching {
            prefFiles.forEach { it.copyTo(File(dst, it.name), overwrite = true) }
            true
        }.getOrElse {
            Log.e(TAG, "restore copy failed", it)
            false
        }
    }

    /**
     * Schedule a relaunch of the launcher's entry activity a moment from now, then kill this
     * process so every DataStore reloads from the just-restored files. Standard restart pattern;
     * the AlarmManager entry survives the process death that follows.
     */
    fun restartApp(context: Context) {
        val ctx = context.applicationContext
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        val pending = PendingIntent.getActivity(
            ctx,
            RESTART_REQUEST,
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        am?.set(AlarmManager.RTC, System.currentTimeMillis() + RESTART_DELAY_MS, pending)
        Runtime.getRuntime().exit(0)
    }

    private const val RESTART_REQUEST = 0xB4C
    private const val RESTART_DELAY_MS = 400L
}
