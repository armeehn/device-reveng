package com.ripostelabs.carlauncher.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ripostelabs.carlauncher.service.CanCaptureService
import com.ripostelabs.carlauncher.ui.theme.CarLauncherTheme

/**
 * Camera test: the reverse picture on demand, with the car out of reverse.
 *
 * The same [ReverseCameraScreen] the reverse overlay draws (AIS on 0.2, camera2 elsewhere), full
 * screen in its own activity, so the feed can be checked at the bench. Close or Back ends it and
 * the screen's dispose hands the camera back. The guide lines are drawn over it too, following
 * the CAN box's steering angle, so they can be checked against the real picture on the bench.
 * Opened from Settings > Reverse camera, or:
 *
 *     adb shell am start -n com.ripostelabs.carlauncher/.ui.CameraTestActivity
 *     adb shell am start -n com.ripostelabs.carlauncher/.ui.CameraTestActivity --ef steer_deg 200
 *
 * `steer_deg` stands in for the steering angle (bench and emulator, no CAN box): a preview of
 * the dynamic lines at that angle.
 */
class CameraTestActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val previewSteer = if (intent.hasExtra(EXTRA_STEER_DEG)) intent.getFloatExtra(EXTRA_STEER_DEG, 0f).toDouble() else null

        setContent {
            val vehicle by CanCaptureService.vehicle().snapshot.collectAsStateWithLifecycle()

            CarLauncherTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    ReverseCameraScreen(verdict = ReverseCameraGate.Verdict.PREVIEW, showRadar = false)
                    ReverseOverlay(
                        visible = true,
                        steeringDeg = previewSteer ?: vehicle.steeringDeg,
                        modifier = Modifier.fillMaxSize(),
                    )

                    Button(
                        onClick = { finish() },
                        // Top centre: the overlay's guide-line chip holds the top-end corner.
                        modifier = Modifier.align(Alignment.TopCenter).padding(CLOSE_INSET_DP.dp),
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }

    private companion object {
        const val CLOSE_INSET_DP = 16
        const val EXTRA_STEER_DEG = "steer_deg"
    }
}
