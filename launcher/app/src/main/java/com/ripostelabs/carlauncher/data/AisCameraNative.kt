package com.ripostelabs.carlauncher.data

import android.view.Surface

/**
 * AisCameraNative — the JNI edge of `libriposte_ais.so` (app/src/main/cpp/ais_camera.cpp).
 *
 *     AisCamera ──▶ this ──▶ libriposte_ais.so ──dlopen──▶ libais_camera.so ──▶ ais_server
 *                  (APK)     (APK, built here)             (GSI /system/lib64, public.libraries.txt)
 *
 * The vendor client is never linked at build time: it only exists on the unit. [load] brings the
 * shim in and asks it to dlopen the client; every failure comes back as text, never a throw, so
 * the caller can fall back to camera2. Static natives on purpose: the vendor's `setSurface` is a
 * static native too, and `set_surface` takes that `jclass` as its second argument.
 */
internal object AisCameraNative : AisCamera.Backend<Surface> {

    private var shimError: String? = null
    private var shimLoaded = false

    override fun load(): String? {
        if (!shimLoaded) {
            shimError = loadShim()
            shimLoaded = true
        }

        return shimError ?: nativeLoad()
    }

    override fun open(cameraIndex: Int): Int = nativeOpen(cameraIndex)

    override fun setSurface(surface: Surface, slot: Int) = nativeSetSurface(surface, slot)

    override fun deleteSurface(slot: Int) = nativeDeleteSurface(slot)

    override fun close() = nativeClose()

    override fun frameCount(slot: Int): Int = nativeFrameCount(slot)

    /** The APK's own shim; missing only when the ABI has no build of it. */
    private fun loadShim(): String? = try {
        System.loadLibrary(SHIM)
        null
    } catch (e: UnsatisfiedLinkError) {
        e.message ?: "lib$SHIM.so failed to load"
    }

    /** dlopen + dlsym of the client; null on success, the dlerror text otherwise. */
    @JvmStatic
    private external fun nativeLoad(): String?

    @JvmStatic
    private external fun nativeOpen(cameraIndex: Int): Int

    @JvmStatic
    private external fun nativeSetSurface(surface: Surface, slot: Int)

    @JvmStatic
    private external fun nativeDeleteSurface(slot: Int)

    @JvmStatic
    private external fun nativeClose()

    @JvmStatic
    private external fun nativeFrameCount(slot: Int): Int

    private const val SHIM = "riposte_ais"
}
