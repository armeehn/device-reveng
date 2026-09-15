package com.ripostelabs.carlauncher.carlib

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SlcanLinkSourceTest {

    /** Reads block on a queue; [close] ends the stream. */
    private class FakeLink : McuLink {
        private val inbound = LinkedBlockingQueue<ByteArray>()

        @Volatile
        var closed = false

        /** Every line the launcher transmitted, as slcan text. */
        val written = java.util.concurrent.CopyOnWriteArrayList<String>()

        fun feed(text: String) = inbound.put(text.toByteArray(Charsets.US_ASCII))

        override fun read(buffer: ByteArray): Int {
            while (!closed) {
                val next = inbound.poll(10, TimeUnit.MILLISECONDS) ?: continue
                next.copyInto(buffer)
                return next.size
            }
            return -1
        }

        override fun write(bytes: ByteArray) {
            written += bytes.toString(Charsets.US_ASCII)
        }

        override fun close() {
            closed = true
        }
    }

    private fun await(deadlineMs: Long = 2_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + deadlineMs
        while (!condition() && System.currentTimeMillis() < end) {
            Thread.sleep(5)
        }
        assertTrue("condition not met within ${deadlineMs}ms", condition())
    }

    @Test
    fun slcanSpeedFrameLandsInTheSnapshot() {
        val link = FakeLink()
        val vehicle = VehicleState()
        val source = SlcanLinkSource({ link }, vehicle, clock = { 1_000L })

        source.start()
        // 0x361 byte 6 is km/h (RawCanDecoder.ID_SPEED_FAST); 0x2B = 43 km/h.
        link.feed("t36180027560056002B7B\r")
        await { vehicle.snapshot.value.speedKmh == 43.0 }
        assertEquals(SlcanLinkSource.Status.Running(frames = 1, unparsed = 0), source.status.value)

        source.stop()
        assertEquals(SlcanLinkSource.Status.Idle, source.status.value)
    }

    @Test
    fun junkIsCountedNotDropped() {
        val link = FakeLink()
        val source = SlcanLinkSource({ link }, VehicleState())

        source.start()
        link.feed("hello\r")
        await { source.status.value == SlcanLinkSource.Status.Running(frames = 0, unparsed = 1) }
        source.stop()
    }

    @Test
    fun openFailureIsReported() {
        val source = SlcanLinkSource({ throw java.io.IOException("/dev/nope does not exist") }, VehicleState())
        source.start()
        assertEquals(SlcanLinkSource.Status.Failed("/dev/nope does not exist"), source.status.value)
    }

    @Test
    fun linkClosingUnderneathIsAFailure() {
        val link = FakeLink()
        val source = SlcanLinkSource({ link }, VehicleState())
        source.start()
        link.close()
        await { source.status.value == SlcanLinkSource.Status.Failed("link closed") }
    }

    @Test
    fun obdCoolantReplyLandsInTheSnapshot() {
        val link = FakeLink()
        val vehicle = VehicleState()
        val source = SlcanLinkSource({ link }, vehicle, clock = { 1_000L })

        source.start()
        // 7E8 # 03 41 05 72: service 01 PID 05, A = 0x72 = 114, so 114 - 40 = 74 C.
        link.feed("t7E880341057200000000\r")
        await { vehicle.snapshot.value.raw(VehicleSnapshot.Field.OBD_COOLANT_C, 1_000L) == 74.0 }
        source.stop()
    }

    @Test
    fun aRequestGoesOutWhileTheBusIsAlive() {
        val link = FakeLink()
        val source = SlcanLinkSource({ link }, VehicleState(), clock = { 1_000L })

        source.start()
        link.feed("t36180027560056002B7B\r")
        // Functional request to 0x7DF, service 01, one PID, padded to 8 bytes.
        await { link.written.any { it.startsWith("t7DF80201") } }
        source.stop()
    }

    @Test
    fun aSilentBusIsNotPolled() {
        val link = FakeLink()
        val source = SlcanLinkSource({ link }, VehicleState(), clock = { 1_000L })

        source.start()
        Thread.sleep(100)
        assertTrue(link.written.isEmpty())
        source.stop()
    }
}
