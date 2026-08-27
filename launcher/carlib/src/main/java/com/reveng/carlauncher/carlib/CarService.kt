package com.reveng.carlauncher.carlib

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.szchoiceway.eventcenter.ICommunication
import com.szchoiceway.eventcenter.IEventService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CarService — binds the vendor control service (CAR_API §3.1) and exposes a thin,
 * null-safe Kotlin wrapper over the [IEventService] AIDL.
 *
 * Bind target (CAR_API §3.1 / §7):
 *   action  = "com.szchoiceway.eventcenter.EventService"
 *   package = "com.szchoiceway.eventcenter"
 *   service is exported=true, so a normal app can bind. Read-only getters are expected
 *   to work; control side-effects "work best as a system app".
 *
 * ⚠ DESCRIPTOR / ORDINAL CAVEAT: our reconstructed IEventService.aidl declares only a
 * subset of methods and its transaction ordinals almost certainly DO NOT match the real
 * service (see the TODO header in IEventService.aidl). Binding + asInterface() succeed
 * regardless, but any transact() may reach the wrong server method until the AIDL is
 * regenerated from the real decompiled IEventService.java (preserving method order).
 * Guard every call and treat results as unverified.
 */
class CarService(private val appContext: Context) {

    companion object {
        private const val TAG = "CarService"
        const val BIND_ACTION = "com.szchoiceway.eventcenter.EventService"
        const val BIND_PACKAGE = "com.szchoiceway.eventcenter"
    }

    private val _connected = MutableStateFlow(false)
    /** true while the AIDL binder is live. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile
    private var service: IEventService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IEventService.Stub.asInterface(binder)
            _connected.value = service != null
            Log.i(TAG, "EventService connected: $name")
            // Register our callback listener (best-effort; ordinal caveat applies).
            runCatching { service?.addMessageListener(messageListener) }
                .onFailure { Log.w(TAG, "addMessageListener failed", it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _connected.value = false
            Log.w(TAG, "EventService disconnected: $name")
        }
    }

    /** Callback the gateway pushes text status lines into (CAR_API §3.3 protocol). */
    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    private val messageListener = object : ICommunication.Stub() {
        override fun notifyMessage(message: String?) {
            _messages.value = message
            Log.d(TAG, "gateway msg: $message")
        }

        override fun checkIsActive(): Boolean = true
    }

    /** Bind the service. Idempotent-ish; returns false if the bind request was rejected. */
    fun bind(): Boolean {
        val intent = Intent(BIND_ACTION).apply { setPackage(BIND_PACKAGE) }
        return try {
            val ok = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!ok) Log.w(TAG, "bindService returned false (service not found?)")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "bind failed", t)
            false
        }
    }

    fun unbind() {
        runCatching { appContext.unbindService(connection) }
        service = null
        _connected.value = false
    }

    // ---- Thin, guarded convenience wrappers --------------------------------
    // Each returns null / false when unbound or on RemoteException. Remember the
    // ordinal caveat above: values are unverified until the AIDL is corrected.

    fun getValidMode(): Int? = call { getValidMode() }
    fun isBackCarConnected(): Boolean = call { IsBackCarConneted() } ?: false
    fun getRadioFreq(): Int? = call { getRadioFreq() }
    fun getMainVolume(): Int? = call { getMainVolval().toInt() }
    fun getMcuVer(): String? = call { getCanVer() }

    fun sendMode(mode: Int, flag: Boolean) { call { sendMode(mode, flag) } }
    fun sendWheelKey(key: Int) { call { sendWheelKey(key) } }
    fun setMute(mute: Boolean) { call { sendMuteState(mute) } }

    /** Typed SysVar passthrough (alternative to the ContentResolver in [SysVar]). */
    fun getSettingString(key: String, def: String): String? =
        call { getSettingString(key, def) }

    private inline fun <T> call(block: IEventService.() -> T): T? {
        val svc = service ?: run {
            Log.d(TAG, "call while unbound")
            return null
        }
        return try {
            svc.block()
        } catch (t: Throwable) {
            Log.w(TAG, "AIDL call failed", t)
            null
        }
    }
}
