package com.reveng.carlauncher.data

import android.content.Context
import android.util.Log
import com.reveng.carlauncher.BuildConfig
import java.io.File
import java.io.FileOutputStream

/**
 * v0.4.3.7 — persist uncaught exceptions to disk so a crash that happens while driving leaves
 * evidence behind.
 *
 * When this launcher dies the driver gets a black screen or the vendor home, and by the time
 * anyone can look at the unit the logcat ring has long rolled over. Wireless adb on this head
 * unit rotates its port on every reboot and has to be re-paired by hand at the car, so "attach a
 * debugger and reproduce it" is not a workflow that exists here. A file on disk is.
 *
 * The handler runs inside a process that is already broken — possibly on the main thread,
 * possibly out of memory, possibly because the very subsystem it would want to use is what
 * failed. So it is deliberately minimal: no coroutines, no DataStore, no PackageManager call (the
 * version string is baked in at compile time), one synchronous [FileOutputStream] write into
 * internal storage, and the whole body wrapped so that a failure *inside* the handler cannot stop
 * the delegation below. It never swallows the throwable: the previously-installed default handler
 * still runs, so the process dies exactly as it would have. A launcher limping on after an
 * unhandled exception is worse than a clean restart.
 *
 * Storage is a bounded ring — [MAX_RECORDS] records and [MAX_BYTES] total, oldest evicted first.
 * The unit sits unattended in a car for weeks; unbounded logging onto embedded flash is not an
 * option.
 *
 * [export] copies the log into the external files dir, the same off-device route (and the same
 * rationale) as [LauncherBackup]:
 *
 *     adb pull /sdcard/Android/data/<applicationId>/files/crash-logs/
 */
object CrashLog {

    private const val TAG = "CrashLog"
    private const val LOG_NAME = "crashes.log"
    private const val EXPORT_DIR = "crash-logs"
    private const val EXPORT_PREFIX = "crash-"
    private const val EXPORT_EXT = ".log"

    /** Ring bounds. Twelve traces is far more history than any diagnosis has ever needed. */
    const val MAX_RECORDS = 12
    const val MAX_BYTES = 192 * 1024

    /** One runaway trace must not be able to evict every other record on its own. */
    const val MAX_TRACE_CHARS = 12_000
    private const val TRUNCATED = "… trace truncated"

    private const val MARKER = "----- crash "
    private const val MARKER_END = " -----"
    private const val THREAD_KEY = "thread: "
    private const val VERSION_KEY = "version: "
    private const val SEPARATOR = "\n\n"

    /**
     * Arm the handler. Idempotent — re-installing over ourselves would chain a second copy and
     * write every crash twice.
     */
    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Recorder) {
            return
        }
        val version = BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"
        Thread.setDefaultUncaughtExceptionHandler(handler(logFile(context), version, previous))
    }

    /** `filesDir/crashes.log` — internal storage, which unlike external is always mounted. */
    fun logFile(context: Context): File =
        File(context.applicationContext.filesDir, LOG_NAME)

    /** Stored crashes, newest first. An unreadable or corrupt log reads as what it can, never throws. */
    fun read(context: Context): List<CrashRecord> {
        val file = logFile(context)
        if (!file.isFile) {
            return emptyList()
        }
        val raw = runCatching { file.readText() }.getOrElse {
            Log.w(TAG, "could not read " + file.absolutePath, it)
            return emptyList()
        }
        return parse(raw).asReversed()
    }

    fun clear(context: Context): Boolean = logFile(context).delete()

    /**
     * Copy the log into external `files/crash-logs` — reachable over adb or from a file manager
     * without any runtime permission on API 33. Null if there is nothing to export or the copy
     * failed. [nowMillis] is passed in so the caller owns the clock.
     */
    fun export(context: Context, nowMillis: Long): File? {
        val src = logFile(context)
        if (!src.isFile || src.length() == 0L) {
            return null
        }
        val base = context.applicationContext.getExternalFilesDir(null) ?: return null
        val dir = File(base, EXPORT_DIR)
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "could not create " + dir.absolutePath)
            return null
        }
        val dest = File(dir, EXPORT_PREFIX + nowMillis + EXPORT_EXT)
        return runCatching {
            src.copyTo(dest, overwrite = true)
            dest
        }.getOrElse {
            Log.e(TAG, "export failed", it)
            null
        }
    }

    /** Render one record. Pure — the clock, the thread name and the version are all passed in. */
    fun format(nowMillis: Long, thread: String, version: String, error: Throwable): String {
        val trace = error.stackTraceToString().trimEnd()
        val bounded = if (trace.length <= MAX_TRACE_CHARS) {
            trace
        } else {
            trace.take(MAX_TRACE_CHARS) + "\n" + TRUNCATED
        }
        return MARKER + nowMillis + MARKER_END + "\n" +
            THREAD_KEY + thread + "\n" +
            VERSION_KEY + version + "\n\n" +
            bounded
    }

    /**
     * Cut stored text into whole records at the marker lines. Anything before the first marker is
     * dropped: that is the tail of a record a power cut interrupted mid-write, and it has no header
     * to attribute it to.
     */
    fun split(raw: String): List<String> {
        val chunks = mutableListOf<StringBuilder>()
        raw.lineSequence().forEach { line ->
            if (line.startsWith(MARKER)) {
                chunks.add(StringBuilder())
            }
            chunks.lastOrNull()?.append(line)?.append('\n')
        }
        return chunks.map { it.toString().trimEnd('\n') }.filter { it.isNotEmpty() }
    }

    fun join(records: List<String>): String =
        if (records.isEmpty()) "" else records.joinToString(SEPARATOR, postfix = "\n")

    /**
     * The ring: newest last. Trims by count first (cheap), then by the size of what would actually
     * be written. A lone oversized record is kept — a truncated trace still beats no trace.
     */
    fun evict(
        records: List<String>,
        maxRecords: Int = MAX_RECORDS,
        maxBytes: Int = MAX_BYTES,
    ): List<String> {
        var kept = if (records.size > maxRecords) records.takeLast(maxRecords) else records
        while (kept.size > 1 && join(kept).toByteArray().size > maxBytes) {
            kept = kept.drop(1)
        }
        return kept
    }

    /**
     * Read stored text back into displayable records, oldest first. Every field is optional on the
     * way in: a record whose write was cut short mid-header yields what survived rather than
     * throwing, because the whole point of the file is to be readable after a bad death.
     */
    fun parse(raw: String): List<CrashRecord> = split(raw).map { chunk ->
        val lines = chunk.lines()
        val time = lines.first()
            .removePrefix(MARKER)
            .removeSuffix(MARKER_END)
            .trim()
            .toLongOrNull() ?: 0L
        val thread = lines.firstOrNull { it.startsWith(THREAD_KEY) }?.removePrefix(THREAD_KEY).orEmpty()
        val version = lines.firstOrNull { it.startsWith(VERSION_KEY) }?.removePrefix(VERSION_KEY).orEmpty()
        val trace = lines.drop(1)
            .dropWhile { it.startsWith(THREAD_KEY) || it.startsWith(VERSION_KEY) || it.isBlank() }
            .joinToString("\n")
        CrashRecord(timeMillis = time, thread = thread, version = version, trace = trace)
    }

    /**
     * The handler [install] wires up, built without touching process-global state so a test can
     * fire a throwable through it and check both halves of the contract: the record lands, and the
     * previous handler still runs.
     */
    internal fun handler(
        file: File,
        version: String,
        previous: Thread.UncaughtExceptionHandler?,
    ): Thread.UncaughtExceptionHandler = Recorder(file, version, previous)

    private const val TMP_SUFFIX = ".tmp"

    /** Serializes [store]: two threads crashing at once must not interleave in the same file. */
    private val storeLock = Any()

    /**
     * Append, evict, rewrite. The rewrite goes to a side file that is renamed into place:
     * truncating the log and rewriting it in-place meant a power cut mid-write — the normal
     * crash environment in a car — destroyed the entire history. A torn write now tears only
     * the tmp file; the log itself is always the previous complete version or the new one.
     */
    private fun store(file: File, record: String) = synchronized(storeLock) {
        val existing = if (file.isFile) file.readText() else ""
        val kept = evict(split(existing) + record)
        val bytes = join(kept).toByteArray()

        val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
        FileOutputStream(tmp, false).use {
            it.write(bytes)
            it.fd.sync() // flushed before the rename, or the rename can land an empty file
        }

        if (!tmp.renameTo(file)) {
            // Same-directory rename should not fail; if it somehow does, an in-place write is
            // still better than dropping the record.
            FileOutputStream(file, false).use { it.write(bytes) }
            tmp.delete()
        }
    }

    private class Recorder(
        private val file: File,
        private val version: String,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(thread: Thread, error: Throwable) {
            try {
                store(file, format(System.currentTimeMillis(), thread.name, version, error))
            } catch (handlerFailure: Throwable) {
                // A crash handler that itself crashes hides the bug it was installed to record.
                // Nothing here is worth losing the original throwable over, so it dies quietly and
                // the delegation below still runs.
            }
            previous?.uncaughtException(thread, error)
        }
    }
}

/** One crash as read back off disk. */
data class CrashRecord(
    val timeMillis: Long,
    val thread: String,
    val version: String,
    val trace: String,
) {
    /** The exception class and message — the first non-blank trace line — for a one-line list row. */
    val summary: String
        get() = trace.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}
