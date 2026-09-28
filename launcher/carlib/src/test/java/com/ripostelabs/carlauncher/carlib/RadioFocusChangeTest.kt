package com.ripostelabs.carlauncher.carlib

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * Focus changes arrive on the main looper; the source switch they cause waits for a MODE_ACK the
 * MCU never sends for SRC_NULL. None of it may run on the calling thread (RAV4-147).
 */
class RadioFocusChangeTest {

    /** Holds tasks until [drain], so a test sees what ran inline. */
    private class Queue : Executor {
        val tasks = mutableListOf<Runnable>()
        override fun execute(command: Runnable) {
            tasks += command
        }
        fun drain() {
            tasks.toList().also { tasks.clear() }.forEach { it.run() }
        }
    }

    private val sent = mutableListOf<McuOwnerProtocol.Mode>()
    private val source = RadioSource(select = { mode ->
        sent += mode
        false
    })
    private val queue = Queue()
    private var released = 0
    private val focus = RadioFocusChange(source, release = { released++ }, run = queue)

    @Test
    fun lossReleasesOffTheCallingThread() {
        focus.on(AudioManager.AUDIOFOCUS_LOSS)

        assertEquals(0, released)

        queue.drain()
        assertEquals(1, released)
    }

    @Test
    fun gainReclaimsOffTheCallingThread() {
        source.claim()
        sent.clear()

        focus.on(AudioManager.AUDIOFOCUS_GAIN)

        assertTrue(sent.isEmpty())

        queue.drain()
        assertEquals(listOf(McuOwnerProtocol.Mode.RADIO), sent)
    }

    /** A gain while the tuner is not ours selects nothing. */
    @Test
    fun gainWithoutClaimSendsNothing() {
        focus.on(AudioManager.AUDIOFOCUS_GAIN)
        queue.drain()

        assertTrue(sent.isEmpty())
    }
}
