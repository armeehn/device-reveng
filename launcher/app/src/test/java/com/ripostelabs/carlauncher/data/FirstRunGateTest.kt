package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9 — the first-run gate. Both rules were written after walking a freshly cleared unit on the
 * farm, where the launcher's own location dialog covered the welcome screen and the permissions
 * step listed a row ("Rewritten app suite (0/28)") that no driver can answer.
 */
class FirstRunGateTest {

    @Test
    fun `no prompt while onboarding is on screen`() {
        assertFalse(FirstRunGate.mayPrompt(true))
    }

    @Test
    fun `no prompt before the flag is read off disk`() {
        // null is the DataStore's "not resolved yet"; prompting then is the same race.
        assertFalse(FirstRunGate.mayPrompt(null))
    }

    @Test
    fun `prompt on a start after onboarding`() {
        assertTrue(FirstRunGate.mayPrompt(false))
    }

    @Test
    fun `onboarding offers grants and drops report rows`() {
        val checks = listOf(
            check("location", CheckKind.GRANT),
            check("riposte_suite", CheckKind.REPORT),
            check("listener_media", CheckKind.GRANT),
        )

        assertEquals(
            listOf("location", "listener_media"),
            FirstRunGate.grants(checks).map { it.id },
        )
    }

    @Test
    fun `a check is a grant unless it says otherwise`() {
        assertEquals(CheckKind.GRANT, check("location", CheckKind.GRANT).kind)
        assertEquals(
            listOf("location"),
            FirstRunGate.grants(listOf(DoctorCheck("location", "t", "d", false, "adb", null))).map { it.id },
        )
    }

    private fun check(id: String, kind: CheckKind) = DoctorCheck(
        id = id,
        title = id,
        detail = "why the launcher wants it",
        ok = false,
        adbCommand = "adb shell true",
        rootCommand = null,
        kind = kind,
    )
}
