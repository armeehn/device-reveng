package com.ripostelabs.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CarBinderTest {

    private val systemUid = 1000

    private class FakeListeners : ListenerSet {
        val held = mutableListOf<ICarListener>()
        override fun add(listener: ICarListener) { held += listener }
        override fun remove(listener: ICarListener) { held -= listener }
    }

    private class FakeListener : ICarListener.Stub() {
        val seen = mutableListOf<CarStatus>()
        override fun onStatus(status: CarStatus) { seen += status }
    }

    private var reboots = 0
    private val listeners = FakeListeners()

    private fun binder(vararg held: String) =
        CarBinder(Gate { it in held }, listeners, { reboots++ }, systemUid)

    @Test
    fun apiVersionIsOne() {
        assertEquals(1, binder().apiVersion())
    }

    @Test
    fun statusCarriesOwnUidAndNoMcuLinkYet() {
        assertEquals(CarStatus(mcuLinkUp = false, uid = systemUid), binder(READ_PERMISSION).status())
    }

    @Test
    fun statusNeedsRead() {
        assertThrows(SecurityException::class.java) { binder().status() }
    }

    @Test
    fun registerAddsAndPushesStatusOnce() {
        val l = FakeListener()
        binder(READ_PERMISSION).registerListener(l)

        assertEquals(listOf<ICarListener>(l), listeners.held)
        assertEquals(listOf(CarStatus(mcuLinkUp = false, uid = systemUid)), l.seen)
    }

    @Test
    fun unregisterRemoves() {
        val b = binder(READ_PERMISSION)
        val l = FakeListener()
        b.registerListener(l)
        b.unregisterListener(l)

        assertTrue(listeners.held.isEmpty())
    }

    @Test
    fun registerNeedsRead() {
        assertThrows(SecurityException::class.java) { binder().registerListener(FakeListener()) }
        assertTrue(listeners.held.isEmpty())
    }

    @Test
    fun rebootWithControlReboots() {
        binder(CONTROL_PERMISSION).reboot()
        assertEquals(1, reboots)
    }

    @Test
    fun rebootWithReadOnlyIsRefusedBeforePower() {
        assertThrows(SecurityException::class.java) { binder(READ_PERMISSION).reboot() }
        assertEquals(0, reboots)
    }
}
