package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CarPlay up → ask the iPhone for its internet over PAN, with backoff while the hotspot is off. */
class PhoneInternetTest {

    private companion object {
        const val IPHONE = "AA:BB:CC:00:00:01"
        const val IPHONE_2 = "AA:BB:CC:00:00:02"
        const val PIXEL = "AA:BB:CC:00:00:03"
    }

    /** A PAN stack that goes CONNECTED after [acceptAfter] connect calls; never, when null. */
    private class FakePan(val peers: List<BtPeer>, val acceptAfter: Int? = 1) : PanLink {
        val connects = mutableListOf<String>()

        override fun bonded() = peers

        override fun state(address: String): PanState {
            val done = acceptAfter != null && connects.count { it == address } >= acceptAfter
            return if (done) PanState.CONNECTED else PanState.DISCONNECTED
        }

        override fun connect(address: String): Boolean {
            connects += address
            return true
        }
    }

    private val pending = ArrayDeque<Pair<Long, () -> Unit>>()
    private val logs = mutableListOf<String>()

    private fun internet(pan: PanLink) = PhoneInternet(pan, schedule = { ms, task -> pending += ms to task }, log = { logs += it })

    /** Run queued checks until none is left; returns the delays they waited. */
    private fun drain(): List<Long> {
        val waited = mutableListOf<Long>()
        while (pending.isNotEmpty()) {
            val (ms, task) = pending.removeFirst()
            waited += ms
            task()
        }
        return waited
    }

    private fun iphone(address: String = IPHONE, acl: Boolean = false) = BtPeer(address, "Sasha's iPhone", acl)
    private val pixel = BtPeer(PIXEL, "Pixel 8", aclConnected = true)

    @Test
    fun carPlayAddressWinsWhenBonded() {
        assertEquals(IPHONE_2, PhoneInternetRules.target(IPHONE_2.lowercase(), listOf(iphone(), iphone(IPHONE_2))))
    }

    @Test
    fun unbondedCarPlayAddressIsNobody() {
        assertNull(PhoneInternetRules.target(IPHONE_2, listOf(iphone())))
    }

    @Test
    fun noAddressFallsBackToTheOneIphone() {
        assertEquals(IPHONE, PhoneInternetRules.target(null, listOf(pixel, iphone())))
    }

    @Test
    fun severalIphonesNarrowToTheLinkedOne() {
        assertEquals(IPHONE_2, PhoneInternetRules.target(null, listOf(iphone(), iphone(IPHONE_2, acl = true))))
        assertNull(PhoneInternetRules.target(null, listOf(iphone(), iphone(IPHONE_2))))
    }

    @Test
    fun noIphoneIsNobody() {
        assertNull(PhoneInternetRules.target(null, listOf(pixel)))
    }

    @Test
    fun sessionUpConnectsAndStopsOnceConnected() {
        val pan = FakePan(listOf(iphone()))
        internet(pan).onSessionUp(null)

        drain()

        assertEquals(listOf(IPHONE), pan.connects)
        assertTrue(logs.last().startsWith("connected"))
    }

    @Test
    fun hotspotOffBacksOffThenGivesUp() {
        val pan = FakePan(listOf(iphone()), acceptAfter = null)
        internet(pan).onSessionUp(IPHONE)

        val waited = drain()

        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L), waited)
        assertEquals(4, pan.connects.size)
        assertTrue(logs.last().contains("hotspot off"))
    }

    @Test
    fun repeatedSessionUpIsIdempotent() {
        val pan = FakePan(listOf(iphone()), acceptAfter = 2)
        val net = internet(pan)
        net.onSessionUp(null)
        net.onSessionUp(null)

        drain()

        assertEquals(2, pan.connects.size)
    }

    @Test
    fun sessionDownStopsRetries() {
        val pan = FakePan(listOf(iphone()), acceptAfter = null)
        val net = internet(pan)
        net.onSessionUp(null)
        net.onSessionDown()

        drain()

        assertEquals(1, pan.connects.size)
    }

    @Test
    fun alreadyConnectedAsksNothing() {
        val pan = object : PanLink {
            override fun bonded() = listOf(iphone())
            override fun state(address: String) = PanState.CONNECTED
            override fun connect(address: String): Boolean = error("must not dial")
        }
        internet(pan).onSessionUp(null)

        assertTrue(pending.isEmpty())
        assertTrue(logs.single().startsWith("connected"))
    }

    @Test
    fun noTargetDoesNothing() {
        val pan = FakePan(listOf(pixel))
        internet(pan).onSessionUp(null)

        assertTrue(pan.connects.isEmpty())
        assertTrue(logs.single().contains("doing nothing"))
    }

    @Test
    fun stateIntsFold() {
        assertEquals(PanState.CONNECTED, PanState.of(2))
        assertEquals(PanState.CONNECTING, PanState.of(1))
        assertEquals(PanState.DISCONNECTED, PanState.of(3))
    }
}
