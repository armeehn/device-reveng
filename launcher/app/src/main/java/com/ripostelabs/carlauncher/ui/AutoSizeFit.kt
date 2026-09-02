package com.ripostelabs.carlauncher.ui

/** One point on the shrink walk: the scale being tried, how many tries so far, and whether it stuck. */
data class FitStep(val scale: Float, val steps: Int, val fitted: Boolean)

/**
 * The step-down maths behind [AutoSizeText], kept free of Compose so it is unit-testable.
 *
 * Each text layout reports whether it overflowed; [advance] answers with the next scale to
 * try. The scale falls by a fixed ratio rather than a fixed amount, so a 72 sp speed readout
 * and a 14 sp badge take the same handful of passes, and the walk is capped at [MAX_STEPS]
 * layouts however low the floor is set. With the default floor:
 *
 *   1.00 → 0.90 → 0.81 → 0.73 → 0.66 → 0.62 (floor) → fitted, ellipsis from here on
 */
object AutoSizeFit {
    const val STEP_RATIO = 0.9f
    const val MAX_STEPS = 8
    const val DEFAULT_MIN_SCALE = 0.62f
    val START = FitStep(scale = 1f, steps = 0, fitted = false)

    fun advance(step: FitStep, overflowed: Boolean, minScale: Float): FitStep {
        if (step.fitted) {
            return step
        }

        // Stop at the first scale that fits, at the floor, or when the step budget is spent.
        val floor = minScale.coerceIn(0f, 1f)
        val exhausted = step.scale <= floor || step.steps >= MAX_STEPS
        if (!overflowed || exhausted) {
            return step.copy(fitted = true)
        }

        val next = (step.scale * STEP_RATIO).coerceAtLeast(floor)
        return FitStep(scale = next, steps = step.steps + 1, fitted = false)
    }
}
