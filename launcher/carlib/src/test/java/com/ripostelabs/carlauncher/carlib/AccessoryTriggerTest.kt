package com.ripostelabs.carlauncher.carlib

import com.ripostelabs.carlauncher.carlib.VehicleSnapshot.Companion.fold
import com.ripostelabs.carlauncher.carlib.VehicleSnapshot.Companion.foldRaw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two ways a trigger lies: firing on a level instead of an edge (lights restart every half second
 * for the whole manoeuvre), and inventing an edge from a gap in the data (a bus dropout reads as
 * "reverse ended", and the bus coming back fires the lights again). Both are pinned here.
 */
class AccessoryTriggerTest {

    private val t0 = 1_000L
    private val lights = AccessorySequence("wl", "Work lights", listOf(
        SequenceStep("bar", AccessoryCommand.SetPower(Power.ON)),
    ))

    private fun reverse(on: Boolean, at: Long) = VehicleSnapshot().fold(
        CanSignal.SysEvent(reverseRaw = on, discPresent = false, usbPresent = false, raw = 0), at,
    )

    private fun doors(driver: Boolean, at: Long) = VehicleSnapshot().foldRaw(
        RawCanSignal.Doors(driver, false, false, false, false, false), at,
    )

    private fun reverseTrigger() = AccessoryTrigger("r", "Reverse", lights, AccessoryTrigger::reverse)

    @Test
    fun `fires once on the rising edge`() {
        val t = reverseTrigger()

        assertFalse(t.fires(reverse(false, t0), t0))
        assertTrue(t.fires(reverse(true, t0 + 1), t0 + 1))
    }

    @Test
    fun `re-arms after the condition falls`() {
        val t = reverseTrigger()
        t.fires(reverse(false, t0), t0)
        t.fires(reverse(true, t0 + 1), t0 + 1)

        assertFalse(t.fires(reverse(false, t0 + 2), t0 + 2))
        assertTrue(t.fires(reverse(true, t0 + 3), t0 + 3))
    }

    @Test
    fun `door triggers read the same snapshot the screen does`() {
        val t = AccessoryTrigger("d", "Welcome", lights, AccessoryTrigger::anyDoorOpen)
        t.fires(doors(driver = false, at = t0), t0)

        assertTrue(t.fires(doors(driver = true, at = t0 + 1), t0 + 1))
    }

    @Test
    fun `the engine returns firing sequences in registration order`() {
        val a = AccessoryTrigger("a", "A", lights, AccessoryTrigger::reverse)
        val b = AccessoryTrigger("b", "B", AccessorySequence("x", "X", lights.steps), AccessoryTrigger::reverse)
        val engine = TriggerEngine(listOf(a, b))
        engine.evaluate(reverse(false, t0), t0)

        val fired = engine.evaluate(reverse(true, t0 + 1), t0 + 1)

        assertEquals(listOf("wl", "x"), fired.map { it.id })
    }

    // ── Negative controls ───────────────────────────────────────────────────────────────────────

    @Test
    fun `a level held true does not keep firing`() {
        val t = reverseTrigger()
        t.fires(reverse(false, t0), t0)
        t.fires(reverse(true, t0 + 1), t0 + 1)

        // The snapshot is re-read twice a second for the whole reversing manoeuvre.
        var fired = 0
        for (i in 2..20) if (t.fires(reverse(true, t0 + i), t0 + i)) fired++

        assertEquals(0, fired)
    }

    @Test
    fun `the first definite reading is a baseline, never an edge`() {
        // Launcher starts while the car is already in reverse: the lights must not fire.
        val t = reverseTrigger()

        assertFalse(t.fires(reverse(true, t0), t0))
    }

    @Test
    fun `unknown never fires`() {
        val t = reverseTrigger()

        assertFalse(t.fires(VehicleSnapshot(), t0))
        assertFalse(t.fires(VehicleSnapshot(), t0 + 1))
    }

    @Test
    fun `a dropout mid-reverse does not invent a second edge`() {
        val t = reverseTrigger()
        t.fires(reverse(false, t0), t0)
        assertTrue(t.fires(reverse(true, t0 + 1), t0 + 1))

        // The bus drops out: the field goes stale, the condition reads null. Nothing happened
        // in the car. When the bus returns, still in reverse, that is NOT a new edge.
        val stale = t0 + 1 + VehicleSnapshot.STALE_AFTER_MS + 1
        assertFalse(t.fires(reverse(true, t0 + 1), stale))
        assertFalse(t.fires(reverse(true, stale + 1), stale + 1))
    }

    @Test
    fun `all doors shut is unknown when doors are unknown, not true`() {
        // The inverse of "any open", not its negation: no door byte means we cannot say shut.
        val t = AccessoryTrigger("s", "Stow", lights, AccessoryTrigger::allDoorsShut)

        assertFalse(t.fires(VehicleSnapshot(), t0))
        t.fires(doors(driver = true, at = t0 + 1), t0 + 1)
        assertTrue(t.fires(doors(driver = false, at = t0 + 2), t0 + 2))
    }
}
