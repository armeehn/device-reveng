package com.ripostelabs.carlauncher.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import com.ripostelabs.carlauncher.carlib.CanableSource
import com.ripostelabs.carlauncher.carlib.CanableStatus
import com.ripostelabs.carlauncher.carlib.SpeedCalibration
import com.ripostelabs.carlauncher.carlib.McuTrailerTally
import com.ripostelabs.carlauncher.carlib.VehicleState

/**
 * CanCaptureService — keeps the bus recording while nobody is looking at the screen.
 *
 * The capture screen was the only thing that ever started a read, so a capture ended the moment
 * the driver navigated away, and "leave the car and fetch the data later" was not actually true.
 * A drive is exactly when nobody is holding a settings screen open.
 *
 * A foreground service, not a thread: Android 13 stops background work quickly, and this has to
 * survive a whole ignition cycle. The `connectedDevice` type is the honest one — it exists for
 * precisely this, an app talking to attached hardware.
 *
 * The service owns the [CanableSource]; the screen observes the same instance rather than starting
 * a second reader, because two readers on one bulk endpoint would split the byte stream between
 * them. That mistake was already made once on this project against the vendor MCU serial port.
 */
class CanCaptureService : Service() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            NOTIFICATION_ID,
            notification("Starting"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        val source = shared(this)
        source.start()
        Log.i(LOG_TAG, "capture service started")

        // START_STICKY: if the system reclaims us mid-drive, come back and keep recording.
        return START_STICKY
    }

    override fun onDestroy() {
        shared(this).stop()
        Log.i(LOG_TAG, "capture service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "CAN capture", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Recording the vehicle bus to a file"

        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification(text: String): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording CAN bus")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()

    companion object {
        private const val LOG_TAG = "Canable"
        private const val CHANNEL_ID = "can-capture"
        private const val NOTIFICATION_ID = 4711

        private var instance: CanableSource? = null

        /**
         * The one accumulating vehicle snapshot. The raw bus and the MCU both fold into it, so it
         * outlives any screen: a reading that arrived while nobody was looking is still there
         * when someone opens the Vehicle page.
         */
        /**
         * The one speed calibration. MCU candidates reach it through [vehicleState] whenever a
         * screen folds MCU frames; ECU answers reach it from the reader. Both halves have to be
         * alive for a sample to be usable, which is why they share an owner.
         */
        private val calibration = SpeedCalibration()

        private val vehicleState = VehicleState(calibration)

        fun vehicle(): VehicleState = vehicleState

        /**
         * Every MCU body the vendor broadcasts is a free test of McuSerial's wire checksum (its
         * C2). Counted here, logged under the tag the watcher on x already pulls, so the car
         * answers the desk's derivation on the next pull without a port being touched.
         */
        private val mcuTrailer = McuTrailerTally()

        fun onMcuBody(body: ByteArray) {
            if (mcuTrailer.onBody(body) == McuTrailerTally.Report.DUE) {
                Log.i(LOG_TAG, mcuTrailer.summary())
            }
        }

        /** The same line the log gets, for the capture screen: the car's verdict without adb. */
        fun mcuTrailerSummary(): String = mcuTrailer.summary()

        fun calibration(): SpeedCalibration = calibration

        /**
         * The one reader in the process. Shared so the screen and the service never open the
         * device twice — a second claim on the same bulk endpoint splits the stream.
         */
        @Synchronized
        fun shared(context: Context): CanableSource =
            instance ?: CanableSource.create(context, vehicleState, calibration).also { instance = it }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CanCaptureService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CanCaptureService::class.java))
        }

        /** Whether a capture is currently producing data, for the screen to reflect. */
        fun isRecording(): Boolean = instance?.status?.value is CanableStatus.Running
    }
}
