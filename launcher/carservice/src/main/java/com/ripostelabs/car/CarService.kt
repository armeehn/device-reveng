package com.ripostelabs.car

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.RemoteCallbackList
import android.util.Log

/**
 * The always-on car service (os/CARHAL.md "The car service"). Binding needs CONTROL (manifest);
 * each call is checked again in [CarBinder]. Runs as android.uid.system, so REBOOT is held.
 */
class CarService : Service() {

    private val callbacks = RemoteCallbackList<ICarListener>()

    private val listeners = object : ListenerSet {
        override fun add(listener: ICarListener) {
            callbacks.register(listener)
        }

        override fun remove(listener: ICarListener) {
            callbacks.unregister(listener)
        }
    }

    // reboot(null): a plain restart, no recovery or bootloader reason.
    private val power = Power { getSystemService(PowerManager::class.java).reboot(null) }

    private val binder by lazy { CarBinder(Gate(::held), listeners, power, Process.myUid()) }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "up as uid ${Process.myUid()}")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        callbacks.kill()
        super.onDestroy()
    }

    private fun held(permission: String) =
        checkCallingOrSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "RiposteCar"
    }
}
