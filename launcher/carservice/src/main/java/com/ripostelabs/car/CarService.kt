package com.ripostelabs.car

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.RecoverySystem
import android.os.RemoteCallbackList
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The always-on car service (os/CARHAL.md "The car service"). Binding needs CONTROL (manifest);
 * each call is checked again in [CarBinder]. Runs as android.uid.system, so REBOOT, MASTER_CLEAR
 * and RECOVERY are held.
 * Hosts the image's one MCU owner ([OwnerHost]) from onCreate: persistent, so from boot on. The
 * reverse line and the PR2000 decoder live here too ([SysfsDecoder]); the picture does not.
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

        // One broadcast at a time: the owner's reader, the CAN box timer and the status
        // collector all publish, and RemoteCallbackList refuses a nested beginBroadcast.
        @Synchronized
        override fun each(action: (ICarListener) -> Unit) {
            val n = callbacks.beginBroadcast()
            try {
                for (i in 0 until n) {
                    action(callbacks.getBroadcastItem(i))
                }
            } finally {
                callbacks.finishBroadcast()
            }
        }
    }

    private val power = object : Power {
        // reboot(null): a plain restart, no recovery or bootloader reason.
        override fun reboot() = getSystemService(PowerManager::class.java).reboot(null)

        // What Settings' own reset ends in (MasterClearReceiver): MASTER_CLEAR + RECOVERY, held
        // as system uid. Blocks this binder thread until the reboot takes the process.
        override fun wipeData() = RecoverySystem.rebootWipeUserData(this@CarService)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var host: OwnerHost

    private val nav: NavPanel by lazy { NavWindow(this) { binder.navTouched() } }

    private val binder: CarBinder by lazy { CarBinder(Gate(::held), listeners, power, Process.myUid(), host, SysfsDecoder(), nav) }

    // The vendor IEventService subset over the same owner, served by EventCompatService.
    private val events: EventCalls by lazy { EventCalls(Gate(::held), host, power, binder::reversing) { Log.w(TAG, it) } }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "up as uid ${Process.myUid()}")

        host = OwnerHost(this) {
            binder.publish(it)
            events.observe(it)
        }
        host.open()
        EventHub.calls = events

        // Clients hear state changes at once and the frame counters about once a second.
        val pacer = StatusPacer()
        scope.launch {
            host.statusFlow.collect { status ->
                if (pacer.due(status, SystemClock.elapsedRealtime())) {
                    binder.publishStatus()
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        EventHub.calls = null
        scope.cancel()
        host.close()
        callbacks.kill()
        super.onDestroy()
    }

    private fun held(permission: String) =
        checkCallingOrSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "RiposteCar"
    }
}
