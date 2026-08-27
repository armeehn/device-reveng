package com.reveng.carlauncher.carlib

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
 * Two backends:
 *   1. [libsu] (com.github.topjohnwu.libsu:core) — preferred: keeps one persistent `su`
 *      session, handles quoting/exit codes robustly. Used automatically if the class is on
 *      the classpath at runtime (it is declared in carlib/build.gradle.kts).
 *   2. A pure [ProcessBuilder] `su -c` fallback — used if libsu is absent or errors. No
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
     * Run [command] as root. Prefers libsu, falls back to ProcessBuilder.
     */
    fun exec(command: String): Result {
        if (libsuPresent) {
            runCatching { return execLibsu(command) }
                .onFailure { Log.w(TAG, "libsu path failed, falling back to su -c", it) }
        }
        return execProcessBuilder(command)
    }

    /** Convenience: run multiple commands in one root session. */
    fun exec(vararg commands: String): Result = exec(commands.joinToString(" && "))

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
            val out = process.inputStream.readLines()
            val err = process.errorStream.readLines()
            val code = process.waitFor()
            Result(code, out, err)
        } catch (t: Throwable) {
            Log.e(TAG, "su -c failed: $command", t)
            Result(-1, emptyList(), listOf(t.message ?: t.toString()))
        }
    }

    private fun java.io.InputStream.readLines(): List<String> =
        BufferedReader(InputStreamReader(this)).use { it.readLines() }
}
