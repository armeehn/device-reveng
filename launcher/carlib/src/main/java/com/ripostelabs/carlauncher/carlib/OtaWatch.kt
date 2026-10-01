package com.ripostelabs.carlauncher.carlib

import java.io.File

/**
 * OtaWatch: the root shell script that replaces the running launcher and watches the new one.
 *
 * The launcher cannot do this itself: `pm install` kills it mid-call. So it writes this script,
 * starts it detached through the root shell, and lets itself be replaced:
 *
 *     sleep ─▶ pm install -r --enable-rollback NEW ─fail─▶ result install-failed, reopen Home
 *                     │ Success
 *                     ▼
 *              reopen Home (am start HOME) ─▶ poll pidof every POLL_S, up to POLLS times
 *                     │                                   │
 *              healthy-<vc> written by the new launcher    the pid went away or changed twice
 *                     ▼                                   ▼
 *              result ok                     pm rollback-app (Android's snapshot, data too)
 *                                            else pm install -r -d <kept previous APK>
 *                                            result rolled-back, reopen Home
 *
 * The result file tells the next launcher what happened; a rolled-back version is never offered
 * again (OtaPlan.updates). POSIX sh only: the unit runs mksh, the unit tests run it under sh.
 */
object OtaWatch {

    enum class Result(val wire: String) {
        NONE(""),
        OK("ok"),
        UNCONFIRMED("unconfirmed"),
        INSTALL_FAILED("install-failed"),
        ROLLED_BACK("rolled-back"),
        ROLLBACK_FAILED("rollback-failed"),
    }

    /**
     * [newApk] is installed and deleted; [oldApk] is the launcher being replaced, kept for the
     * rollback. [stateDir] is the launcher's own folder that holds the markers.
     */
    data class Plan(
        val pkg: String,
        val versionCode: Long,
        val newApk: String,
        val oldApk: String,
        val stateDir: String,
        val polls: Int = POLLS,
        val pollS: Int = POLL_S,
        val settleS: Int = SETTLE_S,
        val deaths: Int = DEATHS,
    )

    /** The marker the new launcher writes once it has run for a while (OtaUpdater). */
    fun healthyMarker(stateDir: File, versionCode: Long) = File(stateDir, "healthy-$versionCode")

    fun resultFile(stateDir: File, versionCode: Long) = File(stateDir, "result-$versionCode")

    fun result(stateDir: File, versionCode: Long): Result {
        val text = resultFile(stateDir, versionCode).takeIf { it.isFile }?.readText()?.trim() ?: return Result.NONE
        return Result.entries.firstOrNull { it != Result.NONE && it.wire == text } ?: Result.NONE
    }

    fun script(p: Plan): String = """
        |# riposte OTA: replace the launcher, reopen Home, roll back if it dies twice on start.
        |PKG=${q(p.pkg)}
        |VC=${p.versionCode}
        |NEW=${q(p.newApk)}
        |OLD=${q(p.oldApk)}
        |DIR=${q(p.stateDir)}
        |umask 022
        |label=${'$'}(ls -Zd "${'$'}DIR" 2>/dev/null | cut -d' ' -f1)
        |
        |finish() {
        |  echo "${'$'}1" > "${'$'}DIR/result-${'$'}VC"
        |  [ -n "${'$'}label" ] && chcon "${'$'}label" "${'$'}DIR/result-${'$'}VC" 2>/dev/null
        |}
        |home() { am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null 2>&1; }
        |ok() { case "${'$'}1" in *Success*) return 0 ;; esac; return 1; }
        |version() {
        |  pm list packages --show-versioncode "${'$'}PKG" 2>/dev/null |
        |    sed -n "s/^package:${'$'}PKG versionCode:\([0-9]*\).*/\1/p"
        |}
        |
        |sleep ${p.settleS}
        |out=${'$'}(pm install -r --enable-rollback "${'$'}NEW" 2>&1)
        |ok "${'$'}out" || out=${'$'}(pm install -r "${'$'}NEW" 2>&1)
        |rm -f "${'$'}NEW"
        |if ! ok "${'$'}out"; then
        |  finish ${Result.INSTALL_FAILED.wire}
        |  home
        |  exit 1
        |fi
        |home
        |
        |deaths=0
        |last=
        |i=0
        |while [ "${'$'}i" -lt ${p.polls} ]; do
        |  i=${'$'}((i + 1))
        |  sleep ${p.pollS}
        |  if [ -f "${'$'}DIR/healthy-${'$'}VC" ]; then
        |    finish ${Result.OK.wire}
        |    exit 0
        |  fi
        |  pid=${'$'}(pidof "${'$'}PKG")
        |  if [ -n "${'$'}last" ] && [ "${'$'}pid" != "${'$'}last" ]; then
        |    deaths=${'$'}((deaths + 1))
        |  fi
        |  last=${'$'}pid
        |  if [ "${'$'}deaths" -ge ${p.deaths} ]; then
        |    pm rollback-app "${'$'}PKG" >/dev/null 2>&1
        |    [ "${'$'}(version)" = "${'$'}VC" ] && pm install -r -d "${'$'}OLD" >/dev/null 2>&1
        |    v=${'$'}(version)
        |    if [ -n "${'$'}v" ] && [ "${'$'}v" != "${'$'}VC" ]; then
        |      finish ${Result.ROLLED_BACK.wire}
        |    else
        |      finish ${Result.ROLLBACK_FAILED.wire}
        |    fi
        |    home
        |    exit 0
        |  fi
        |done
        |finish ${Result.UNCONFIRMED.wire}
        |""".trimMargin()

    /** Single-quoted for sh. */
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

    /** Two minutes: long enough for a cold start on the unit, short enough to roll back promptly. */
    const val POLLS = 60
    const val POLL_S = 2
    const val SETTLE_S = 3
    const val DEATHS = 2
}
