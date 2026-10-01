package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The launcher cannot watch its own update: `pm install` kills it. So a root shell script does
 * it, detached before the install. These tests run that exact script under `sh` with fake `pm`,
 * `am` and `pidof` on the PATH, and read back what it asked Android to do.
 */
class OtaWatchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pkg = "com.ripostelabs.carlauncher"
    private val vc = 976L

    private class Run(val calls: List<String>, val result: OtaWatch.Result)

    /**
     * Run the script. [pids] is what `pidof` answers, one line per poll ("" = not running).
     * [pm] maps a pm sub-command prefix to a failure output. Otherwise the fake pm keeps the
     * installed versionCode in a file: an install sets 976, a downgrade or rollback sets 975.
     */
    private fun run(
        pids: List<String>,
        pm: Map<String, String> = emptyMap(),
        healthy: Boolean = false,
        polls: Int = 8,
    ): Run {
        val state = tmp.newFolder("state")
        val bin = tmp.newFolder("bin")
        val calls = File(tmp.root, "calls.log")
        File(tmp.root, "pids").writeText(pids.joinToString("\n", postfix = "\n"))
        val rules = pm.entries.joinToString("\n") { (k, v) -> "  \"$k\"*) echo '$v'; exit 1 ;;" }
        val ver = File(tmp.root, "ver").apply { writeText("975") }
        fake(bin, "pm", """
            echo "pm $*" >> ${calls.path}
            case "$*" in
            $rules
            esac
            case "$*" in
              "list packages"*) echo "package:$pkg versionCode:$(cat ${ver.path})"; exit 0 ;;
              "install -r -d"*|"rollback-app"*) echo 975 > ${ver.path} ;;
              "install"*) echo $vc > ${ver.path} ;;
            esac
            echo Success
        """)
        fake(bin, "am", """echo "am $*" >> ${calls.path}""")
        fake(bin, "pidof", """
            n=$(cat ${tmp.root}/n 2>/dev/null || echo 1)
            echo $((n + 1)) > ${tmp.root}/n
            sed -n "${'$'}{n}p" ${tmp.root}/pids
        """)
        fake(bin, "sleep", ":")
        if (healthy) {
            OtaWatch.healthyMarker(state, vc).writeText("ok")
        }

        val newApk = File(tmp.root, "new.apk").apply { writeText("new") }
        val plan = OtaWatch.Plan(pkg, vc, newApk.path, "/data/ota/launcher-975.apk", state.path, polls = polls)
        val script = File(tmp.root, "watch.sh").apply { writeText(OtaWatch.script(plan)) }
        val proc = ProcessBuilder("sh", script.path).redirectErrorStream(true).apply {
            environment()["PATH"] = bin.path + ":" + System.getenv("PATH")
        }.start()
        assertTrue("script finished", proc.waitFor(20, TimeUnit.SECONDS))
        val log = calls.takeIf { it.exists() }?.readLines() ?: emptyList()
        return Run(log, OtaWatch.result(state, vc))
    }

    private fun fake(bin: File, name: String, body: String) {
        File(bin, name).apply {
            writeText("#!/bin/sh\n" + body.trimIndent() + "\n")
            setExecutable(true)
        }
    }

    private val home = "am start -a android.intent.action.MAIN -c android.intent.category.HOME"

    @Test
    fun `a healthy start is left alone and Home is reopened`() {
        val r = run(pids = listOf("100"), healthy = true)
        assertEquals(OtaWatch.Result.OK, r.result)
        assertTrue(r.calls.first().startsWith("pm install -r --enable-rollback "))
        assertEquals(listOf(home), r.calls.filter { it.startsWith("am ") })
        assertFalse(r.calls.any { it.contains(" -d ") || it.contains("rollback-app") })
    }

    @Test
    fun `two crashes on start roll back and reopen Home`() {
        // up, gone (crash 1), restarted by Android, gone again (crash 2)
        val r = run(pids = listOf("100", "", "200", ""))
        assertEquals(OtaWatch.Result.ROLLED_BACK, r.result)
        assertTrue(r.calls.contains("pm rollback-app $pkg"))
        assertFalse("Android's rollback was enough", r.calls.any { it.contains(" -d ") })
        assertEquals(2, r.calls.count { it == home })
    }

    @Test
    fun `a pid that changes between polls is a crash too`() {
        val r = run(pids = listOf("100", "200", "300"))
        assertEquals(OtaWatch.Result.ROLLED_BACK, r.result)
    }

    @Test
    fun `one crash is not enough`() {
        val r = run(pids = listOf("100", "", "200", "200", "200", "200", "200", "200"))
        assertEquals(OtaWatch.Result.UNCONFIRMED, r.result)
        assertFalse(r.calls.any { it.contains(" -d ") })
    }

    @Test
    fun `without Android's rollback the kept APK goes back in`() {
        val r = run(pids = listOf("100", "", "200", ""), pm = mapOf("rollback-app" to "Error: no rollback available"))
        assertEquals(OtaWatch.Result.ROLLED_BACK, r.result)
        assertTrue(r.calls.contains("pm install -r -d /data/ota/launcher-975.apk"))
    }

    @Test
    fun `a rollback that changes nothing says so`() {
        val r = run(
            pids = listOf("100", "", "200", ""),
            pm = mapOf("rollback-app" to "Error: no rollback", "install -r -d" to "Failure [INSTALL_FAILED_VERSION_DOWNGRADE]"),
        )
        assertEquals(OtaWatch.Result.ROLLBACK_FAILED, r.result)
        assertEquals(2, r.calls.count { it == home })
    }

    @Test
    fun `no rollback support installs without it`() {
        val r = run(pids = listOf("100"), healthy = true, pm = mapOf("install -r --enable-rollback" to "Error: Unknown option"))
        assertEquals(OtaWatch.Result.OK, r.result)
        assertTrue(r.calls[1].startsWith("pm install -r /"))
    }

    @Test
    fun `a failed install reopens Home and watches nothing`() {
        val r = run(pids = listOf("100"), pm = mapOf("install" to "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]"))
        assertEquals(OtaWatch.Result.INSTALL_FAILED, r.result)
        assertEquals(listOf(home), r.calls.filter { it.startsWith("am ") })
        assertFalse(r.calls.any { it.contains(" -d ") || it.contains("rollback-app") })
    }

    @Test
    fun `no result file reads as none`() {
        assertEquals(OtaWatch.Result.NONE, OtaWatch.result(tmp.newFolder("empty"), vc))
    }
}
