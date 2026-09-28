package com.ripostelabs.car

import android.util.Log
import java.io.File
import java.io.IOException

/**
 * The PR2000 as system uid, no root shell (os/sepolicy/riposte_car.cil labels the two nodes and
 * os/build.sh makes the mode property system_prop).
 *
 *     setMode(n)        setprop persist.riposte.camera.mode n ─▶ init: riposte-camera-mode.sh
 *     signal()          /sys/camera_status/camera_status        ─▶ 1..13 locked, 0 none
 *     forceStreamable() c0 + v4 to /sys/pr2000/pr2000           ─▶ status 2 (720x576)
 *     redetect()        r, c1, v0                               (BackcarEvent.java:1431-1433)
 *
 * The node parses the two characters after the letter as a number, so the root shell's
 * `echo v4` stored 4 x 10 + ('\n' - '0') = 2; [forceStreamable] writes those bytes itself.
 * Properties go through `setprop`/`getprop` because SystemProperties is a hidden API.
 */
class SysfsDecoder : Decoder {

    override fun mode(): Int = getprop(MODE_PROP).toIntOrNull() ?: 0

    override fun setMode(mode: Int) {
        val ok = setprop(MODE_PROP, mode.toString())
        Log.i(TAG, "decoder mode $mode via $MODE_PROP -> $ok")
    }

    override fun signal(): Int = try {
        File(STATUS_NODE).readText().trim().toIntOrNull() ?: ReverseState.NO_SIGNAL
    } catch (e: IOException) {
        Log.w(TAG, "cannot read $STATUS_NODE", e)
        ReverseState.NO_SIGNAL
    }

    override fun forceStreamable() {
        unlock()
        write("c0\n")
        write("v4\n")
    }

    override fun redetect() {
        unlock()
        write("r")
        Thread.sleep(RESET_SETTLE_MS)
        write("c1")
        write("v0")
    }

    // The driver refuses writes until the vendor's unlock property is set (BackcarEvent.java:1371).
    private fun unlock() {
        setprop(WRITABLE_PROP, "1")
    }

    private fun write(bytes: String) {
        try {
            File(DECODER_NODE).writeText(bytes)
        } catch (e: IOException) {
            Log.w(TAG, "cannot write ${bytes.trim()} to $DECODER_NODE", e)
        }
    }

    private fun getprop(name: String): String = run("getprop", name)?.trim().orEmpty()

    private fun setprop(name: String, value: String): Boolean = run("setprop", name, value) != null

    /** stdout of a finished command with exit 0, else null. */
    private fun run(vararg cmd: String): String? = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() == 0) out else null
    } catch (e: IOException) {
        Log.w(TAG, "${cmd.first()} failed", e)
        null
    }

    private companion object {
        const val TAG = "SysfsDecoder"
        const val MODE_PROP = "persist.riposte.camera.mode"
        const val WRITABLE_PROP = "sys.pr2000.writable"
        const val STATUS_NODE = "/sys/camera_status/camera_status"
        const val DECODER_NODE = "/sys/pr2000/pr2000"

        /** The root shell's `sleep 0.05` between reset and channel. */
        const val RESET_SETTLE_MS = 50L
    }
}
