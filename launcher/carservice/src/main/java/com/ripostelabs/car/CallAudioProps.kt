package com.ripostelabs.car

import android.util.Log
import java.io.IOException

/**
 * RAV4-184: call audio props through `setprop`/`getprop`, as the system uid that may set
 * `persist.blinkbt.*` (stock eventcenter does the same as system). SystemProperties is a hidden
 * API, so the service runs the tools, like [SysfsDecoder].
 */
class CallAudioProps : CallAudioSettings {

    override fun set(prop: String, value: String) {
        val ok = run("setprop", prop, value) != null
        Log.i(TAG, "$prop = $value -> $ok")
    }

    override fun get(prop: String): String = run("getprop", prop)?.trim().orEmpty()

    /** stdout of a finished command with exit 0, else null. */
    private fun run(vararg cmd: String): String? = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (p.waitFor() == 0) out else null
    } catch (e: IOException) {
        Log.w(TAG, "cannot run ${cmd.first()}", e)
        null
    }

    private companion object {
        const val TAG = "CallAudioProps"
    }
}
