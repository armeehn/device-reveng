package com.ripostelabs.carlauncher.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where the rear corners of the car go when it reverses at a steering angle, as screen
 * fractions (0..1 across, 0..1 down) for the reverse camera's guide lines.
 *
 *     steering wheel ─÷ ratio─▶ front wheel angle ─▶ turning radius R = wheelbase / tan(angle)
 *     each rear corner swings on a circle around (R, 0) at the rear axle ─▶ ground points behind
 *     the bumper ─▶ the perspective fitted to the static lines ─▶ screen fractions
 *
 * Straight ahead (0°) the rails land exactly on the static lines (0.22 / 0.78 at the bumper,
 * 0.36 / 0.64 at the top), so switching the dynamic mode on changes nothing until the wheel turns.
 * Which way a positive angle bends is [STEER_SIGN]: set from the car, not from a datasheet.
 */
object GuideTrajectory {

    data class Point(val x: Float, val y: Float)
    data class Rails(val left: List<Point>, val right: List<Point>)

    // RAV4 XA50 (2019+). Width and wheelbase from the spec sheet; overhang and ratio approximate.
    private const val WHEELBASE_M = 2.69
    private const val HALF_WIDTH_M = 0.9275
    private const val REAR_OVERHANG_M = 0.97
    private const val STEERING_RATIO = 13.9

    /** +1 or -1: which side a positive CAN-box angle bends the lines to. Verify in the car. */
    private const val STEER_SIGN = 1.0

    /** Lines run from the bumper to this far back along the path. */
    private const val NEAR_M = 0.3
    private const val FAR_M = 3.0
    private const val SAMPLES = 24

    // Perspective fitted to the static lines: across = 0.5 ± k·x/(d + D0), down = V0 + b/(d + D0).
    private const val D0 = 2.4
    private const val ACROSS_K = 0.28 * (NEAR_M + D0) / HALF_WIDTH_M
    private const val DOWN_B = (0.98 - 0.45) / (1 / (NEAR_M + D0) - 1 / (FAR_M + D0))
    private const val DOWN_V0 = 0.98 - DOWN_B / (NEAR_M + D0)

    /** Below this front-wheel angle the path is treated as straight (R would overflow). */
    private const val STRAIGHT_RAD = 1e-4

    fun rails(steeringDeg: Double, mirrored: Boolean): Rails {
        val wheel = STEER_SIGN * steeringDeg / STEERING_RATIO * PI / 180
        val left = path(-HALF_WIDTH_M, wheel)
        val right = path(HALF_WIDTH_M, wheel)
        if (!mirrored) {
            return Rails(left, right)
        }

        // A mirrored feed flips the picture left to right; the lines follow it.
        return Rails(right.map { Point(1f - it.x, it.y) }, left.map { Point(1f - it.x, it.y) })
    }

    /** One rear corner at lateral [x0], swept backwards along the path. */
    private fun path(x0: Double, wheel: Double): List<Point> {
        val points = mutableListOf<Point>()
        for (i in 0..SAMPLES) {
            // Distance the rear axle travels, chosen so the corner starts NEAR_M behind the bumper.
            val s = NEAR_M + (FAR_M - NEAR_M) * i / SAMPLES
            val (x, d) = corner(x0, s, wheel)
            points += project(x, d)
        }
        return points
    }

    /**
     * The corner after the rear axle has reversed [s] metres, in bumper coordinates
     * (x across, d behind the bumper). Rear-axle frame: y backwards, ICR at (R, 0).
     */
    private fun corner(x0: Double, s: Double, wheel: Double): Pair<Double, Double> {
        if (abs(wheel) < STRAIGHT_RAD) {
            return x0 to s
        }

        val r = WHEELBASE_M / tan(wheel)
        val phi = -s / r
        val px = x0 - r
        val py = REAR_OVERHANG_M
        val x = r + px * cos(phi) - py * sin(phi)
        val y = px * sin(phi) + py * cos(phi)
        return x to (y - REAR_OVERHANG_M)
    }

    private fun project(x: Double, d: Double): Point {
        val depth = d.coerceAtLeast(0.0) + D0
        return Point((0.5 + ACROSS_K * x / depth).toFloat(), (DOWN_V0 + DOWN_B / depth).toFloat())
    }
}
