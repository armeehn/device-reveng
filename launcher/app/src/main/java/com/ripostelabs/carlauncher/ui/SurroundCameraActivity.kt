package com.ripostelabs.carlauncher.ui

import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ripostelabs.carlauncher.data.AisCamera
import com.ripostelabs.carlauncher.data.AisCameraNative
import com.ripostelabs.carlauncher.data.AisCameraWorker
import com.ripostelabs.carlauncher.data.SurroundSignal
import com.ripostelabs.carlauncher.service.CanCaptureService
import com.ripostelabs.carlauncher.ui.theme.CarLauncherTheme
import java.util.concurrent.Executors
import kotlinx.coroutines.delay

/**
 * 360 cameras: the four XS9922B channels in a 2x2 grid (os/CAMERA_360.md).
 *
 *     4 x TextureView ─▶ all four textures up ─▶ worker: open_camera(0), set_surface(s_k, k)
 *     every second    ─▶ per tile: frame count + a 32x18 sample ─▶ SurroundSignal.judge
 *     tap a tile      ─▶ it fills the screen (the others shrink to a sliver, their textures
 *                        stay alive, so no reopen); tap again for the grid
 *     reverse / close ─▶ worker: close_camera, delete the slots, release the surfaces
 *
 * The client library serves one device at a time, and the reverse view needs the PR2000, so
 * the screen leaves as soon as the car is in reverse. Opened from Settings > Reverse camera, or:
 *
 *     adb shell am start -n com.ripostelabs.carlauncher/.ui.SurroundCameraActivity
 */
class SurroundCameraActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val camera = AisCameraWorker(
        AisCamera(AisCameraNative), CLIENT_THREAD, DEADLINE_TIMER, post = { handler.post(it) },
        cameraIndex = AisCamera.SURROUND_CAMERA_INDEX,
    )

    private val views = arrayOfNulls<TextureView>(CHANNELS)
    private val textures = arrayOfNulls<SurfaceTexture>(CHANNELS)
    private var surfaces: List<Surface> = emptyList()

    private val tiles = mutableStateListOf(*Array(CHANNELS) { SurroundSignal.Tile.WAITING })
    private val counts = arrayOfNulls<Int>(CHANNELS)
    private var failure by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val vehicle by CanCaptureService.vehicle().snapshot.collectAsStateWithLifecycle()
            if (vehicle.reverse == true) {
                LaunchedEffect(Unit) { finish() }
            }

            var focused by remember { mutableStateOf<Int?>(null) }

            CarLauncherTheme {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                    Grid(focused) { focused = SurroundSignal.focus(focused, it) }

                    failure?.let { Label(it, Modifier.align(Alignment.Center)) }

                    Button(
                        onClick = { finish() },
                        modifier = Modifier.align(Alignment.TopCenter).padding(CLOSE_INSET_DP.dp),
                    ) {
                        Text("Close")
                    }
                }
            }

            // Poll every tile once a second while the stream is up.
            LaunchedEffect(Unit) {
                while (true) {
                    delay(POLL_MS)
                    poll()
                }
            }
        }
    }

    /** Two rows of two. A focused tile's row and column take the screen; the rest keep a sliver. */
    @Composable
    private fun Grid(focused: Int?, onTap: (Int) -> Unit) {
        fun share(index: Int, focusIndex: Int?): Float =
            if (focusIndex == null || focusIndex == index) FULL_WEIGHT else SLIVER_WEIGHT

        val focusRow = focused?.div(GRID)
        val focusCol = focused?.rem(GRID)

        Column(modifier = Modifier.fillMaxSize()) {
            for (row in 0 until GRID) {
                Row(modifier = Modifier.fillMaxWidth().weight(share(row, focusRow))) {
                    for (col in 0 until GRID) {
                        val index = row * GRID + col
                        Tile(
                            index = index,
                            modifier = Modifier.weight(share(col, if (row == focusRow) focusCol else null))
                                .fillMaxHeight()
                                .clickable { onTap(index) },
                        )
                    }
                }
            }
        }
    }

    /** One channel: its texture at 16:9 on black, its name, and "No signal" when judged so. */
    @Composable
    private fun Tile(index: Int, modifier: Modifier) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { context -> TextureView(context).also { bind(index, it) } },
                modifier = Modifier.aspectRatio(PICTURE_ASPECT),
            )

            Label("Camera ${index + 1}", Modifier.align(Alignment.TopStart).padding(LABEL_INSET_DP.dp))
            if (tiles[index] == SurroundSignal.Tile.NO_SIGNAL) {
                Label("No signal", Modifier.align(Alignment.Center))
            }
        }
    }

    /** White text on a dark chip: the tiles are black or picture, whatever the theme. */
    @Composable
    private fun Label(text: String, modifier: Modifier) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            modifier = modifier.background(Color.Black.copy(alpha = LABEL_ALPHA)).padding(LABEL_PAD_DP.dp),
        )
    }

    private fun bind(index: Int, view: TextureView) {
        views[index] = view
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                textures[index] = texture
                openWhenReady()
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

            // The texture is released with the surfaces in closeCamera, after the client lets go.
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                closeCamera()
                return false
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
        }
    }

    /** Opens once every tile has a texture: the client takes all four slots in one open. */
    private fun openWhenReady() {
        if (surfaces.isNotEmpty() || textures.any { it == null }) {
            return
        }

        surfaces = textures.map { Surface(it) }
        camera.openAll(surfaces) { state ->
            failure = (state as? AisCamera.State.Failed)?.reason
            Log.i(TAG, "360 open: $state")
        }
    }

    private fun poll() {
        if (surfaces.isEmpty()) {
            return
        }

        for (index in 0 until CHANNELS) {
            val flat = views[index]?.getBitmap(SAMPLE_W, SAMPLE_H)?.let { bitmap ->
                val pixels = IntArray(SAMPLE_W * SAMPLE_H)
                bitmap.getPixels(pixels, 0, SAMPLE_W, 0, 0, SAMPLE_W, SAMPLE_H)
                bitmap.recycle()
                SurroundSignal.isFlat(pixels)
            } ?: true

            camera.frames(index) { now ->
                tiles[index] = SurroundSignal.judge(counts[index], now, flat)
                counts[index] = now
            }
        }
    }

    private fun closeCamera() {
        if (surfaces.isEmpty()) {
            return
        }

        val released = surfaces
        surfaces = emptyList()
        textures.fill(null)
        camera.close { released.forEach { it.release() } }
    }

    override fun onDestroy() {
        closeCamera()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val TAG = "SurroundCamera"

        const val CHANNELS = AisCamera.SURROUND_CHANNELS
        const val GRID = 2

        const val FULL_WEIGHT = 1f

        /** Keeps an unfocused tile laid out (weight must be > 0) so its texture survives. */
        const val SLIVER_WEIGHT = 0.001f

        /** The XS9922B streams 1920x1080 or 1280x720 per channel. */
        const val PICTURE_ASPECT = 16f / 9f

        const val POLL_MS = 1_000L
        const val SAMPLE_W = 32
        const val SAMPLE_H = 18

        const val CLOSE_INSET_DP = 16
        const val LABEL_INSET_DP = 8
        const val LABEL_PAD_DP = 6
        const val LABEL_ALPHA = 0.5f

        // One client call at a time per activity, and a daemon so a stuck open never holds the app.
        val CLIENT_THREAD = Executors.newSingleThreadExecutor { Thread(it, "ais-360").apply { isDaemon = true } }
        val DEADLINE_TIMER = Executors.newSingleThreadScheduledExecutor { Thread(it, "ais-360-deadline").apply { isDaemon = true } }
    }
}
