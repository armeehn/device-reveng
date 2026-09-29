package com.ripostelabs.carlauncher.data

import android.util.Log
import com.ripostelabs.carlauncher.carlib.CarDecoder
import com.ripostelabs.carlauncher.carlib.RootShell

/**
 * The PR2000's signal lock for [AisCameraWorker]'s warm-up, read and written through the root
 * shell.
 *
 *     locked()          cat /sys/camera_status/camera_status ─▶ 7, the camera's own format
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

    /**
     * The status [CAMERA_FORMAT] sets: libais_pr2000.so's row 7, 1280x720. Any other row sizes a
     * stream too, but the wrong one: the chip boots at 11 and the picture came up grey.
     */
    private const val CAMERA_STATUS = 7

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

    /** Reads camera_status through root; null when the read fails. */
    val ROOT_STATUS: () -> String? = {
        val result = RootShell.exec("cat $STATUS_NODE")
        if (result.ok) result.stdout else null
    }

    /** Where [locked] reads the status: [ROOT_STATUS] on the unit, a stub in tests. */
    @Volatile
    var status: () -> String? = ROOT_STATUS

    /** The car service's decoder once MainActivity binds it; null keeps the root shell. */
    @Volatile
    var service: CarDecoder? = null

    /** True only for the camera's own format; see [CAMERA_STATUS]. */
    fun isLocked(status: String?): Boolean = status?.trim()?.toIntOrNull() == CAMERA_STATUS

    // Not through the service: it calls any sized row a lock, 11 included.
    override fun locked(): Boolean = isLocked(status())

    override fun forceStreamable() = lock()

    override fun redetect() = lock()

    private fun lock() {
        shell("setprop $WRITABLE_PROP 1; printf $REVERSE_CHANNEL > $DECODER_NODE; printf $CAMERA_FORMAT > $DECODER_NODE")
    }
}
