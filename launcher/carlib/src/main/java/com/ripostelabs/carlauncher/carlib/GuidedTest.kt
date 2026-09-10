package com.ripostelabs.carlauncher.carlib

/**
 * GuidedTest — one car action, one question, one answer.
 *
 * ── What this replaces ──────────────────────────────────────────────────────────────────────────
 * Attributing a signal on this car has meant: drive out, capture, come back, trawl the log, form
 * a theory, drive out again. Several theories died on the second trip, and two shipped wrong
 * before the car contradicted them. The instrument was always a log file read hours later at a
 * desk, by which time nobody could say exactly what had been pressed and when.
 *
 * This is the same method with the human in the loop: the screen says which control to operate
 * and when, watches the bus either side of it, and answers on the spot.
 *
 * ── The rule every test obeys ───────────────────────────────────────────────────────────────────
 * Hold ONE state for the whole window. Not a press, not a sweep. The difference between a state
 * and a 0.3 s event pulse is only visible when a state is held long enough to dominate the window,
 * and mistaking the two is the single error this project has made most often.
 *
 * A test with no [expected] is not a lesser test. Three separate hunts for turn indicators found
 * nothing on this bus, and a run that reports "nothing moved" is how that becomes a fact rather
 * than a suspicion.
 */
data class GuidedTest(
    val key: String,
    val title: String,

    /** What to do, or not do, while the first window runs. */
    val baselinePrompt: String,

    /** The one thing to change, and to HOLD, while the second window runs. */
    val actionPrompt: String,

    /** The signal this test predicts, or null when the test is a search. */
    val expected: Expected? = null,

    /** Why this test exists, in one line, for whoever is holding the door open. */
    val note: String? = null,
) {

    /** A predicted bit, named so a verdict can say what it is rather than where it is. */
    data class Expected(
        val canId: Int,
        val byteIndex: Int,
        val bitMask: Int,
        val label: String,
    )

    companion object {

        /**
         * The tests worth a trip to the car, cheapest first.
         *
         * Order matters. The first two prove the instrument works on signals already confirmed by
         * actuation, so a later "nothing moved" can be read as a fact about the car rather than a
         * fault in the harness. Running the searches first would leave that ambiguous.
         */
        val CATALOGUE = listOf(
            GuidedTest(
                key = "driver-door",
                title = "Driver's door",
                baselinePrompt = "Close every door. Sit still.",
                actionPrompt = "Open the driver's door and HOLD it open.",
                expected = Expected(0x4A5, 3, 0x80, "driver door ajar"),
                note = "Already confirmed by actuation. Run it first: it proves the harness, " +
                    "so a later negative means the car, not the instrument.",
            ),
            GuidedTest(
                key = "tailgate",
                title = "Tailgate",
                baselinePrompt = "Close everything. Sit still.",
                actionPrompt = "Open the tailgate and leave it up.",
                expected = Expected(0x4A5, 3, 0x08, "tailgate ajar"),
                note = "Second confirmation of the instrument, on a different bit of the same byte.",
            ),
            GuidedTest(
                key = "bonnet",
                title = "Bonnet",
                baselinePrompt = "Close everything. Sit still.",
                actionPrompt = "Open the bonnet and leave it up.",
                expected = Expected(0x4A5, 3, 0x04, "bonnet ajar"),
                note = "PREDICTED ONLY, never actuated. The bit was taken from the vendor's own " +
                    "byte layout and has never been seen set. This settles it.",
            ),
            GuidedTest(
                key = "second-door-field",
                title = "Door field cross-check",
                baselinePrompt = "Close every door. Sit still.",
                actionPrompt = "Open the driver's door and HOLD it open.",
                expected = Expected(0x620, 5, 0x20, "driver door, second field"),
                note = "Found in archived captures by this harness: 0x620 byte 5 reads 0x40 with " +
                    "both front doors shut, 0x50 with the passenger open, 0x60 with the driver " +
                    "open. Neither archived run held everything shut for long, so a latched " +
                    "'last door operated' would look the same. This run separates them.",
            ),
            GuidedTest(
                key = "reverse",
                title = "Reverse gear",
                baselinePrompt = "Car in READY, foot on the brake, leave it in Park.",
                actionPrompt = "Select R and hold it. Do not move off.",
                expected = Expected(0x3BC, 1, 0x10, "reverse selected"),
                note = "Confirmed once, from a single parking manoeuvre. A held run makes it solid.",
            ),
            GuidedTest(
                key = "air-conditioning",
                title = "A/C compressor",
                baselinePrompt = "Climate OFF entirely. Sit still.",
                actionPrompt = "Switch A/C on and leave it on.",
                expected = Expected(0x3B0, 5, 0x08, "climate mode on"),
                note = "Verified from a driveway capture. Re-run confirms it in READY.",
            ),
            GuidedTest(
                key = "left-indicator",
                title = "Left indicator",
                baselinePrompt = "Indicators off. Car in READY.",
                actionPrompt = "Left indicator on and leave it clicking.",
                note = "SEARCH. Three hunts found no indicator anywhere on this bus. The vendor " +
                    "box does report them, so they likely arrive on the other wire pair. A clean " +
                    "'nothing moved' turns that from a suspicion into a result.",
            ),
            GuidedTest(
                key = "headlights",
                title = "Headlights",
                baselinePrompt = "All exterior lights off. Car in READY.",
                actionPrompt = "Headlights on and leave them on.",
                note = "SEARCH. A promising left/right pair on 0x63B failed its confirmation run " +
                    "outright. Exterior lighting has never been found on this bus.",
            ),
            GuidedTest(
                key = "fan-step",
                title = "Blower speed",
                baselinePrompt = "Climate on, fan at its LOWEST step. Auto off.",
                actionPrompt = "Raise the fan to its HIGHEST step and leave it there.",
                note = "SEARCH. The selected step is not on this bus; only blower duty appears, " +
                    "at 0x4AD byte 6. Watch the disjoint-byte list rather than the bit list.",
            ),
            GuidedTest(
                key = "driver-window",
                title = "Driver's window",
                baselinePrompt = "All windows fully up. Car in READY.",
                actionPrompt = "Lower the driver's window fully and leave it down.",
                note = "SEARCH. Never looked for. A window position is a plausible body signal " +
                    "and nothing has ruled it out.",
            ),
        )

        /**
         * How far a bit must move to count as an answer.
         *
         * The one signal measured against held states moved 0.06 to 0.96, a delta of 0.90. The
         * pulse that was mistaken for it sat at 0.01 in both windows. Half is comfortably between
         * them and far enough from either to survive a noisier car.
         */
        const val RESPONDED_DELTA = 0.5

        /** Below this many frames in a window, no verdict is honest. */
        const val MIN_FRAMES = 40

        /** How many movers to show when the prediction did not pan out. */
        const val TOP_MOVERS = 6
    }
}

/** What a completed run concluded. */
sealed interface TestVerdict {

    /** The predicted bit moved, and by how much. [set] says which way. */
    data class Responded(
        val label: String,
        val delta: Double,
        val set: Boolean,
        val alsoMoved: List<SignalProbe.Candidate>,
    ) : TestVerdict

    /**
     * The prediction did not move. [topMovers] is the useful half: whatever DID move is the
     * next hypothesis, and this is how an unknown signal gets found rather than merely missed.
     */
    data class NoResponse(
        val label: String,
        val observedDelta: Double?,
        val topMovers: List<SignalProbe.Candidate>,
        val disjointBytes: List<SignalProbe.DisjointByte>,
    ) : TestVerdict

    /** A search with no prediction. Everything that moved, for a human to read. */
    data class Observed(
        val topMovers: List<SignalProbe.Candidate>,
        val disjointBytes: List<SignalProbe.DisjointByte>,
    ) : TestVerdict

    /** Not enough bus in one of the windows. Says which, because the causes differ. */
    data class NotEnoughData(val baselineFrames: Int, val actionFrames: Int) : TestVerdict
}

/**
 * Read a completed probe as a verdict on one test.
 *
 * Pure, so the whole decision is testable against archived captures without a car — which is how
 * this harness was checked before anybody drove anywhere.
 */
object GuidedTestJudge {

    fun judge(test: GuidedTest, probe: SignalProbe): TestVerdict {
        val baseline = probe.framesIn(SignalProbe.Phase.BASELINE)
        val action = probe.framesIn(SignalProbe.Phase.ACTION)
        if (baseline < GuidedTest.MIN_FRAMES || action < GuidedTest.MIN_FRAMES) {
            return TestVerdict.NotEnoughData(baseline, action)
        }

        val movers = probe.candidates()
            .filter { kotlin.math.abs(it.delta) >= NOISE_FLOOR }
            .take(GuidedTest.TOP_MOVERS)
        val disjoint = probe.disjointBytes()

        val expected = test.expected
            ?: return TestVerdict.Observed(topMovers = movers, disjointBytes = disjoint)

        val found = probe.movementOf(expected.canId, expected.byteIndex, expected.bitMask)
        val delta = found?.delta

        if (delta != null && kotlin.math.abs(delta) >= GuidedTest.RESPONDED_DELTA) {
            return TestVerdict.Responded(
                label = expected.label,
                delta = delta,
                set = delta > 0,
                // Anything else that moved as much is reported too. A control that lights up two
                // ids is a fact worth seeing, not noise to hide behind a single green tick.
                alsoMoved = movers.filter {
                    it.id != expected.canId || it.byteIndex != expected.byteIndex || it.bitMask != expected.bitMask
                },
            )
        }

        return TestVerdict.NoResponse(
            label = expected.label,
            observedDelta = delta,
            topMovers = movers,
            disjointBytes = disjoint,
        )
    }

    /**
     * Movement below this is not worth a human's attention. Deliberately low: the point of the
     * list is to surface a candidate nobody predicted, and a floor set for tidiness would hide
     * exactly the weak first sighting that is worth a second run.
     */
    private const val NOISE_FLOOR = 0.15
}
