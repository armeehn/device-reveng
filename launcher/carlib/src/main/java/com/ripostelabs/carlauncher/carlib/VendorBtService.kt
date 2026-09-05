package com.ripostelabs.carlauncher.carlib

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * Bound client of btsuite's own binder, `com.szchoiceway.btsuite.BTService` (exported, no
 * permission, `btsuite/AndroidManifest.xml:82-87`). Descriptor `com.szchoiceway.btsuite.IBTService`
 * (`IBTService.java:5`), three transactions (`IBTService.java:34-36`):
 *
 * ```
 *  1  String getContractAddress()
 *  2  void   hideBTFloatWnd()      the in-call floating window, see [hideFloatWnd]
 *  3  void   sendData(String)      raw module command ("DW<num>" dial, "DG" hang up, ...)
 * ```
 *
 * Only [hideFloatWnd] is wired. The parcel is written by hand, mirroring the vendor proxy
 * (`IBTService.java:121-132`), so no AIDL of a vendor interface is committed.
 *
 * ⚠ UNVERIFIED on the car: that a normal uid may bind, and that the hide holds.
 */
class VendorBtService(private val appContext: Context) {

    companion object {
        private const val TAG = "VendorBtService"

        const val BIND_ACTION = "com.szchoiceway.btsuite.BTService"
        const val DESCRIPTOR = "com.szchoiceway.btsuite.IBTService"

        /** `IBTService.java:35`. */
        const val TXN_HIDE_FLOAT_WND = 2
        private const val TXN_FLAGS_SYNC = 0
    }

    @Volatile
    private var binder: IBinder? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service
            Log.i(TAG, "BTService connected: $name")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            binder = null
            Log.w(TAG, "BTService disconnected: $name")
        }
    }

    /** Bind btsuite's service; false when the bind request was rejected. */
    fun bind(): Boolean {
        val intent = Intent(BIND_ACTION).setPackage(VendorBt.PACKAGE)
        return runCatching {
            appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.onFailure { Log.e(TAG, "bind failed", it) }.getOrDefault(false)
    }

    fun unbind() {
        runCatching { appContext.unbindService(connection) }
        binder = null
    }

    /**
     * `BTService.hideBTFloatWnd()` (`BTService.java:1105-1115`): removes the in-call window if
     * it is up and cancels the 1 s re-show poll (message 1005). false when unbound or refused.
     */
    fun hideFloatWnd(): Boolean {
        val remote = binder ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()

        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            val sent = remote.transact(TXN_HIDE_FLOAT_WND, data, reply, TXN_FLAGS_SYNC)
            reply.readException()
            sent
        } catch (t: Throwable) {
            Log.w(TAG, "hideBTFloatWnd failed", t)
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
