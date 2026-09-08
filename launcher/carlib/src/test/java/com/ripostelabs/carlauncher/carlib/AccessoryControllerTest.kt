package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A toggle on a screen is a claim about a physical thing. These tests are almost entirely about
 * the claims the controller must REFUSE to make: the standard accessory-UI bug is an optimistic
 * update that moves the switch while the relay stays put.
 */
class AccessoryControllerTest {

    private val t0 = 1_000L
    private val lamp = Accessory("lamp", "Light bar", AccessoryKind.SWITCH)
    private val fan = Accessory("fan", "Fridge fan", AccessoryKind.LEVEL)

    /** A transport whose answer each call is dictated by the test. */
    private class Fake(
        var result: CommandResult = CommandResult.APPLIED,
        var reported: AccessoryState? = null,
    ) : AccessoryTransport {
        var applied: AccessoryCommand? = null

        override fun apply(accessory: Accessory, command: AccessoryCommand): CommandResult {
            applied = command
            return result
        }

        override fun read(accessory: Accessory): AccessoryState? = reported
    }

    @Test
    fun `an applied command becomes the state`() {
        val fake = Fake(CommandResult.APPLIED)
        val c = AccessoryController(fake, listOf(lamp))

        assertEquals(CommandResult.APPLIED, c.send("lamp", AccessoryCommand.SetPower(Power.ON), t0))
        assertEquals(Power.ON, c.state("lamp", t0).power)
    }

    @Test
    fun `a level above zero also implies on`() {
        val c = AccessoryController(Fake(CommandResult.APPLIED), listOf(fan))

        c.send("fan", AccessoryCommand.SetLevel(40), t0)

        assertEquals(40, c.state("fan", t0).level)
        assertEquals(Power.ON, c.state("fan", t0).power)
    }

    @Test
    fun `a level of zero is off`() {
        val c = AccessoryController(Fake(CommandResult.APPLIED), listOf(fan))

        c.send("fan", AccessoryCommand.SetLevel(0), t0)

        assertEquals(Power.OFF, c.state("fan", t0).power)
    }

    @Test
    fun `refresh stores what the device reports`() {
        val fake = Fake(reported = AccessoryState(power = Power.ON))
        val c = AccessoryController(fake, listOf(lamp))

        c.refresh("lamp", t0)

        assertEquals(Power.ON, c.state("lamp", t0).power)
    }

    // ── Negative controls ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a rejected command does not move the switch`() {
        val fake = Fake(CommandResult.APPLIED)
        val c = AccessoryController(fake, listOf(lamp))
        c.send("lamp", AccessoryCommand.SetPower(Power.ON), t0)

        // The device answered and said no, so what we last knew still holds.
        fake.result = CommandResult.REJECTED
        c.send("lamp", AccessoryCommand.SetPower(Power.OFF), t0 + 1)

        assertEquals(Power.ON, c.state("lamp", t0 + 1).power)
    }

    @Test
    fun `an unreachable device goes unknown rather than keeping its last value`() {
        val fake = Fake(CommandResult.APPLIED)
        val c = AccessoryController(fake, listOf(lamp))
        c.send("lamp", AccessoryCommand.SetPower(Power.ON), t0)

        // After a command that nothing answered, we do not know what happened out there.
        fake.result = CommandResult.UNREACHABLE
        c.send("lamp", AccessoryCommand.SetPower(Power.OFF), t0 + 1)

        assertNull(c.state("lamp", t0 + 1).power)
        assertFalse(c.state("lamp", t0 + 1).isKnown)
    }

    @Test
    fun `a silent poll clears the state instead of leaving it on`() {
        val fake = Fake(reported = AccessoryState(power = Power.ON))
        val c = AccessoryController(fake, listOf(lamp))
        c.refresh("lamp", t0)

        fake.reported = null
        c.refresh("lamp", t0 + 1)

        // A stale "on" is the dangerous one: the thing may still be drawing current.
        assertFalse(c.state("lamp", t0 + 1).isKnown)
    }

    @Test
    fun `a known state ages out`() {
        val c = AccessoryController(Fake(CommandResult.APPLIED), listOf(lamp))
        c.send("lamp", AccessoryCommand.SetPower(Power.ON), t0)

        val late = t0 + AccessoryState.STALE_AFTER_MS + 1
        assertFalse(c.state("lamp", late).isKnown)
    }

    @Test
    fun `an unknown accessory is rejected and never touches the transport`() {
        val fake = Fake(CommandResult.APPLIED)
        val c = AccessoryController(fake, listOf(lamp))

        assertEquals(
            CommandResult.REJECTED,
            c.send("nope", AccessoryCommand.SetPower(Power.ON), t0),
        )
        assertNull(fake.applied)
    }

    @Test
    fun `an accessory never commanded is unknown, not off`() {
        val c = AccessoryController(Fake(), listOf(lamp))

        // A switch drawn in the off position asserts the thing is off. We have not asked.
        assertFalse(c.state("lamp", t0).isKnown)
        assertNull(c.state("lamp", t0).power)
        assertTrue(c.accessories().isNotEmpty())
    }
}
