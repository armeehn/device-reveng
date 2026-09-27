package com.ripostelabs.carlauncher.carlib

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.ripostelabs.car.ICarService
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * [CarBinding] over bindService. BIND_AUTO_CREATE keeps the binding: after the service process
 * dies (binderDied) the system calls onServiceDisconnected, restarts the persistent service and
 * calls onServiceConnected again. onBindingDied (package replaced) needs a fresh bind, done here.
 * Callbacks run on [executor], so the replay's binder calls never hold the main thread.
 */
class ServiceCarBinding(
    private val context: Context,
    private val executor: Executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "car-binding").apply { isDaemon = true }
    },
) : CarBinding {

    private var connection: ServiceConnection? = null

    override fun bind(onUp: (ICarService) -> Unit, onDown: () -> Unit) {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                binder?.let { onUp(ICarService.Stub.asInterface(it)) }
            }

            override fun onServiceDisconnected(name: ComponentName?) = onDown()

            override fun onBindingDied(name: ComponentName?) {
                onDown()
                unbind()
                bind(onUp, onDown)
            }

            override fun onNullBinding(name: ComponentName?) {
                Log.w(TAG, "car service returned no binder")
            }
        }

        connection = conn
        val ok = context.bindService(intent(), Context.BIND_AUTO_CREATE, executor, conn)
        if (!ok) {
            Log.w(TAG, "bindService refused ${COMPONENT.flattenToShortString()}")
        }
    }

    override fun unbind() {
        val conn = connection ?: return
        connection = null
        runCatching { context.unbindService(conn) }
    }

    private fun intent() = Intent().setComponent(COMPONENT)

    companion object {
        private const val TAG = "ServiceCarBinding"
        private const val PACKAGE = "com.ripostelabs.car"
        private const val SERVICE = "com.ripostelabs.car.CarService"

        /** Manifest meta-data on the service: its ICarService.apiVersion, readable without binding. */
        private const val API_META = "com.ripostelabs.car.API"

        private val COMPONENT = ComponentName(PACKAGE, SERVICE)

        /** The installed car service's API level, or null on an image without it. */
        fun installedApi(context: Context): Int? = try {
            val flags = PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            context.packageManager.getServiceInfo(COMPONENT, flags).metaData?.getInt(API_META)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}
