package com.ripostelabs.carlauncher.data

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `open_camera` blocks while `ais_server` is absent (2026-09-24: the server never linked and
 * the launcher froze on reverse, the call ran on the main thread). The worker keeps the caller
 * free, answers with a failure at the deadline, and runs close behind a stuck open.
 */
class AisCameraWorkerTest {

    /** A backend whose open waits on [gate]: the absent server. */
    private class Stuck : AisCamera.Backend<String> {
        val gate = CountDownLatch(1)
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun load(): String? { calls += "load"; return null }
        override fun open(cameraIndex: Int): Int { gate.await(); calls += "open"; return AisCamera.OPEN_OK }
        override fun setSurface(surface: String, slot: Int) { calls += "setSurface" }
        override fun deleteSurface(slot: Int) { calls += "deleteSurface" }
        override fun close() { calls += "close" }
        override fun frameCount(slot: Int): Int = 0
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val results: MutableList<AisCamera.State> = Collections.synchronizedList(mutableListOf())

    @After
    fun stop() {
        worker.shutdownNow()
        timer.shutdownNow()
    }

    private fun subject(backend: Stuck) =
        AisCameraWorker(AisCamera(backend), worker, timer, post = { it() }, openTimeoutMs = DEADLINE_MS)

    @Test
    fun openReturnsAtOnceAndFailsAtTheDeadline() {
        val backend = Stuck()
        val subject = subject(backend)

        val started = System.nanoTime()
        subject.open("tex") { results += it }
        val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertTrue("open held the caller for $tookMs ms", tookMs < DEADLINE_MS)
        waitFor { results.isNotEmpty() }
        assertEquals(AisCamera.State.Failed(AisCameraWorker.NOT_ANSWERING), results[0])
        backend.gate.countDown()
    }

    @Test
    fun aLateServerStillGivesThePicture() {
        val backend = Stuck()
        val subject = subject(backend)

        subject.open("tex") { results += it }
        waitFor { results.isNotEmpty() }
        backend.gate.countDown()

        waitFor { results.size == 2 }
        assertEquals(AisCamera.State.Streaming, results[1])
    }

    @Test
    fun closeRunsBehindAStuckOpenAndReleasesLast() {
        val backend = Stuck()
        val subject = subject(backend)
        val released = CountDownLatch(1)

        subject.open("tex") { results += it }
        subject.close { backend.calls += "release"; released.countDown() }
        backend.gate.countDown()

        assertTrue(released.await(WAIT_MS, TimeUnit.MILLISECONDS))
        assertEquals(listOf("load", "open", "setSurface", "close", "deleteSurface", "release"), backend.calls)
    }

    @Test
    fun anAnswerAfterCloseIsDropped() {
        val backend = Stuck()
        val subject = subject(backend)
        val released = CountDownLatch(1)

        subject.open("tex") { results += it }
        subject.close { released.countDown() }
        backend.gate.countDown()

        assertTrue(released.await(WAIT_MS, TimeUnit.MILLISECONDS))
        Thread.sleep(DEADLINE_MS * 2)
        assertEquals(emptyList<AisCamera.State>(), results.toList())
    }

    /** Stream start fails first, as it did while the PR2000 relocked after a server start. */
    private class FailsFirst : AisCamera.Backend<String> {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private var opens = 0

        override fun load(): String? = null
        override fun open(cameraIndex: Int): Int {
            opens++
            calls += "open"
            return if (opens == 1) STREAM_FAILED else AisCamera.OPEN_OK
        }
        override fun setSurface(surface: String, slot: Int) { calls += "setSurface" }
        override fun deleteSurface(slot: Int) { calls += "deleteSurface" }
        override fun close() { calls += "close" }
        override fun frameCount(slot: Int): Int = 0
    }

    @Test
    fun aFailedOpenIsClosedAndRetried() {
        val backend = FailsFirst()
        val subject = AisCameraWorker(AisCamera(backend), worker, timer, post = { it() }, retryDelayMs = 0L)

        subject.open("tex") { results += it }

        waitFor { results.isNotEmpty() }
        assertEquals(AisCamera.State.Streaming, results.last())
        assertEquals(listOf("open", "close", "open", "setSurface"), backend.calls)
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + WAIT_MS
        while (!condition()) {
            if (System.currentTimeMillis() > until) {
                throw AssertionError("timed out; results=$results")
            }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val DEADLINE_MS = 100L
        const val WAIT_MS = 5_000L
        const val POLL_MS = 5L
        const val STREAM_FAILED = -8
    }
}
