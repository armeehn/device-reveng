package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The wire format lives in one place and this pins it. The negative controls are the point: a
 * board that answers something other than the contract must read as "not confirmed", never as
 * "off" — the transport is where a lying device would first be believed.
 */
class HttpAccessoryTransportTest {

    private fun parse(body: String) = HttpAccessoryTransport.parseState(body)

    @Test
    fun `power on and off parse`() {
        assertEquals(Power.ON, parse("""{"power":"on"}""")!!.power)
        assertEquals(Power.OFF, parse("""{"power":"off"}""")!!.power)
    }

    @Test
    fun `a level parses with or without power`() {
        assertEquals(42, parse("""{"level":42}""")!!.level)

        val both = parse("""{"power":"on","level":100}""")!!
        assertEquals(Power.ON, both.power)
        assertEquals(100, both.level)
    }

    @Test
    fun `zero and one hundred are valid levels`() {
        assertEquals(0, parse("""{"level":0}""")!!.level)
        assertEquals(100, parse("""{"level":100}""")!!.level)
    }

    @Test
    fun `extra fields are ignored`() {
        // A board may report more than we model — uptime, temperature. That is not an error.
        assertEquals(Power.ON, parse("""{"power":"on","uptime_s":12,"temp_c":41.5}""")!!.power)
    }

    // ── Negative controls ───────────────────────────────────────────────────────────────────────

    @Test
    fun `an HTML error page is not a state`() {
        assertNull(parse("<html><body>404</body></html>"))
    }

    @Test
    fun `an empty body is not a state`() {
        assertNull(parse(""))
        assertNull(parse("{}"))
    }

    @Test
    fun `an unknown power word is refused, not read as off`() {
        // "1", "true", "ON": a board speaking a different dialect must not be believed.
        assertNull(parse("""{"power":"1"}"""))
        assertNull(parse("""{"power":"true"}"""))
        assertNull(parse("""{"power":"ON"}"""))
    }

    @Test
    fun `a level outside the range is refused`() {
        assertNull(parse("""{"level":101}"""))
        assertNull(parse("""{"level":-1}"""))
    }

    @Test
    fun `a level that is not a number is refused`() {
        assertNull(parse("""{"level":"high"}"""))
    }

    @Test
    fun `a state is never invented from a null`() {
        assertNull(parse("null"))
        assertNull(parse("[]"))
    }
}
