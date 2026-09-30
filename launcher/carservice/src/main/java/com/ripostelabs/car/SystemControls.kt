package com.ripostelabs.car

import android.content.Context
import android.net.wifi.WifiManager
import android.os.LocaleList
import android.util.Log
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/**
 * RAV4-216: the Wi-Fi hotspot and the system language, set as the system uid.
 *
 *   launcher ──setHotspot──▶ CarBinder ──▶ SystemControls ──▶ TetheringManager.startTethering
 *            ──setLanguage─▶            └─────────────────▶ LocalePicker.updateLocales
 *
 * Both are system APIs outside the public SDK, so they are reached by reflection. A
 * platform-signed app is exempt from the hidden API checks, and the system uid holds
 * TETHER_PRIVILEGED and CHANGE_CONFIGURATION. A refusal is logged, never thrown: the launcher
 * reads the state back and the row shows what the system holds.
 */
class SystemControls(private val context: Context) : SystemSettings {

    override fun setHotspot(state: Int) {
        runCatching {
            val manager = context.getSystemService(TETHERING_SERVICE) ?: return
            if (state == ICarService.HOTSPOT_ON) {
                start(manager)
            } else {
                manager.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(manager, TETHERING_WIFI)
            }
        }.onFailure { Log.w(TAG, "hotspot $state refused", it) }
    }

    // startTethering(TetheringRequest, Executor, StartTetheringCallback), Android 11 on.
    private fun start(manager: Any) {
        val builder = Class.forName(REQUEST_BUILDER).getConstructor(Int::class.javaPrimitiveType).newInstance(TETHERING_WIFI)
        val request = builder.javaClass.getMethod("build").invoke(builder)
        val callbackType = Class.forName(START_CALLBACK)
        val callback = Proxy.newProxyInstance(callbackType.classLoader, arrayOf(callbackType)) { proxy, method, args ->
            when (method.name) {
                "onTetheringFailed" -> Log.w(TAG, "hotspot failed: error ${args?.firstOrNull()}").let { null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "hotspot callback"
                else -> null
            }
        }
        manager.javaClass.getMethod("startTethering", request.javaClass, Executor::class.java, callbackType)
            .invoke(manager, request, Executor { it.run() }, callback)
    }

    // WifiManager.isWifiApEnabled is a system API; an unreadable state reads as off.
    override fun hotspotState(): Int {
        val wifi = context.getSystemService(WifiManager::class.java) ?: return ICarService.HOTSPOT_OFF
        val on = runCatching { wifi.javaClass.getMethod("isWifiApEnabled").invoke(wifi) == true }.getOrDefault(false)
        return if (on) ICarService.HOTSPOT_ON else ICarService.HOTSPOT_OFF
    }

    // What Settings' language page calls: the persistent configuration, then a backup mark.
    override fun setLanguage(tag: String) {
        runCatching {
            Class.forName(LOCALE_PICKER).getMethod("updateLocales", LocaleList::class.java)
                .invoke(null, LocaleList.forLanguageTags(tag))
        }.onFailure { Log.w(TAG, "language $tag refused", it) }
    }

    private companion object {
        const val TAG = "SystemControls"

        /** Context.TETHERING_SERVICE and TetheringManager.TETHERING_WIFI, both system APIs. */
        const val TETHERING_SERVICE = "tethering"
        const val TETHERING_WIFI = 0

        const val REQUEST_BUILDER = "android.net.TetheringManager\$TetheringRequest\$Builder"
        const val START_CALLBACK = "android.net.TetheringManager\$StartTetheringCallback"
        const val LOCALE_PICKER = "com.android.internal.app.LocalePicker"
    }
}
