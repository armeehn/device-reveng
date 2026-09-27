package com.ripostelabs.car

import android.os.RemoteException

/** The listeners a client registered; RemoteCallbackList on the unit, a list in tests. */
interface ListenerSet {
    fun add(listener: ICarListener)
    fun remove(listener: ICarListener)
}

/** The power actions the service performs; PowerManager on the unit. */
fun interface Power {
    fun reboot()
}

/**
 * ICarService, first slice. Every call checks its caller through [gate] before it acts, so a
 * refused call has no side effect. [uid] is the service's own, reported in every status.
 */
class CarBinder(
    private val gate: Gate,
    private val listeners: ListenerSet,
    private val power: Power,
    private val uid: Int,
) : ICarService.Stub() {

    override fun apiVersion() = API_VERSION

    override fun status(): CarStatus {
        gate.enforce(Access.READ)
        return current()
    }

    // A new listener gets the current status at once, so it never waits for the next change.
    override fun registerListener(listener: ICarListener?) {
        gate.enforce(Access.READ)
        if (listener == null) {
            return
        }

        listeners.add(listener)
        try {
            listener.onStatus(current())
        } catch (e: RemoteException) {
            listeners.remove(listener)
        }
    }

    override fun unregisterListener(listener: ICarListener?) {
        gate.enforce(Access.READ)
        if (listener == null) {
            return
        }
        listeners.remove(listener)
    }

    override fun reboot() {
        gate.enforce(Access.CONTROL)
        power.reboot()
    }

    // No MCU owner in the service yet (RAV4-133), so the link is always down.
    private fun current() = CarStatus(mcuLinkUp = false, uid = uid)

    private companion object {
        // 1 for Riposte OS 0.3; additions to ICarService bump it.
        const val API_VERSION = 1
    }
}
