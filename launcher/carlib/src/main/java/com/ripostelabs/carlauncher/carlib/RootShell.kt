package com.ripostelabs.carlauncher.carlib

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * RootShell — run privileged shell commands on this ROOTED head unit.
 *
 * Needed because (CAR_API §2, §6.4):
 *   * writing the SysVar provider requires system uid or root, and
 *   * a few gateway actions are cleaner to trigger via `am broadcast` from a root shell.
 *
 * Three backends, in order:
 *   1. [libsu] (com.github.topjohnwu.libsu:core) — preferred: keeps one persistent `su`
 *      session, handles quoting/exit codes robustly. Used automatically if the class is on
 *      the classpath at runtime (it is declared in carlib/build.gradle.kts).
 *   2. v2.9 [RootSession] — our own persistent `su` channel, so the no-libsu path is no longer
 *      a fork per command. Matters because the settings suite writes SysVar on every control
 *      change (see the KDoc there).
 *   3. A pure [ProcessBuilder] `su -c` fallback — used if the other two are absent or error. No
 *      extra dependency required.
 *
 * All calls are BLOCKING — invoke from a background thread / coroutine Dispatchers.IO.
 */
object RootShell {

    private const val TAG = "RootShell"

    data class Result(val code: Int, val out: List<String>, val err: List<String>) {
        val ok: Boolean get() = code == 0
        val stdout: String get() = out.joinToString("\n")
    }

    /** True once we detect a working `su`. Cached after first probe. */
    @Volatile
    private var rootAvailable: Boolean? = null

    /** libsu present on classpath? Probed reflectively so carlib compiles without it. */
    private val libsuPresent: Boolean by lazy {
        runCatching { Class.forName("com.topjohnwu.superuser.Shell") }.isSuccess
    }

    fun isRootAvailable(): Boolean {
        rootAvailable?.let { return it }
        val res = exec("id")
        val available = res.ok && res.stdout.contains("uid=0")
        rootAvailable = available
        return available
    }

    /**
     * Run [command] as root. Prefers libsu, then the v2.9 persistent channel, then ProcessBuilder.
     */
    fun exec(command: String): Result {
        if (libsuPresent) {
            runCatching { return execLibsu(command) }
                .onFailure { Log.w(TAG, "libsu path failed, falling back to su -c", it) }
        }
        // v2.9: reuse one open `su` instead of forking per command. Returns null when it cannot
        // serve this command at all (no root, or a newline that would split into two commands),
        // in which case the per-call backend below still runs it correctly.
        RootSession.exec(command)?.let { return it }

        return execProcessBuilder(command)
    }

    /** One command from the vararg [exec], paired with what it returned. */
    data class Outcome(val command: String, val result: Result)

    /**
     * What several independent commands did. [ok] only when every one exited 0; [failures] names
     * the ones that did not, so a caller can report *which* repair failed instead of reporting a
     * single boolean that hides a partial success.
     */
    data class MultiResult(val outcomes: List<Outcome>) {
        val ok: Boolean get() = outcomes.all { it.result.ok }
        val failures: List<Outcome> get() = outcomes.filter { !it.result.ok }
    }

    /**
     * Convenience: run several INDEPENDENT commands, each as its own [exec].
     *
     * Deliberately not `a && b && c`. That made every command conditional on the one before it, so
     * one non-zero exit — an already-granted permission, a vendor `pm` quirk — silently dropped
     * every command after it: a "repair all" that repaired up to the first failure and then said
     * nothing. Each command now runs whatever the ones before it did, in order.
     */
    fun exec(vararg commands: String): MultiResult = execEach(commands.asList()) { exec(it) }

    /** The fan-out of [exec], with the backend passed in so it is testable without a real `su`. */
    internal fun execEach(commands: List<String>, run: (String) -> Result): MultiResult =
        MultiResult(commands.map { Outcome(it, run(it)) })

    /**
     * v2.9 — wrap [s] in single quotes, escaping any embedded single quote, for exactly one shell
     * level.
     *
     * Every backend hands the command to one shell, so an interpolated value that is not quoted
     * here executes as root: a bare value with a space breaks argument splitting, and `;`, `$()`
     * or backticks run as commands. This is the single implementation — [SysVar] and the v2.9
     * root helpers all route through it rather than keeping private copies that could drift.
     */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    // ---- libsu backend (reflection so the dep stays optional) ---------------
    private fun execLibsu(command: String): Result {
        // Equivalent to: Shell.cmd(command).exec()
        val shellClass = Class.forName("com.topjohnwu.superuser.Shell")
        val cmd = shellClass.getMethod("cmd", Array<String>::class.java)
            .invoke(null, arrayOf(command))
        val jobExec = cmd.javaClass.getMethod("exec")
        val result = jobExec.invoke(cmd)
        val rc = result.javaClass.getMethod("getCode").invoke(result) as Int
        @Suppress("UNCHECKED_CAST")
        val out = result.javaClass.getMethod("getOut").invoke(result) as List<String>
        @Suppress("UNCHECKED_CAST")
        val err = result.javaClass.getMethod("getErr").invoke(result) as List<String>
        return Result(rc, out, err)
    }

    // ---- ProcessBuilder `su -c` backend -------------------------------------
    private fun execProcessBuilder(command: String): Result {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(false)
                .start()
            // Drain stderr concurrently with stdout. Reading stdout to EOF first would
            // deadlock if the command writes more than the stderr pipe buffer (~64 KB) while
            // still producing stdout: the child blocks writing stderr, never closes stdout, and
            // our stdout read blocks forever.
            val errLines = ArrayList<String>()
            val errThread = Thread {
                runCatching { errLines.addAll(process.errorStream.readLines()) }
            }.apply { isDaemon = true; start() }
            val out = process.inputStream.readLines()
            errThread.join()
            val code = process.waitFor()
            Result(code, out, errLines)
        } catch (t: Throwable) {
            Log.e(TAG, "su -c failed: $command", t)
            Result(-1, emptyList(), listOf(t.message ?: t.toString()))
        }
    }

    private fun java.io.InputStream.readLines(): List<String> =
        BufferedReader(InputStreamReader(this)).use { it.readLines() }
}
