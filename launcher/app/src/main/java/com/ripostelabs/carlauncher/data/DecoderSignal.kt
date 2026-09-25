package com.ripostelabs.carlauncher.data

import android.util.Log
import com.ripostelabs.carlauncher.carlib.RootShell

/**
 * The PR2000's signal lock through the root shell, for [AisCameraWorker]'s warm-up.
 *
 *     locked()          cat /sys/camera_status/camera_status ─▶ 1..13 (a libais_pr2000 size row)
 *     forceStreamable() c0 + v4 to /sys/pr2000/pr2000 ─▶ status 2 (720x576): a stream can start
 *     redetect()        r, c1, v0 (stock's reset + default channel + auto, BackcarEvent.java:1431-1433)
 *
 * The node parses the two characters after the letter as a number, so a newline counts:
 * `echo v4` stores 4 x 10 + ('\n' - '0') = 2. That is the write that gave the stream on the bench
 * twice; the one-digit `printf` forms are the redetect that followed it. Both kept byte for byte.
 */
object DecoderSignal : AisCameraWorker.Signal {

    private const val STATUS_NODE = "/sys/camera_status/camera_status"
    private const val DECODER_NODE = ReverseCameraDecoder.PR2000_NODE
    private const val WRITABLE_PROP = "sys.pr2000.writable"

    /** libais_pr2000.so accepts status - 1 in 0..12 (`cmp w8, #0xc`), else 0 x 0. */
    private val SIZED_STATUS = 1..13

    private const val TAG = "DecoderSignal"

    /** True for a status the AIS server can size a stream from. */
    fun isLocked(status: String?): Boolean = status?.trim()?.toIntOrNull() in SIZED_STATUS

    override fun locked(): Boolean {
        val result = RootShell.exec("cat $STATUS_NODE")
        return result.ok && isLocked(result.stdout)
    }

    override fun forceStreamable() {
        run("setprop $WRITABLE_PROP 1; echo c0 > $DECODER_NODE; echo v4 > $DECODER_NODE")
    }

    override fun redetect() {
        run("printf r > $DECODER_NODE; sleep 0.05; printf c1 > $DECODER_NODE; printf v0 > $DECODER_NODE")
    }

    private fun run(command: String) {
        val result = RootShell.exec(command)
        Log.i(TAG, "$command -> ${result.code}")
    }
}
