package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The config is a blob a person pastes in. It must round-trip, and — more important for a HOME
 * app — a bad blob must degrade into "what could be used, plus why the rest was not", never
 * into a crash or a half-defined light.
 */
class AccessoryConfigTest {

    private val good = """
        {"baseUrl":"http://accessories.car",
         "accessories":[{"id":"bar","name":"Light bar","kind":"switch"},
                        {"id":"antenna","name":"Antenna","kind":"level"}],
         "sequences":[{"id":"stow","name":"Stow antenna",
                       "steps":[{"accessory":"antenna","level":0,"holdMs":1500},
                                {"accessory":"antenna","power":"off"}]},
                      {"id":"wl","name":"Work lights","steps":[{"accessory":"bar","power":"on"}]}],
         "triggers":[{"id":"rev","name":"Reverse lights","on":"reverse","sequence":"wl"}]}
    """.trimIndent()

    @Test
    fun `a clean blob parses with no problems`() {
        val c = AccessoryConfig.parse(good)

        assertTrue(c.problems.isEmpty())
        assertEquals("http://accessories.car", c.baseUrl)
        assertEquals(listOf("bar", "antenna"), c.accessories.map { it.id })
        assertEquals(AccessoryKind.LEVEL, c.accessories[1].kind)
        assertEquals(2, c.sequences.size)
        assertEquals(1500L, c.sequences[0].steps[0].holdMs)
        assertEquals(AccessoryCommand.SetLevel(0), c.sequences[0].steps[0].command)
        assertEquals(AccessoryCommand.SetPower(Power.OFF), c.sequences[0].steps[1].command)
        assertEquals(AccessoryConfig.TriggerEvent.REVERSE, c.triggers.single().on)
    }

    @Test
    fun `serialize and parse round-trip`() {
        val once = AccessoryConfig.parse(good)
        val twice = AccessoryConfig.parse(once.serialize())

        assertEquals(once.copy(problems = emptyList()), twice.copy(problems = emptyList()))
        assertTrue(twice.problems.isEmpty())
    }

    @Test
    fun `triggers bind to their sequence and condition`() {
        val live = AccessoryConfig.parse(good).toTriggers()

        assertEquals("wl", live.single().sequence.id)
        assertEquals("Reverse lights", live.single().name)
    }

    // ── Negative controls ───────────────────────────────────────────────────────────────────────

    @Test
    fun `null or blank is an empty config with no problems`() {
        assertTrue(AccessoryConfig.parse(null).isEmpty)
        assertTrue(AccessoryConfig.parse("   ").problems.isEmpty())
    }

    @Test
    fun `junk is empty and says so`() {
        val c = AccessoryConfig.parse("<html>")

        assertTrue(c.isEmpty)
        assertEquals(listOf("not a JSON object"), c.problems)
    }

    @Test
    fun `a step naming an unknown accessory is dropped, not kept to fail later`() {
        val c = AccessoryConfig.parse("""{"accessories":[{"id":"bar","kind":"switch"}],
            "sequences":[{"id":"s","steps":[{"accessory":"ghost","power":"on"},{"accessory":"bar","power":"on"}]}]}""")

        assertEquals(1, c.sequences.single().steps.size)
        assertTrue(c.problems.any { it.contains("ghost") })
    }

    @Test
    fun `a sequence with no usable steps is dropped entirely`() {
        val c = AccessoryConfig.parse("""{"accessories":[{"id":"bar","kind":"switch"}],
            "sequences":[{"id":"s","steps":[{"accessory":"bar","power":"maybe"}]}]}""")

        assertTrue(c.sequences.isEmpty())
        assertTrue(c.problems.any { it.contains("no usable steps") })
    }

    @Test
    fun `an unknown trigger event is dropped`() {
        val c = AccessoryConfig.parse("""{"accessories":[{"id":"bar","kind":"switch"}],
            "sequences":[{"id":"s","steps":[{"accessory":"bar","power":"on"}]}],
            "triggers":[{"id":"t","on":"handbrake","sequence":"s"}]}""")

        assertTrue(c.triggers.isEmpty())
        assertTrue(c.problems.any { it.contains("handbrake") })
    }

    @Test
    fun `a trigger naming an unknown sequence is dropped`() {
        val c = AccessoryConfig.parse("""{"triggers":[{"id":"t","on":"reverse","sequence":"nope"}]}""")

        assertTrue(c.triggers.isEmpty())
        assertTrue(c.toTriggers().isEmpty())
    }

    @Test
    fun `the first declaration of an id wins`() {
        // A duplicate must not silently redefine a light as a servo.
        val c = AccessoryConfig.parse("""{"accessories":[{"id":"bar","kind":"switch"},{"id":"bar","kind":"level"}]}""")

        assertEquals(1, c.accessories.size)
        assertEquals(AccessoryKind.SWITCH, c.accessories.single().kind)
        assertTrue(c.problems.any { it.contains("duplicate") })
    }

    @Test
    fun `an out-of-range level or negative hold is refused`() {
        val c = AccessoryConfig.parse("""{"accessories":[{"id":"a","kind":"level"}],
            "sequences":[{"id":"s","steps":[{"accessory":"a","level":150},{"accessory":"a","level":5,"holdMs":-1},{"accessory":"a","level":5}]}]}""")

        assertEquals(1, c.sequences.single().steps.size)
        assertEquals(2, c.problems.size)
    }

    @Test
    fun `an unknown kind is refused rather than defaulted`() {
        val c = AccessoryConfig.parse("""{"accessories":[{"id":"x","kind":"dimmer"}]}""")

        assertTrue(c.accessories.isEmpty())
        assertTrue(c.problems.single().contains("dimmer"))
    }
}
