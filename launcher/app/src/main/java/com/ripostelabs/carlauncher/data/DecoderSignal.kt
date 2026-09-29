package com.ripostelabs.carlauncher.data

import android.util.Log
import com.ripostelabs.carlauncher.carlib.CarDecoder
import com.ripostelabs.carlauncher.carlib.RootShell

/**
 * The PR2000's signal lock for [AisCameraWorker]'s warm-up, read and nudged through the root
 * shell, the way stock's BackcarEvent does it.
 *
 *     locked()          cat /sys/camera_status/camera_status ─▶ 1..13 (a libais_pr2000 size row)
 *     forceStreamable() c1 + v0 to /sys/pr2000/pr2000 ─▶ check_pr2000_signal finds the format
 *     redetect()        the same
 *
 * Stock's redetect is r, c1, v0 (BackcarEvent.java:1431-1433). The reset drops the channel on
 * this kernel and leaves 0, so it goes; c1 + v0 alone ran the check and the row came within a
 * second (car, 2026-09-29). A forced format (v7) only re-triggers the check. This camera has come
 * up as 7 (AHD 720p30) and 11 (TVI 720p30); [AisCameraWorker] reopens when the row changes.
 * One-digit `printf`: the node reads the two characters after the letter, so `echo v0` would
 * store 0 x 10 + ('\n' - '0'). Root, not the car service: the service's copy is stock's with
 * the reset.
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

    /** Auto: the chip's check_pr2000_signal picks the format. */
    private const val AUTO_FORMAT = "v0"

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
    var statusRead: () -> String? = ROOT_STATUS

    /** The car service's decoder once MainActivity binds it; null keeps the root shell. */
    @Volatile
    var service: CarDecoder? = null

    /** True for a status the AIS server can size a stream from. */
    fun isLocked(status: String?): Boolean = status?.trim()?.toIntOrNull() in SIZED_STATUS

    override fun locked(): Boolean = isLocked(statusRead())

    override fun status(): Int? = statusRead()?.trim()?.toIntOrNull()?.takeIf { it in SIZED_STATUS }

    override fun forceStreamable() = lock()

    override fun redetect() = lock()

    private fun lock() {
        shell("setprop $WRITABLE_PROP 1; printf $REVERSE_CHANNEL > $DECODER_NODE; printf $AUTO_FORMAT > $DECODER_NODE")
    }
}
