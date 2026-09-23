package com.ripostelabs.carlauncher.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.ripostelabs.carlauncher.carlib.AndroidOwnerGate
import com.ripostelabs.carlauncher.carlib.RadarState
import com.ripostelabs.carlauncher.data.AisCamera
import com.ripostelabs.carlauncher.data.AisCameraNative
import com.ripostelabs.carlauncher.data.ReverseFeedPath
import com.ripostelabs.carlauncher.ui.theme.carShape

/**
 * ReverseCameraScreen — the launcher's own reverse feed, Riposte OS 0.2 only.
 *
 * ── Where this sits ─────────────────────────────────────────────────────────────────────────────
 *
 *     MCU SYS_EVENT 71 ─▶ McuOwner ─▶ CarEvents.reverse ─▶ ReverseCameraGate ─▶ this (ReverseCameraWindow)
 *                                                                                    ▲
 *     ais_server ◀─ libais_camera.so ◀─ AisCamera (car_owner=1) ─┐
 *     camera2 id "1" (any other slot, or no client lib) ─────────┴─▶ TextureView ──┘
 *
 * On 0.2 the feed takes eventcenter's own path, Qualcomm AIS through [AisCamera]: the PR2000
 * sits on no camera2 provider (`legacy/0` is declared, never served), so `Camera.open(1)` and
 * camera2 id "1" only answer on a HAL that lists it. That path stays as the fallback
 * ([ReverseFeedPath]) for a stock slot, the farm and the desk. A [TextureView], not a
 * SurfaceView: it composes like any view, so the label draws over it with no hole-punching, and
 * on 0.2 there is no vendor window above us to yield to (contrast [ReverseOverlay]).
 *
 * Deliberately plain: no guide lines, no controls. One label so the driver can tell the launcher
 * is showing the picture, a one-line reason whenever there is none — permission missing, camera
 * absent, or the [CameraAccessException] reason — and the vendor's two decorations of the feed:
 *
 *   • [radar], the parking-sensor bands the vendor draws as RadarViewUp/RadarViewDown over its
 *     backcar window (BackcarEvent.java:505-506, :960-978), shown while [showRadar] — the
 *     `Sys_BackCar_Display_Radar_Key` toggle, default on (:2189);
 *   • [mirrored], `Sys_Backcar_Camera_Mirroring` → `CameraManager.mirrorLeftRight(true)`
 *     (:1300-1302), done here as a horizontal flip of the texture.
 *
 * The camera is released on dispose, i.e. the moment reverse disengages.
 */
@Composable
fun ReverseCameraScreen(
    verdict: ReverseCameraGate.Verdict,
    radar: RadarState? = null,
    showRadar: Boolean = true,
    mirrored: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (verdict == ReverseCameraGate.Verdict.HIDDEN) {
        return
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        when (verdict) {
            ReverseCameraGate.Verdict.PREVIEW -> CameraPreview(mirrored = mirrored, modifier = Modifier.fillMaxSize())
            ReverseCameraGate.Verdict.NO_PERMISSION -> Notice(NO_PERMISSION_MESSAGE)
            ReverseCameraGate.Verdict.HIDDEN -> Unit
        }

        ReverseLabel(modifier = Modifier.align(Alignment.TopStart).padding(LABEL_INSET_DP.dp))

        // Bottom edge, over the feed: nothing draws until a frame has arrived (showPlaceholder
        // off), and the vendor's toggle hides it outright.
        if (showRadar) {
            RadarView(
                state = radar,
                showPlaceholder = false,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(RADAR_WIDTH_FRACTION)
                    .padding(bottom = LABEL_INSET_DP.dp),
            )
        }
    }
}

/** The camera2 preview. Failures replace the picture with their reason; nothing throws out. */
@Composable
private fun CameraPreview(mirrored: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var failure by remember { mutableStateOf<String?>(null) }
    val session = remember {
        // The OS says whether it owns the car; the client lib says whether AIS can be driven.
        val path = ReverseFeedPath.choose(AndroidOwnerGate(context).ownerEnabled(), AisCameraNative::load)
        Log.i(TAG, "reverse feed path: $path")
        when (path) {
            ReverseFeedPath.AIS -> AisReverseSession { failure = it }
            ReverseFeedPath.CAMERA2 -> ReverseCameraSession(context) { failure = it }
        }
    }

    // Reverse disengaged → this leaves the composition → the camera is handed back.
    DisposableEffect(session) {
        onDispose { session.close() }
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                // A mirror-image feed for a camera mounted the other way round (:1300-1302).
                scaleX = if (mirrored) MIRROR_SCALE else 1f
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                        session.open(texture)
                    }

                    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        session.close()
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
                }
            }
        },
        modifier = modifier,
    )

    failure?.let { Notice(it) }
}

/** One centred line on the black bed. White on purpose: the bed is black in every theme. */
@Composable
private fun Notice(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
    }
}

/** Small themed chip, same recipe as [ReverseOverlay]'s guide-line toggle. */
@Composable
private fun ReverseLabel(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            color = MaterialTheme.colorScheme.surface.copy(alpha = LABEL_ALPHA),
            shape = carShape(50),
        ),
    ) {
        Text(
            text = "REVERSE",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** What the preview drives: the camera2 session or the AIS one, chosen by [ReverseFeedPath]. */
private interface ReverseSession {
    fun open(texture: SurfaceTexture)

    fun close()
}

/**
 * The AIS session: one [AisCamera] over the unit's client library, the texture wrapped as the
 * Surface the client draws into. Failures show as their reason, like the camera2 session.
 *
 *     open(texture) ─▶ Surface(texture) ─▶ AisCamera.open ─▶ [1 s later] frame count logged
 *     close()       ─▶ AisCamera.close ─▶ surface.release
 */
private class AisReverseSession(private val onFailure: (String) -> Unit) : ReverseSession {
    private val handler = Handler(Looper.getMainLooper())
    private val camera = AisCamera(AisCameraNative)
    private var surface: Surface? = null

    override fun open(texture: SurfaceTexture) {
        val target = Surface(texture)
        surface = target

        val state = camera.open(target)
        if (state is AisCamera.State.Failed) {
            Log.w(TAG, state.reason)
            onFailure(state.reason)
            return
        }

        // The only sign of a picture from here: the client's frame counter, the number stock
        // polls for signal detection (CamerasSignalDetection.java:523).
        handler.postDelayed(
            { Log.i(TAG, "AIS frames after $FIRST_FRAME_CHECK_MS ms: ${camera.frames()}") },
            FIRST_FRAME_CHECK_MS,
        )
    }

    override fun close() {
        handler.removeCallbacksAndMessages(null)
        camera.close()

        surface?.release()
        surface = null
    }
}

/**
 * One camera2 session: open → preview session → repeating request, and [close] tears it all
 * down in reverse order. Callbacks run on the main looper; the preview is the only work.
 *
 *     open(texture) ─▶ openCamera ─▶ onOpened ─▶ createCaptureSession ─▶ onConfigured ─▶ setRepeatingRequest
 *                                                                                              │
 *     close() ◀── session.close, device.close, surface.release ◀──────────────────────────────┘
 */
private class ReverseCameraSession(
    private val context: Context,
    private val onFailure: (String) -> Unit,
) : ReverseSession {
    private val handler = Handler(Looper.getMainLooper())
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null

    /** Set by [close]; an [onOpened] that lands afterwards must hand the camera straight back. */
    private var closed = false

    // Lint cannot follow the checkSelfPermission below through a field-held context.
    @SuppressLint("MissingPermission")
    override fun open(texture: SurfaceTexture) {
        closed = false

        val granted = context.checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            report(NO_PERMISSION_MESSAGE)
            return
        }

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val ids = manager.cameraIdList
            if (REVERSE_CAMERA_ID !in ids) {
                report("Camera $REVERSE_CAMERA_ID absent (present: ${ids.joinToString().ifEmpty { "none" }})")
                return
            }

            // The HAL streams at its own sizes; ask for the largest it lists for a texture so the
            // buffer is never a size it refuses. No list at all → keep the view's default.
            val size = previewSize(manager)
            size?.let { texture.setDefaultBufferSize(it.width, it.height) }
            surface = Surface(texture)
            Log.i(TAG, "opening camera $REVERSE_CAMERA_ID (present: ${ids.joinToString()}) preview ${size ?: "default"}")
            manager.openCamera(REVERSE_CAMERA_ID, deviceCallback, handler)
        } catch (e: CameraAccessException) {
            fail(describe(e), e)
        } catch (e: SecurityException) {
            fail(NO_PERMISSION_MESSAGE, e)
        } catch (e: IllegalArgumentException) {
            fail("Camera $REVERSE_CAMERA_ID rejected: ${e.message}", e)
        }
    }

    override fun close() {
        closed = true

        session?.close()
        session = null

        device?.close()
        device = null

        surface?.release()
        surface = null
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (closed) {
                camera.close()
                return
            }

            device = camera
            Log.i(TAG, "camera $REVERSE_CAMERA_ID opened")
            startPreview(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            device = null
            report("Camera disconnected")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            device = null
            report("Camera error $error")
        }
    }

    private fun startPreview(camera: CameraDevice) {
        val target = surface ?: return

        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                .apply { addTarget(target) }
                .build()

            @Suppress("DEPRECATION") // SessionConfiguration buys nothing for one preview surface.
            camera.createCaptureSession(
                listOf(target),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        if (closed) {
                            configured.close()
                            return
                        }

                        session = configured
                        try {
                            configured.setRepeatingRequest(request, null, handler)
                            Log.i(TAG, "preview running")
                        } catch (e: CameraAccessException) {
                            fail(describe(e), e)
                        }
                    }

                    override fun onConfigureFailed(configured: CameraCaptureSession) {
                        report("Preview configuration failed")
                    }
                },
                handler,
            )
        } catch (e: CameraAccessException) {
            fail(describe(e), e)
        }
    }

    private fun previewSize(manager: CameraManager): Size? {
        val map = manager.getCameraCharacteristics(REVERSE_CAMERA_ID)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null

        return map.getOutputSizes(SurfaceTexture::class.java)?.maxByOrNull { it.width * it.height }
    }

    private fun fail(message: String, cause: Exception) {
        Log.w(TAG, message, cause)
        onFailure(message)
    }

    /** What the driver reads on the black bed is also in the log ring pulled later. */
    private fun report(message: String) {
        Log.w(TAG, message)
        onFailure(message)
    }

    private fun describe(e: CameraAccessException): String {
        val reason = when (e.reason) {
            CameraAccessException.CAMERA_DISABLED -> "disabled by policy"
            CameraAccessException.CAMERA_DISCONNECTED -> "disconnected"
            CameraAccessException.CAMERA_ERROR -> "device error"
            CameraAccessException.CAMERA_IN_USE -> "in use"
            CameraAccessException.MAX_CAMERAS_IN_USE -> "too many cameras open"
            else -> "reason ${e.reason}"
        }

        return "Camera unavailable: $reason"
    }
}

private const val TAG = "ReverseCamera"

/** The id AUXCamera opens (`Camera.open(1)`); camera2 addresses the same device by "1". */
private const val REVERSE_CAMERA_ID = "1"

private const val NO_PERMISSION_MESSAGE = "Camera permission not granted"

/** How long after the AIS open the frame counter is read and logged. */
private const val FIRST_FRAME_CHECK_MS = 1000L

/** Chip inset from the screen corner, and its translucency over the feed. */
private const val LABEL_INSET_DP = 16
private const val LABEL_ALPHA = 0.6f

/** Share of the screen width the radar bands take at the bottom of the feed. */
private const val RADAR_WIDTH_FRACTION = 0.5f

/** Horizontal flip of the texture, the mirroring the vendor asks its camera HAL for. */
private const val MIRROR_SCALE = -1f
