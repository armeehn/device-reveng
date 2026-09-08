package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A sequence is a plan whose later steps assume the earlier ones happened. Most of this file is
 * about a plan being void the moment that stops being true, and about two plans never fighting
 * over one light.
 */
class AccessorySequenceTest {

    private val bar = Accessory("bar", "Light bar", AccessoryKind.SWITCH)
    private val spot = Accessory("spot", "Spot", AccessoryKind.LEVEL)
    private val servo = Accessory("servo", "Antenna", AccessoryKind.LEVEL)

    private fun on(id: String, hold: Long = 0) = SequenceStep(id, AccessoryCommand.SetPower(Power.ON), hold)
    private fun off(id: String, hold: Long = 0) = SequenceStep(id, AccessoryCommand.SetPower(Power.OFF), hold)
    private fun level(id: String, l: Int, hold: Long = 0) = SequenceStep(id, AccessoryCommand.SetLevel(l), hold)

    /** Records every command and answers as told, per accessory. */
    private class Fake : AccessoryTransport {
        val sent = mutableListOf<Pair<String, AccessoryCommand>>()
        val answer = mutableMapOf<String, CommandResult>()
        override fun apply(accessory: Accessory, command: AccessoryCommand): CommandResult {
            sent += accessory.id to command
            return answer[accessory.id] ?: CommandResult.APPLIED
        }
        override fun read(accessory: Accessory): AccessoryState? = null
    }

    private fun runner(fake: Fake) = SequenceRunner(AccessoryController(fake, listOf(bar, spot, servo)))

    @Test
    fun `steps run in order, each after its hold`() {
        val fake = Fake()
        val r = runner(fake)
        val welcome = AccessorySequence("w", "Welcome", listOf(on("bar", hold = 400), level("spot", 100, hold = 2_000), level("spot", 30)))

        r.start(welcome, now = 1_000)
        assertEquals(listOf("bar"), fake.sent.map { it.first })

        r.tick(1_399)
        assertEquals(1, fake.sent.size)

        r.tick(1_400)
        assertEquals(listOf("bar", "spot"), fake.sent.map { it.first })

        r.tick(3_400)
        assertEquals(3, fake.sent.size)
        assertTrue(r.state is SequenceState.Finished)
    }

    @Test
    fun `zero holds run in a single tick`() {
        val fake = Fake()
        val r = runner(fake)

        r.start(AccessorySequence("z", "All on", listOf(on("bar"), on("spot"), level("servo", 0))), now = 0)

        assertEquals(3, fake.sent.size)
        assertTrue(r.state is SequenceState.Finished)
    }

    @Test
    fun `the state says what the sequence did`() {
        val fake = Fake()
        val r = runner(fake)
        val seq = AccessorySequence("s", "One", listOf(on("bar")))

        r.start(seq, now = 5)

        assertEquals(SequenceState.Finished(seq, 5), r.state)
    }

    // ── Negative controls ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a refused step aborts instead of continuing blind`() {
        val fake = Fake()
        fake.answer["servo"] = CommandResult.REJECTED
        val r = runner(fake)
        // Stow: servo to 0, then cut its power. Cutting power after a refused move leaves the
        // antenna wherever it stopped.
        val stow = AccessorySequence("st", "Stow", listOf(level("servo", 0, hold = 1_500), off("servo")))

        r.start(stow, now = 0)
        r.tick(10_000)

        assertEquals(1, fake.sent.size)
        val aborted = r.state as SequenceState.Aborted
        assertEquals(0, aborted.step)
        assertTrue(aborted.reason.contains("REJECTED"))
    }

    @Test
    fun `an unreachable step aborts too`() {
        val fake = Fake()
        fake.answer["spot"] = CommandResult.UNREACHABLE
        val r = runner(fake)

        r.start(AccessorySequence("u", "Two", listOf(on("bar"), on("spot"), on("servo"))), now = 0)

        assertEquals(listOf("bar", "spot"), fake.sent.map { it.first })
        assertTrue(r.state is SequenceState.Aborted)
    }

    @Test
    fun `starting a second sequence cancels the first, it does not interleave`() {
        val fake = Fake()
        val r = runner(fake)
        val a = AccessorySequence("a", "A", listOf(on("bar", hold = 1_000), off("bar")))
        val b = AccessorySequence("b", "B", listOf(level("spot", 50)))

        r.start(a, now = 0)
        r.start(b, now = 100)
        r.tick(5_000)

        // A's second step never runs: the light bar is left as B found it, not switched off
        // underneath B by a plan that was abandoned.
        assertEquals(listOf("bar", "spot"), fake.sent.map { it.first })
        assertTrue(r.state is SequenceState.Finished)
    }

    @Test
    fun `cancel stops future steps and undoes nothing`() {
        val fake = Fake()
        val r = runner(fake)

        r.start(AccessorySequence("c", "C", listOf(on("bar", hold = 1_000), off("bar"))), now = 0)
        r.cancel(now = 500)
        r.tick(5_000)

        assertEquals(1, fake.sent.size)
        assertEquals(-1, (r.state as SequenceState.Aborted).step)
    }

    @Test
    fun `an idle runner ignores ticks`() {
        val fake = Fake()
        val r = runner(fake)

        r.tick(1)
        r.tick(1_000_000)

        assertTrue(fake.sent.isEmpty())
        assertEquals(SequenceState.Idle, r.state)
    }

    @Test
    fun `an empty sequence is refused at construction`() {
        var threw = false
        try {
            AccessorySequence("e", "Empty", emptyList())
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}
