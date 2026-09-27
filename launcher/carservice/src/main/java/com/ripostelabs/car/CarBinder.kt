package com.ripostelabs.car

import android.os.RemoteException
import com.ripostelabs.carlauncher.carlib.McuOwner

/** The listeners a client registered; RemoteCallbackList on the unit, a list in tests. */
interface ListenerSet {
    fun add(listener: ICarListener)
    fun remove(listener: ICarListener)

    /** [action] on every listener; one that throws is the caller's to drop. */
    fun each(action: (ICarListener) -> Unit)
}

/** The power actions the service performs; PowerManager on the unit. */
fun interface Power {
    fun reboot()
}

/** The MCU link as the binder drives it: [OwnerHost] around McuOwner on the unit, a fake in tests. */
interface Link {
    fun status(): McuOwner.Status
    fun open()
    fun close()
    fun setStartup(packed: ByteArray)
    fun setSource(mode: Int): Boolean

    /** McuOwnerProtocol.Mode code of the last source set, or [NO_SOURCE]. */
    fun currentSource(): Int
    fun selectCar(id: String)
    fun send(frame: ByteArray)

    companion object {
        const val NO_SOURCE = -1
    }
}

/**
 * ICarService. Every call checks its caller through [gate] before it acts, so a refused call
 * has no side effect. [uid] is the service's own, reported in every status.
 */
class CarBinder(
    private val gate: Gate,
    private val listeners: ListenerSet,
    private val power: Power,
    private val uid: Int,
    private val link: Link,
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

    override fun openLink() {
        gate.enforce(Access.CONTROL)
        link.open()
    }

    override fun closeLink() {
        gate.enforce(Access.CONTROL)
        link.close()
    }

    override fun setStartup(frames: ByteArray?) {
        gate.enforce(Access.CONTROL)
        frames?.let(link::setStartup)
    }

    override fun setSource(mode: Int): Boolean {
        gate.enforce(Access.CONTROL)
        return link.setSource(mode)
    }

    override fun currentSource(): Int {
        gate.enforce(Access.READ)
        return link.currentSource()
    }

    override fun selectCar(carId: String?) {
        gate.enforce(Access.CONTROL)
        carId?.let(link::selectCar)
    }

    override fun sendMcuFrame(frame: ByteArray?) {
        gate.enforce(Access.CONTROL)
        frame?.let(link::send)
    }

    /** One MCU event to every client, in the order the owner saw them. */
    fun publish(event: McuEvent) = broadcast { it.onMcuEvent(event) }

    /** The link's status to every client. */
    fun publishStatus() {
        val status = current()
        broadcast { it.onStatus(status) }
    }

    // A dead client is dropped here; RemoteCallbackList also drops it on its binder death.
    private fun broadcast(action: (ICarListener) -> Unit) {
        val dead = mutableListOf<ICarListener>()
        listeners.each { l ->
            try {
                action(l)
            } catch (e: RemoteException) {
                dead += l
            }
        }
        dead.forEach(listeners::remove)
    }

    private fun current() = CarStatus.of(uid, link.status())

    private companion object {
        // 1 was the skeleton; 2 adds the MCU link calls. Additions to ICarService bump it.
        // The manifest's com.ripostelabs.car.API meta-data must say the same.
        const val API_VERSION = 2
    }
}
