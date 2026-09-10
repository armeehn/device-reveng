package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The harness, checked against captures whose answer is already known.
 *
 * The frames below are real, taken verbatim from two archived runs on 2026-09-07: one holding the
 * PASSENGER door open, one holding the DRIVER door open. The driver bit was established
 * independently by actuation, so these runs are a ground truth the probe must reproduce.
 *
 * Both traps this project actually fell into are present in the data rather than described:
 *  - `0x620` byte 1 bit `0x80` is the 0.3 s event pulse once mistaken for door state. It fires in
 *    BOTH runs and must not win.
 *  - `0x4A5` byte 7 is a checksum, churning through values in both runs. Nothing tells the probe
 *    that; it has to sink on its own.
 */
class GuidedTestJudgeTest {

    private fun bytes(hex: String) = ByteArray(hex.length / 2) {
        hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    // Captured while the FRONT PASSENGER door was held open (driver door shut).
    private val BASE_4A5 = listOf(
        "0001E080C80008BA", "0001E080C80008B9", "0001E080C80008B6", "0001E080C80008B9", "0001E000C80008B7", "0001E000C80008B6",
        "0001E000C80008B4", "0001E000C80008B5", "0001E000C80008B1", "0001E000C80008B6", "0001E040C80008C5", "0001E040C80008C4",
        "0001E040C80008C5", "0001E040C80008C0", "0001E040C80008C8", "0001E040C80008C5", "0001E040C80008C6", "0001E040C80008C5",
        "0001E040C80008C5", "0001E040C80008C6", "0001E040C80008C4", "0001E040C80008C0", "0001E040C80008C5", "0001E040C80008C3",
        "0001E040C80008C3", "0001E040C80008C4", "0001E040C80008C2", "0001E040C80008C5", "0001E040C80008C1", "0001E040C80008C2",
        "0001E040C80008C0", "0001E040C80008C3", "0001E040C80008C1", "0001E040C80008C2", "0001E040C80008C0", "0001E040C80008C1",
        "0001E040C80008C0", "0001E040C80008C1", "0001E040C80008BF", "0001E040C80008BE", "0001E040C80008BE", "0001E040C80008BE",
        "0001E040C80008C1", "0001E040C80008BE", "0001E040C80008BD", "0001E040C80008BD", "0001E040C80008BD", "0001E040C80008BB",
        "0001E040C80008BD", "0001E040C80008B9", "0001E040C80008B9", "0001E040C80008BF", "0001E040C80008BB", "0001E040C80008B9",
        "0001E040C80008B9", "0001E040C80008BB", "0001E040C80008BA", "0001E040C80008BC", "0001E040C80008BA", "0001E040C80008B7",
    )

    // Captured while the DRIVER door was held open.
    private val ACT_4A5 = listOf(
        "0001E080C80008C6", "0001E080C80008C5", "0001E080C80008C5", "0001E080C80008C4", "0001E080C80008C5", "0001E080C80008C4",
        "0001E080C80008C4", "0001E080C80008C2", "0001E080C80008C0", "0001E080C80008C2", "0001E080C80008C0", "0001E000C80008C3",
        "0001E000C80008BF", "0001E000C80008C3", "0001E080C80008C2", "0001E080C80008C2", "0001E080C80008C1", "0001E080C80008C1",
        "0001E080C80008C4", "0001E080C80008C4", "0001E080C80008C1", "0001E080C80008C3", "0001E080C80008C2", "0001E080C80008C2",
        "0001E080C80008C3", "0001E080C80008C1", "0001E080C80008C3", "0001E080C80008C1", "0001E080C80008C2", "0001E080C80008C3",
        "0001E080C80008C1", "0001E080C80008C0", "0001E080C80008C0", "0001E080C80008C0", "0001E080C80008BF", "0001E080C80008C0",
        "0001E080C80008C0", "0001E080C80008C1", "0001E080C80008C0", "0001E080C80008C2", "0001E080C80008C1", "0001E080C80008C1",
        "0001E080C80008C1", "0001E080C80008C0", "0001E080C80008C0", "0001E080C80008C1", "0001E080C80008C2", "0001E080C80008BF",
        "0001E080C80008C1", "0001E080C80008BE", "0001E080C80008BB", "0001E080C80008C0", "0001E080C80008C3", "0001E080C80008BE",
        "0001E080C80008BE", "0001E080C80008BB", "0001E080C80008C2", "0001E080C80008BE", "0001E080C80008C0", "0001E080C80008BD",
    )

    // The door-activity pulse id, same two runs.
    private val BASE_620 = listOf(
        "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0400050", "10000000B0400050",
        "10000000B0400050", "10000000B0400050", "10000000B0400050", "10000000B0400050", "10000000B0400050", "10000000B0500050",
        "10000000B0500050", "10000000B0500050", "10000000B0500050", "10000000B0500050", "10000000B0500050", "10000000B0500050",
        "10000000B0500050", "10000000B0500050", "10000000B0500050", "10000000B0500050", "10000000B0500050", "10000000B0500050",
        "10000000B0500050", "10000000B0500050", "10000000B0500050", "10000000B0500050", "10000000B0500050", "10000000B0500050",
        "10000000B0500050", "10000000B0500050", "10000000B0500050", "10000000B0500050", "10000000B0500050", "10000000B0500050",
        "10000000B0500050", "10000000B0500050", "10000000B0500050", "10000000B0500050", "10000000B0500050", "10000000B0500050",
        "10000000B0500050", "10000000B0500050", "10000000B0500050", "10000000B0500050", "10000000B0500050", "10000000B0500050",
        "10000000B0500050", "10000000B0500050", "10000000B0500050", "10000000B0500050", "10000000B0500050", "10000000B0500050",
        "10000000B0500050", "10000000B0500050", "10000000B0500050", "10000000B0500050", "10000000B0500050", "10000000B0500050",
    )

    private val ACT_620 = listOf(
        "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050",
        "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050",
        "10000000B0600050", "10000000B0400050", "10000000B0400050", "10000000B0400050", "10000000B0600050", "10000000B0600050",
        "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050",
        "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050",
        "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050",
        "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050",
        "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050",
        "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050",
        "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050", "10000000B0600050",
    )

    private fun probeOfDoors(): SignalProbe {
        val probe = SignalProbe()

        probe.phase(SignalProbe.Phase.BASELINE)
        BASE_4A5.forEach { probe.accept(0x4A5, bytes(it)) }
        BASE_620.forEach { probe.accept(0x620, bytes(it)) }

        probe.phase(SignalProbe.Phase.ACTION)
        ACT_4A5.forEach { probe.accept(0x4A5, bytes(it)) }
        ACT_620.forEach { probe.accept(0x620, bytes(it)) }

        return probe
    }

    private val driverDoorTest = GuidedTest.CATALOGUE.first { it.key == "driver-door" }

    /**
     * The known door bit must be among the strongest movers. Deliberately NOT "the strongest":
     * writing that assertion first is how this test failed, and the failure was correct. A second
     * field moves just as hard on the same actuation (see below), and demanding a single winner
     * would have meant editing reality to fit the harness.
     */
    @Test
    fun `the driver door bit is among the strongest movers`() {
        val top = probeOfDoors().candidates().take(4)

        assertTrue(
            "door bit missing from " + top,
            top.any { it.id == 0x4A5 && it.byteIndex == 3 && it.bitMask == 0x80 },
        )
    }

    /**
     * Found by this harness, on archived captures, before anyone drove anywhere.
     *
     * `0x620` byte 5 tracks which front door is open: `0x40` with both shut, `0x50` with the
     * passenger open, `0x60` with the driver open. Across the two full runs its `0x20` bit moves
     * 0.050 to 0.955, as hard as the confirmed door bit itself.
     *
     * The project had written `0x620` off as "not doors" after examining byte 1, which really is
     * only a 0.3 s event pulse. Byte 5 was never looked at. Both statements are true, which is
     * why the note dismissing the whole id was wrong.
     *
     * Treated as a strong lead, not a fact: neither run holds every door shut for long, so a
     * latched "last door operated" would look identical. The catalogue carries a test to settle it.
     */
    @Test
    fun `a second door field moves on the same actuation`() {
        val moved = probeOfDoors().movementOf(0x620, 5, 0x20)!!

        assertTrue("delta was " + moved.delta, moved.delta > GuidedTest.RESPONDED_DELTA)
    }

    /** The measured figures from the archived runs: 0.06 set with it shut, 0.96 with it open. */
    @Test
    fun `the measured fractions match the runs they came from`() {
        val moved = probeOfDoors().movementOf(0x4A5, 3, 0x80)!!

        assertEquals(0.06, moved.baselineFraction, 0.03)
        assertEquals(0.96, moved.actionFraction, 0.03)
        assertTrue(moved.delta > GuidedTest.RESPONDED_DELTA)
    }

    @Test
    fun `the harness answers the driver door test correctly`() {
        val verdict = GuidedTestJudge.judge(driverDoorTest, probeOfDoors())

        assertTrue("expected Responded, got " + verdict, verdict is TestVerdict.Responded)
        val responded = verdict as TestVerdict.Responded
        assertTrue(responded.set)
        assertEquals("driver door ajar", responded.label)
    }

    /**
     * The pulse must lose. It fires in both runs, so its fraction barely moves, which is the whole
     * reason this ranks by movement rather than by counting toggles. Counting toggles is the
     * method that originally picked this pulse as the door signal.
     */
    @Test
    fun `the door-activity pulse does not beat the real state bit`() {
        val moved = probeOfDoors().movementOf(0x620, 1, 0x80)

        val delta = moved?.delta ?: 0.0
        assertTrue("pulse moved " + delta, kotlin.math.abs(delta) < GuidedTest.RESPONDED_DELTA)
    }

    /**
     * The checksum byte must not be mistaken for signal, and nothing tells the probe which byte
     * it is. Every bit of 0x4A5 byte 7 has to fall short on its own.
     */
    @Test
    fun `the checksum byte sinks without being named`() {
        val checksumBits = probeOfDoors().candidates().filter { it.id == 0x4A5 && it.byteIndex == 7 }

        assertTrue(checksumBits.isNotEmpty())
        checksumBits.forEach {
            assertTrue("checksum bit moved " + it.delta, kotlin.math.abs(it.delta) < GuidedTest.RESPONDED_DELTA)
        }
    }

    /** A prediction that is wrong must report what DID move, which is how the next theory starts. */
    @Test
    fun `a wrong prediction reports the real movers`() {
        val wrong = driverDoorTest.copy(
            expected = GuidedTest.Expected(0x4A5, 3, 0x01, "a bit that does nothing"),
        )

        val verdict = GuidedTestJudge.judge(wrong, probeOfDoors())

        assertTrue(verdict is TestVerdict.NoResponse)
        val movers = (verdict as TestVerdict.NoResponse).topMovers
        assertTrue(
            "real door bit missing from " + movers,
            movers.any { it.id == 0x4A5 && it.byteIndex == 3 && it.bitMask == 0x80 },
        )
    }

    /** A search with no prediction reports observations, never a pass or a fail. */
    @Test
    fun `a search reports observations`() {
        val search = GuidedTest.CATALOGUE.first { it.expected == null }

        val verdict = GuidedTestJudge.judge(search, probeOfDoors())

        assertTrue(verdict is TestVerdict.Observed)
    }

    /** Too little bus is its own answer, and must never be reported as a failed control. */
    @Test
    fun `a starved run refuses to give a verdict`() {
        val probe = SignalProbe()
        probe.phase(SignalProbe.Phase.BASELINE)
        probe.accept(0x4A5, bytes("0001E000C80008B7"))
        probe.phase(SignalProbe.Phase.ACTION)
        probe.accept(0x4A5, bytes("0001E080C80008C6"))

        assertTrue(GuidedTestJudge.judge(driverDoorTest, probe) is TestVerdict.NotEnoughData)
    }

    /** Every catalogue entry must tell a person what to do in both windows. */
    @Test
    fun `every catalogue entry is actionable`() {
        assertTrue(GuidedTest.CATALOGUE.isNotEmpty())
        GuidedTest.CATALOGUE.forEach {
            assertTrue(it.key, it.baselinePrompt.isNotBlank())
            assertTrue(it.key, it.actionPrompt.isNotBlank())
            assertTrue(it.key, it.title.isNotBlank())
        }
        assertEquals(GuidedTest.CATALOGUE.size, GuidedTest.CATALOGUE.map { it.key }.toSet().size)
    }

    /** The instrument-proving tests must come before the searches. See CATALOGUE. */
    @Test
    fun `a confirmed test runs before the first search`() {
        val firstSearch = GuidedTest.CATALOGUE.indexOfFirst { it.expected == null }
        val firstKnown = GuidedTest.CATALOGUE.indexOfFirst { it.expected != null }

        assertTrue(firstKnown < firstSearch)
    }
}
