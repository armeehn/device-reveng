package com.ripostelabs.carlauncher.data

import android.util.Log
import com.ripostelabs.carlauncher.carlib.CarDecoder
import com.ripostelabs.carlauncher.carlib.RootShell

/**
 * The PR2000's signal lock for [AisCameraWorker]'s warm-up: through the car service when one
 * is bound ([service], API 4), else through the root shell.
 *
 *     locked()          cat /sys/camera_status/camera_status ─▶ 1..13 (a libais_pr2000 size row)
 *     forceStreamable() c1 + v7 to /sys/pr2000/pr2000 ─▶ status 7 at once (AHD 720p30)
 *     redetect()        the same: the lock is the only nudge that works on this kernel
 *
 * Stock's redetect (r, c1, v0; BackcarEvent.java:1431-1433) leaves camera_status at 0 here: the
 * reset drops the channel and auto never fills it. Channel 1, then the camera's own format, set it
 * to 7 at once, and the picture came (car, 2026-09-28). One-digit `printf`: the node reads the two
 * characters after the letter, so `echo v7` would store 7 x 10 + ('\n' - '0').
 * Root, not the car service: the service's copy of the sequence is stock's.
 */
object DecoderSignal : AisCameraWorker.Signal {

    private const val STATUS_NODE = "/sys/camera_status/camera_status"
    private const val DECODER_NODE = ReverseCameraDecoder.PR2000_NODE
    private const val WRITABLE_PROP = "sys.pr2000.writable"

    /** libais_pr2000.so accepts status - 1 in 0..12 (`cmp w8, #0xc`), else 0 x 0. */
    private val SIZED_STATUS = 1..13

    private const val TAG = "DecoderSignal"

    /** The reverse input: stock's default channel. */
    private const val REVERSE_CHANNEL = "c1"

    /** The owner's camera, AHD 720p30: libais_pr2000.so's row 7, 1280x720. */
    private const val CAMERA_FORMAT = "v7"

    /** Runs one root command and logs its exit code. */
    val ROOT: (String) -> Unit = { command ->
        val result = RootShell.exec(command)
        Log.i(TAG, "$command -> ${result.code}")
    }

    /** Where the lock's writes go: [ROOT] on the unit, a recorder in tests. */
    @Volatile
    var shell: (String) -> Unit = ROOT

    /** The car service's decoder once MainActivity binds it; null keeps the root shell. */
    @Volatile
    var service: CarDecoder? = null

    /** True for a status the AIS server can size a stream from. */
    fun isLocked(status: String?): Boolean = status?.trim()?.toIntOrNull() in SIZED_STATUS

    override fun locked(): Boolean {
        service?.decoderLocked()?.let { return it }
        val result = RootShell.exec("cat $STATUS_NODE")
        return result.ok && isLocked(result.stdout)
    }

    override fun forceStreamable() = lock()

    override fun redetect() = lock()

    private fun lock() {
        shell("setprop $WRITABLE_PROP 1; printf $REVERSE_CHANNEL > $DECODER_NODE; printf $CAMERA_FORMAT > $DECODER_NODE")
    }
}
