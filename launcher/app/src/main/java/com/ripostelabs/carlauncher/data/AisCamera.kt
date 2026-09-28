package com.ripostelabs.carlauncher.data

import android.util.Log

/**
 * AisCamera — the reverse feed the way stock draws it, for Riposte OS 0.2.
 *
 * The PR2000 decoder never appears on the Android camera HAL: the vendor manifest declares
 * `ICameraProvider legacy/0` and nothing serves it. eventcenter draws the picture through
 * Qualcomm AIS instead, and this class replays its calls, in its order:
 *
 *     open(surface) ─▶ backend.load ─▶ backend.open(1) ─▶ backend.setSurface(surface, 0) ─▶ Streaming
 *     close()       ─▶ backend.close ─▶ backend.deleteSurface(0) ─▶ Idle
 *
 * Stock (eventcenter): `CameraUtils.openCamera(1)` on reverse, index 1 = `CCAM_MIPI_DEV_PR2000`
 * (BackcarEvent.java:1309; CameraUtils.java:11), `CameraUtils.setSurface(surface, 0)` once the
 * TextureView has a texture (CameraManager.java:63), `CameraUtils.closeCamera()` on disengage
 * (BackcarEvent.java:1347) and `deleteSurface(0)` when the texture goes (CameraManager.java:75).
 * The gateway raises its open flag before the call and closes on that flag whatever the result
 * (:1296-1298, :1339-1347), so a failed open is still closed here. `libcamera_utils.so` is a
 * dlopen shim over `libais_camera.so` (.rodata 0x1af1: "libais_camera.so", "dlsym open_camera"
 * ...) whose trampolines pass the JNI arguments through untouched (setSurface, .text 0x121c:
 * `br set_surface`), so [Backend] is that library's exported contract, not the shim's.
 *
 * The 360 cameras take the same path on the other decoder: [openAll] opens device 0, the
 * 4-channel XS9922B, and puts one surface on each channel's slot (os/CAMERA_360.md):
 *
 *     openAll([s0..s3], 0) ─▶ backend.open(0) ─▶ setSurface(s0, 0) .. setSurface(s3, 3) ─▶ Streaming
 *
 * [S] is the surface handle: `android.view.Surface` in the app, anything on the JVM.
 */
class AisCamera<S : Any>(private val backend: Backend<S>) {

    /**
     * The `libais_camera.so` exports (nm -D: open_camera 0x20e8, close_camera 0x2d78,
     * set_surface 0x2f8c, delete_surface 0x3180, get_frame_count 0x32d0).
     */
    interface Backend<S> {
        /** Load the client library; null when it is in, else the loader's reason. */
        fun load(): String?

        /** `int open_camera(int cam_idx)`: [OPEN_OK], or a negative stage code (.text 0x2284, 0x24ec..0x2674). */
        fun open(cameraIndex: Int): Int

        /** `void set_surface(JNIEnv*, jclass, jobject surface, int index)`, index < 5 (.text 0x2fe0). */
        fun setSurface(surface: S, slot: Int)

        /** `void delete_surface(int index)`. */
        fun deleteSurface(slot: Int)

        /** `void close_camera(void)`: stops the stream and deinits the carcam client. */
        fun close()

        /** `int get_frame_count(int index)`: frames drawn into that surface slot so far. */
        fun frameCount(slot: Int): Int
    }

    sealed class State {
        object Idle : State()
        object Streaming : State()
        data class Failed(val reason: String) : State()
    }

    var state: State = State.Idle
        private set

    /** True after a [Backend.open] call, whatever it returned: stock closes on that flag, not on success. */
    private var opened = false

    /** The slots [openAll] drew into, deleted by [close]. */
    private var attached: List<Int> = emptyList()

    fun open(surface: S, cameraIndex: Int = REVERSE_CAMERA_INDEX): State = openAll(listOf(surface), cameraIndex)

    /** Opens [cameraIndex] and puts surface k on slot k: one slot per decoder channel. */
    fun openAll(surfaces: List<S>, cameraIndex: Int): State {
        if (state == State.Streaming) {
            return state
        }

        if (surfaces.isEmpty() || surfaces.size > MAX_SLOTS) {
            return fail("${surfaces.size} surfaces: the client draws into 1..$MAX_SLOTS slots")
        }

        synchronized(CLIENT) {
            return takeClient(surfaces, cameraIndex)
        }
    }

    /**
     * The client serves one device per process. A second session (reverse over the 360 view)
     * first closes the one holding it, so reverse always gets the PR2000; the old session's
     * own close then finds nothing open and leaves the new stream alone.
     */
    private fun takeClient(surfaces: List<S>, cameraIndex: Int): State {
        val previous = holder
        if (previous != null && previous !== this) {
            Log.i(TAG, "another session holds the client: closing it first")
            previous.close()
        }
        holder = this

        val loadError = backend.load()
        if (loadError != null) {
            Log.i(TAG, "dlopen failed($loadError)")
            return fail("AIS client library unavailable: $loadError")
        }
        Log.i(TAG, "dlopen ok")

        opened = true
        val result = backend.open(cameraIndex)
        Log.i(TAG, "open_camera($cameraIndex) returned $result")
        if (result != OPEN_OK) {
            return fail("open_camera($cameraIndex) returned $result")
        }

        surfaces.forEachIndexed { slot, surface -> backend.setSurface(surface, slot) }
        attached = surfaces.indices.toList()
        Log.i(TAG, "surfaces set on slots $attached")

        state = State.Streaming
        return state
    }

    fun close() = synchronized(CLIENT) {
        if (holder === this) {
            holder = null
        }

        if (opened) {
            backend.close()
            Log.i(TAG, "close_camera done")
        }
        opened = false

        attached.forEach { backend.deleteSurface(it) }
        if (attached.isNotEmpty()) {
            Log.i(TAG, "surface slots $attached deleted")
        }
        attached = emptyList()

        state = State.Idle
    }

    /** Frames drawn so far, null when nothing streams. Stock polls it for signal detection (CamerasSignalDetection.java:523). */
    fun frames(slot: Int = SURFACE_SLOT): Int? {
        if (state != State.Streaming) {
            return null
        }

        return backend.frameCount(slot)
    }

    private fun fail(reason: String): State {
        state = State.Failed(reason)
        return state
    }

    companion object {
        private const val TAG = "AisCamera"

        /** Guards [holder] and orders client calls made from different sessions' threads. */
        private val CLIENT = Any()

        /** The session whose device the client has open, if any. */
        private var holder: AisCamera<*>? = null

        /** `CCAM_MIPI_DEV_PR2000` (CameraUtils.java:11), the index the gateway opens on this board (BackcarEvent.java:1309). */
        const val REVERSE_CAMERA_INDEX = 1

        /** The surface slot the gateway draws into on a PR2000 board (CameraManager.java:63, 75). */
        const val SURFACE_SLOT = 0

        /** `CCAM_MIPI_DEV_XS9922B` (CameraUtils.java:11): the 4-channel decoder behind the 360 cameras. */
        const val SURROUND_CAMERA_INDEX = 0

        /** The XS9922B's channels, qcarcam inputs 0-3 (ais_camera_config.xml inputMapping). */
        const val SURROUND_CHANNELS = 4

        /** `set_surface` refuses an index of 5 or more (.text 0x2fe0). */
        const val MAX_SLOTS = 5

        /** `open_camera` returns 0 when the stream is up, or was already (.text 0x2150, 0x2780). */
        const val OPEN_OK = 0
    }
}
