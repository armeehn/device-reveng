package com.ripostelabs.carlauncher.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ripostelabs.carlauncher.ui.theme.CarLauncherTheme

/**
 * Camera test: the reverse picture on demand, with the car out of reverse.
 *
 * The same [ReverseCameraScreen] the reverse overlay draws (AIS on 0.2, camera2 elsewhere), full
 * screen in its own activity, so the feed can be checked at the bench. Close or Back ends it and
 * the screen's dispose hands the camera back. Opened from Settings > Reverse camera, or:
 *
 *     adb shell am start -n com.ripostelabs.carlauncher/.ui.CameraTestActivity
 */
class CameraTestActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            CarLauncherTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    ReverseCameraScreen(verdict = ReverseCameraGate.Verdict.PREVIEW, showRadar = false)

                    Button(
                        onClick = { finish() },
                        modifier = Modifier.align(Alignment.TopEnd).padding(CLOSE_INSET_DP.dp),
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }

    private companion object {
        const val CLOSE_INSET_DP = 16
    }
}
