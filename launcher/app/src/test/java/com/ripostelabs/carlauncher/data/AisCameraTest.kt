package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AIS client call order is stock's: `CameraUtils.openCamera(1)` on reverse
 * (BackcarEvent.java:1309), `setSurface(surface, 0)` once the texture exists
 * (CameraManager.java:63), `closeCamera()` on disengage (BackcarEvent.java:1347) and
 * `deleteSurface(0)` when the texture goes (CameraManager.java:75). The gateway sets its
 * open flag before the call and closes on it regardless of the result (:1296-1298, :1339-1347).
 */
class AisCameraTest {

    private class Fake(
        private val loadError: String? = null,
        private val openResult: Int = AisCamera.OPEN_OK,
        private val frames: Int = 0,
    ) : AisCamera.Backend<String> {
        val calls = mutableListOf<String>()

        override fun load(): String? { calls += "load"; return loadError }
        override fun open(cameraIndex: Int): Int { calls += "open($cameraIndex)"; return openResult }
        override fun setSurface(surface: String, slot: Int) { calls += "setSurface($surface,$slot)" }
        override fun deleteSurface(slot: Int) { calls += "deleteSurface($slot)" }
        override fun close() { calls += "close" }
        override fun frameCount(slot: Int): Int { calls += "frameCount($slot)"; return frames }
    }

    @Test
    fun opensThePr2000ThenAttachesTheSurface() {
        val fake = Fake()
        val camera = AisCamera(fake)

        val state = camera.open("tex")

        assertEquals(listOf("load", "open(1)", "setSurface(tex,0)"), fake.calls)
        assertEquals(AisCamera.State.Streaming, state)
    }

    @Test
    fun closeReleasesTheCameraBeforeTheSurface() {
        val fake = Fake()
        val camera = AisCamera(fake)
        camera.open("tex")
        fake.calls.clear()

        camera.close()

        assertEquals(listOf("close", "deleteSurface(0)"), fake.calls)
        assertEquals(AisCamera.State.Idle, camera.state)
    }

    @Test
    fun missingClientLibFailsWithoutTouchingTheCamera() {
        val fake = Fake(loadError = "dlopen failed: library \"libais_camera.so\" not found")
        val camera = AisCamera(fake)

        val state = camera.open("tex")

        assertTrue(state is AisCamera.State.Failed)
        assertTrue((state as AisCamera.State.Failed).reason.contains("libais_camera.so"))
        assertEquals(listOf("load"), fake.calls)
    }

    @Test
    fun openFailureNamesTheResultAndStillClosesLikeStock() {
        // open_camera returns 0 on success, -1/-3..-7 for the stage that failed
        // (libais_camera.so open_camera, .text 0x2150, 0x2284, 0x24ec..0x2674).
        val fake = Fake(openResult = -5)
        val camera = AisCamera(fake)

        val state = camera.open("tex")
        camera.close()

        assertEquals(AisCamera.State.Failed("open_camera(1) returned -5"), state)
        assertEquals(listOf("load", "open(1)", "close"), fake.calls)
    }

    @Test
    fun closeBeforeOpenIsANoop() {
        val fake = Fake()

        AisCamera(fake).close()

        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun secondOpenIsIgnoredWhileStreaming() {
        val fake = Fake()
        val camera = AisCamera(fake)
        camera.open("tex")
        fake.calls.clear()

        val state = camera.open("other")

        assertEquals(AisCamera.State.Streaming, state)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun framesReadTheSurfaceSlotOnlyWhileStreaming() {
        val fake = Fake(frames = 12)
        val camera = AisCamera(fake)

        assertNull(camera.frames())
        camera.open("tex")
        assertEquals(12, camera.frames())
        assertEquals("frameCount(0)", fake.calls.last())
    }
}
