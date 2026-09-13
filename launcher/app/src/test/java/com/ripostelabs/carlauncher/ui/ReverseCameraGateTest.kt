package com.ripostelabs.carlauncher.ui

import com.ripostelabs.carlauncher.ui.ReverseCameraGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class ReverseCameraGateTest {

    // (reverse, ownerActive, permissionGranted) → verdict. Every row of the truth table.
    private val table = listOf(
        Triple(false, false, false) to Verdict.HIDDEN,
        Triple(false, false, true) to Verdict.HIDDEN,
        Triple(false, true, false) to Verdict.HIDDEN,
        Triple(false, true, true) to Verdict.HIDDEN,
        Triple(true, false, false) to Verdict.HIDDEN,
        Triple(true, false, true) to Verdict.HIDDEN,
        Triple(true, true, false) to Verdict.NO_PERMISSION,
        Triple(true, true, true) to Verdict.PREVIEW,
    )

    @Test fun everyRowOfTheTable() {
        for ((inputs, expected) in table) {
            val (reverse, owner, granted) = inputs
            assertEquals(
                "reverse=$reverse owner=$owner granted=$granted",
                expected,
                ReverseCameraGate.decide(reverse, owner, granted),
            )
        }
    }

    @Test fun vendorSlotNeverShowsOurCamera() {
        // The vendor composites its own window there; a grant changes nothing.
        assertEquals(Verdict.HIDDEN, ReverseCameraGate.decide(reverse = true, ownerActive = false, permissionGranted = true))
    }

    @Test fun missingGrantIsAMessageNotAHide() {
        assertEquals(Verdict.NO_PERMISSION, ReverseCameraGate.decide(reverse = true, ownerActive = true, permissionGranted = false))
    }
}
