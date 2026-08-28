package com.reveng.carlauncher.carlib

import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * v2.9 — RootSession: one `su` process kept open for the life of the launcher, with commands
 * serialised over its stdin.
 *
 * Why this exists: [SysVar.putString] falls back to a root `content` shell for every write, and
 * through v2.5 the no-libsu path paid a full `su` fork + Magisk policy check per write. That is
 * tens to hundreds of milliseconds each, and the settings suite writes on every slider tick — the
 * lag was visible on the head unit. Holding the channel open moves that cost to the first write.
 *
 * **The injection protections are unchanged, on purpose.** This class only changes the *transport*
 * of an already-built command string; it never builds, escapes or re-quotes one. Callers such as
 * [SysVar.putViaRoot] still single-quote every interpolated value (via [RootShell.quote]) and still
 * SQL-escape what goes inside a `--where` clause, exactly as before, because the command text here
 * is handed to a root shell verbatim. A command carrying a newline would end the line early and
 * turn the remainder into a second root command, so such a command is refused outright and the
 * caller falls back to the per-call `su -c` backend, where a newline is safely inside one argv
 * entry.
 *
 * All calls are BLOCKING and mutually exclusive — invoke from Dispatchers.IO.
 */
internal object RootSession {

    private const val TAG = "RootSession"

    /**
     * Printed after every command so the reader can find the end of that command's output and
     * pick up its exit status. Deliberately unlikely to occur in `content`/`pm`/`cmd` output; a
     * command that echoed this string itself would truncate its own result, which is why the
     * marker is long and namespaced rather than something like "DONE".
     */
    private const val MARKER = "__CARLAUNCHER_ROOT_SESSION_DONE__"

    private val lock = Any()

    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    private var stdout: BufferedReader? = null

    /** Set once `su` cannot be started at all, so every later write doesn't re-pay the attempt. */
    private var unavailable = false

    /**
     * Run [command] over the persistent channel.
     *
     * @return the result, or null when this backend cannot serve the command — no root, a newline
     *   in the command, or a shell that died mid-command. Null means "use another backend", never
     *   "the command failed".
     */
    fun exec(command: String): RootShell.Result? {
        if (command.contains('\n') || command.contains('\r')) {
            return null
        }

        synchronized(lock) {
            if (unavailable) {
                return null
            }

            // The shell can die between commands — ACC off, a Magisk update, an OOM kill. Open or
            // re-open, run, and on a mid-command death retry exactly once on a fresh shell. A
            // second failure means something is wrong with `su` itself, so we hand back to the
            // caller instead of looping.
            if (!ensureOpen()) {
                return null
            }
            runCommand(command)?.let { return it }

            close()
            if (!ensureOpen()) {
                return null
            }
            return runCommand(command)
        }
    }

    /** Drop the channel. The next [exec] opens a new one. */
    fun close() {
        synchronized(lock) {
            runCatching { stdin?.close() }
            runCatching { stdout?.close() }
            runCatching { process?.destroy() }
            stdin = null
            stdout = null
            process = null
        }
    }

    private fun ensureOpen(): Boolean {
        process?.let { if (it.isAlive) return true }
        close()

        // stderr is merged into stdout rather than drained separately: separating the two streams
        // per command over one persistent channel needs a second marker protocol on a second pipe,
        // and an undrained stderr pipe deadlocks the shell once it fills. Merging keeps every
        // diagnostic line the vendor `content` tool writes, just not partitioned — callers that
        // need them apart get the per-call backend.
        return runCatching {
            val p = ProcessBuilder("su").redirectErrorStream(true).start()
            process = p
            stdin = BufferedWriter(OutputStreamWriter(p.outputStream))
            stdout = BufferedReader(InputStreamReader(p.inputStream))
            true
        }.getOrElse {
            Log.d(TAG, "no persistent su channel: ${it.message}")
            unavailable = true
            false
        }
    }

    /** @return null if the shell died while we were talking to it. */
    private fun runCommand(command: String): RootShell.Result? {
        val out = stdin ?: return null
        val input = stdout ?: return null

        val wrote = runCatching {
            out.write(command)
            out.newLine()
            // `$?` expands to the exit status of [command], so the marker line carries it back.
            out.write("echo \"$MARKER $?\"")
            out.newLine()
            out.flush()
        }.isSuccess
        if (!wrote) {
            return null
        }

        val lines = ArrayList<String>()
        while (true) {
            val line = runCatching { input.readLine() }.getOrNull() ?: return null

            if (!line.startsWith(MARKER)) {
                lines.add(line)
                continue
            }

            val code = line.removePrefix(MARKER).trim().toIntOrNull() ?: -1
            return RootShell.Result(code, lines, emptyList())
        }
    }
}
