package com.ripostelabs.carlauncher.carlib

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A `when` takes its first matching branch, so a broadcast action listed twice in
 * [CarEvents]'s dispatch runs only the upper handler and the lower one is dead code.
 * `Zlink.ACTION_MESSAGE` was listed twice: the CarPlay call state (RAV4-52, PR #83's
 * PHONE_CALL_ON/OFF) never reached `carplayState`, so the car-kit's CarPlay gates never closed.
 */
class CarEventsDispatchAuditTest {

    private companion object {
        const val SOURCE = "src/main/java/com/ripostelabs/carlauncher/carlib/CarEvents.kt"
        const val DISPATCH = "private fun dispatch(intent: Intent?)"

        /** A branch of the dispatch `when`, at its fixed indent: `ACTION_A, ACTION_B -> {`. */
        val BRANCH = Regex("""^ {16}([A-Za-z_.]+(?:, [A-Za-z_.]+)*) ->""")

        /** The dispatch body ends at the next member at the receiver object's indent. */
        val NEXT_MEMBER = Regex("""^ {8}(private |override )?fun """)
    }

    @Test
    fun `every action has one dispatch branch`() {
        val lines = File(SOURCE).readLines()
        val start = lines.indexOfFirst { it.contains(DISPATCH) }
        val body = lines.drop(start + 1).takeWhile { !NEXT_MEMBER.containsMatchIn(it) }

        val labels = body.mapNotNull { BRANCH.find(it)?.groupValues?.get(1) }
            .flatMap { it.split(", ") }
            .filter { it != "else" }
        val twice = labels.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

        assertEquals("actions with an unreachable second branch", emptySet<String>(), twice)
    }
}
