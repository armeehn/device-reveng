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

    /** The `*.preferences_pb` files directly inside [dir] (empty if none or unreadable). */
    private fun prefFilesIn(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(PREFS_SUFFIX) }?.toList().orEmpty()

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
        val prefFiles = prefFilesIn(datastoreDir(context))
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
     * Bring a backup's preference files back over the live ones. Returns true when ANY live
     * file was replaced — the caller must then restart via [restartApp] unconditionally, even
     * if a later step failed, because the on-disk state no longer matches the in-memory caches.
     * False means the live files are untouched (empty backup, or the staging phase failed).
     */
    fun restore(context: Context, backup: File): Boolean =
        restoreInto(datastoreDir(context), backup)

    /**
     * Two-phase restore into [dst]: stage every file first (`*.restore-tmp` copies), and only
     * once ALL copies succeeded rename each into place. The old one-pass copy could fail
     * half-way and leave a silent mix of two snapshots — with the restore reported as failed,
     * so no restart followed and the running stores kept writing over the hybrid.
     *
     * [log] is a seam: android.util.Log is unmocked in local unit tests, and this function is
     * the file-level logic those tests exercise.
     */
    internal fun restoreInto(
        dst: File,
        backup: File,
        log: (String) -> Unit = { Log.w(TAG, it) },
    ): Boolean {
        val prefFiles = prefFilesIn(backup)
        if (prefFiles.isEmpty()) {
            log("backup ${backup.name} has no preference files")
            return false
        }
        if (!dst.exists() && !dst.mkdirs()) return false

        // Phase 1 — stage. A failure here deletes the stages and leaves the live files alone.
        val staged = mutableListOf<Pair<File, File>>() // tmp -> live destination
        val stagedAll = runCatching {
            prefFiles.forEach { src ->
                val tmp = File(dst, src.name + RESTORE_TMP_SUFFIX)
                src.copyTo(tmp, overwrite = true)
                staged.add(tmp to File(dst, src.name))
            }
        }.onFailure { log("restore staging failed: $it") }.isSuccess
        if (!stagedAll) {
            staged.forEach { (tmp, _) -> tmp.delete() }
            return false
        }

        // Phase 2 — swap in. Each rename is atomic; count what actually landed so the caller
        // restarts whenever the live state changed at all.
        var replaced = 0
        staged.forEach { (tmp, live) ->
            if (tmp.renameTo(live)) {
                replaced++
            } else {
                log("restore could not swap in ${live.name}")
                tmp.delete()
            }
        }
        return replaced > 0
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
    private const val RESTORE_TMP_SUFFIX = ".restore-tmp"
}
