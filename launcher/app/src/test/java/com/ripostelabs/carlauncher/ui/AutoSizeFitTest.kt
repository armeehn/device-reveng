package com.ripostelabs.carlauncher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSizeFitTest {
    private val floor = AutoSizeFit.DEFAULT_MIN_SCALE

    @Test fun textThatFitsStaysAtFullSize() {
        val step = AutoSizeFit.advance(AutoSizeFit.START, overflowed = false, minScale = floor)
        assertEquals(1f, step.scale, 0f)
        assertTrue(step.fitted)
    }

    @Test fun overflowStepsDownByAFixedRatio() {
        val first = AutoSizeFit.advance(AutoSizeFit.START, overflowed = true, minScale = floor)
        val second = AutoSizeFit.advance(first, overflowed = true, minScale = floor)
        assertEquals(AutoSizeFit.STEP_RATIO, first.scale, 1e-6f)
        assertEquals(AutoSizeFit.STEP_RATIO * AutoSizeFit.STEP_RATIO, second.scale, 1e-6f)
        assertFalse(second.fitted)
        assertEquals(2, second.steps)
    }

    @Test fun floorIsReachedThenEllipsisTakesOver() {
        var step = AutoSizeFit.START
        while (!step.fitted) {
            step = AutoSizeFit.advance(step, overflowed = true, minScale = floor)
        }
        assertEquals(floor, step.scale, 0f)
        // 0.9^5 < 0.62 so the floor lands on the fifth pass; the sixth marks it fitted.
        assertEquals(5, step.steps)
    }

    @Test fun walkIsCappedWhenTheFloorIsFarAway() {
        var step = AutoSizeFit.START
        while (!step.fitted) {
            step = AutoSizeFit.advance(step, overflowed = true, minScale = 0f)
        }
        assertEquals(AutoSizeFit.MAX_STEPS, step.steps)
        assertTrue(step.scale > 0f)
    }

    @Test fun fittedIsSticky() {
        val fitted = FitStep(scale = 0.9f, steps = 1, fitted = true)
        assertEquals(fitted, AutoSizeFit.advance(fitted, overflowed = true, minScale = floor))
    }

    @Test fun floorAboveOneNeverShrinks() {
        val step = AutoSizeFit.advance(AutoSizeFit.START, overflowed = true, minScale = 1.5f)
        assertEquals(1f, step.scale, 0f)
        assertTrue(step.fitted)
    }
}
