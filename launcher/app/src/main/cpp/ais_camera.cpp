// ais_camera.cpp — libriposte_ais.so, the launcher's port of the vendor's libcamera_utils.so.
//
//     AisCameraNative (Kotlin) ──▶ Java_..._AisCameraNative_native* ──▶ dlsym'd libais_camera.so
//                                                                       (open_camera, set_surface,
//                                                                        delete_surface, close_camera,
//                                                                        get_frame_count)
//
// The vendor shim dlopens "libais_camera.so" in JNI_OnLoad and dlsyms the same five names
// (libcamera_utils.so .rodata 0x1af1-0x1c8f: "libais_camera.so", "dlopen error %s",
// "dlsym open_camera error %s" ...). Its JNI trampolines forward the arguments untouched:
// setSurface (.text 0x121c) is `br set_surface` with (env, clazz, surface, index) still in
// x0-x3; deleteSurface/getFrameCount (.text 0x122c/0x1240) move the int down and branch;
// openCamera (.text 0x13c4) calls open_camera(index); closeCamera (.text 0x13ec) calls
// close_camera() with no arguments. The client is never linked: it only exists on the unit,
// so a desk or farm build loads this shim and gets a dlerror back, and the caller falls back.
#include <jni.h>

#include <dlfcn.h>

#include <android/log.h>

namespace {

constexpr const char* kTag = "AisCamera";
constexpr const char* kClientLib = "libais_camera.so";

// libais_camera.so exports (nm -D): the argument shapes come from its own prologues, e.g.
// set_surface (.text 0x2f8c) keeps x2 as the jobject and w3 as the slot, then calls
// android_view_Surface_getSurface(env, jobject).
using OpenCameraFn = int (*)(int camIdx);
using CloseCameraFn = void (*)();
using SetSurfaceFn = void (*)(JNIEnv* env, jclass clazz, jobject surface, int index);
using DeleteSurfaceFn = void (*)(int index);
using GetFrameCountFn = int (*)(int index);

struct Client {
    void* handle = nullptr;
    OpenCameraFn openCamera = nullptr;
    CloseCameraFn closeCamera = nullptr;
    SetSurfaceFn setSurface = nullptr;
    DeleteSurfaceFn deleteSurface = nullptr;
    GetFrameCountFn getFrameCount = nullptr;
};

Client gClient;

void logInfo(const char* fmt, const char* detail) {
    __android_log_print(ANDROID_LOG_INFO, kTag, fmt, detail);
}

// One symbol, or the reason it is missing. dlerror() is per-thread and cleared by the next call.
template <typename Fn>
const char* resolve(const char* name, Fn* out) {
    *out = reinterpret_cast<Fn>(dlsym(gClient.handle, name));
    if (*out != nullptr) {
        return nullptr;
    }

    const char* err = dlerror();
    return err != nullptr ? err : "dlsym returned null";
}

// dlopen the vendor client and bind the five entry points. Idempotent: a second call answers
// from the first. Returns nullptr on success, else the dlerror text (owned by libc).
const char* loadClient() {
    if (gClient.handle != nullptr) {
        return nullptr;
    }

    void* handle = dlopen(kClientLib, RTLD_NOW);
    if (handle == nullptr) {
        const char* err = dlerror();
        return err != nullptr ? err : "dlopen returned null";
    }
    gClient.handle = handle;

    const char* err = nullptr;
    if ((err = resolve("open_camera", &gClient.openCamera)) != nullptr ||
        (err = resolve("close_camera", &gClient.closeCamera)) != nullptr ||
        (err = resolve("set_surface", &gClient.setSurface)) != nullptr ||
        (err = resolve("delete_surface", &gClient.deleteSurface)) != nullptr ||
        (err = resolve("get_frame_count", &gClient.getFrameCount)) != nullptr) {
        dlclose(handle);
        gClient = Client{};
        return err;
    }

    return nullptr;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_ripostelabs_carlauncher_data_AisCameraNative_nativeLoad(JNIEnv* env, jclass) {
    const char* err = loadClient();
    if (err != nullptr) {
        logInfo("dlopen failed(%s)", err);
        return env->NewStringUTF(err);
    }

    logInfo("dlopen ok: %s bound", kClientLib);
    return nullptr;
}

JNIEXPORT jint JNICALL
Java_com_ripostelabs_carlauncher_data_AisCameraNative_nativeOpen(JNIEnv*, jclass, jint cameraIndex) {
    if (gClient.openCamera == nullptr) {
        return -1;
    }

    return gClient.openCamera(cameraIndex);
}

JNIEXPORT void JNICALL
Java_com_ripostelabs_carlauncher_data_AisCameraNative_nativeSetSurface(JNIEnv* env, jclass clazz,
                                                                       jobject surface, jint slot) {
    if (gClient.setSurface == nullptr) {
        return;
    }

    // The vendor passes its own JNIEnv and jclass straight through; the client only uses env.
    gClient.setSurface(env, clazz, surface, slot);
}

JNIEXPORT void JNICALL
Java_com_ripostelabs_carlauncher_data_AisCameraNative_nativeDeleteSurface(JNIEnv*, jclass, jint slot) {
    if (gClient.deleteSurface == nullptr) {
        return;
    }

    gClient.deleteSurface(slot);
}

JNIEXPORT void JNICALL
Java_com_ripostelabs_carlauncher_data_AisCameraNative_nativeClose(JNIEnv*, jclass) {
    if (gClient.closeCamera == nullptr) {
        return;
    }

    gClient.closeCamera();
}

JNIEXPORT jint JNICALL
Java_com_ripostelabs_carlauncher_data_AisCameraNative_nativeFrameCount(JNIEnv*, jclass, jint slot) {
    if (gClient.getFrameCount == nullptr) {
        return 0;
    }

    return gClient.getFrameCount(slot);
}

}  // extern "C"
