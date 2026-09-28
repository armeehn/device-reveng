package com.ripostelabs.carlauncher.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The reverse camera's dynamic guide lines, ported from the stock head unit so they bend the way
 * the stock ones do (RAV4-148: the old bicycle model divided an already-small angle by the
 * steering ratio and barely curved).
 *
 *     wheel degrees ─÷14, clamp 38, ÷2─▶ OEM index -19..19 ─▶ circle R = 270 / tan(index°)
 *       ─▶ 51 ground points per rail, 500 units back ─▶ pinhole camera (height 60, tilt 87°)
 *       ─▶ pixels in the OEM's 800x720 box ─▶ fractions of that box
 *
 * Source: eventcenter `ReverseCarTrackView.GetXYListFinal` / `drawarcLine` / `drawFlagLine`, fed
 * by `BackcarEvent.setTrackData` from canbus2 `HiworldCanParseToyota.OnHandleCanWheelTrackCmd`.
 * Tuning values are the unit's own resource set (sw480dp-land-hdpi = 1920x720 @240 dpi).
 * The OEM's "degrees" are not road-wheel degrees: full lock is only 19, which is why its lines
 * are fitted with extra fudge terms (widening and skew) rather than real geometry.
 */
object GuideTrajectory {

    data class Point(val x: Float, val y: Float)

    /** Red, yellow, green: how far back along the rail a point is. */
    enum class Zone { NEAR, MID, FAR }

    /** A short inward mark on a rail at a fixed distance. */
    data class Tick(val from: Point, val to: Point, val zone: Zone)

    /** Points are fractions of the OEM box (see [BOX_ASPECT]); ticks alternate left, right. */
    data class Rails(val left: List<Point>, val right: List<Point>, val ticks: List<Tick>)

    /** Width over height of the OEM drawing box. Fit it to the screen height, centred. */
    const val BOX_ASPECT = 800f / 720f

    /** Points per rail; [zoneOf] takes an index into it. */
    const val POINTS = 51

    /** +1 draws a positive angle like the stock unit does; -1 flips it. Verify in the car. */
    private const val STEER_SIGN = 1

    // canbus2 + eventcenter: the angle word /14, clamped to 38, halved (integer maths).
    private const val RAW_PER_COUNT = 14
    private const val MAX_COUNT = 38
    private const val COUNTS_PER_INDEX = 2

    // ReverseCarTrackView.initData: ground model, in the OEM's own units (cm-ish).
    private const val WHEELBASE = 270.0
    private const val TRACK_WIDTH = 150.0
    private const val PIVOT_BACK = 60.0
    private const val LENGTH = 500.0
    private const val CAMERA_HEIGHT = 60.0
    private const val FOCAL = 0.4
    private const val Y_BIAS = 28.0

    // Layout resources for this panel: box size, tilt, widening per point, skew, inset, red end.
    private const val BOX_W = 800.0
    private const val BOX_H = 720.0
    private const val CAMERA_TILT_DEG = 87.0
    private const val WIDEN_PER_POINT = 3.4
    private const val SKEW = 1.9
    private const val INSET = 44.0
    private const val RED_END = 7

    /** onDraw translates the canvas up this far before drawing. */
    private const val LIFT = 50.0

    /** Normalises the skew: `index * point * SKEW / 19` pixels. */
    private const val SKEW_DIVISOR = 19.0

    /** Sensor aspect the projection hard-codes (1280x720). */
    private const val SENSOR_ASPECT = 1280.0 / 720.0

    /** Last yellow point; green after it. */
    private const val MID_END = 30

    // drawFlagLine: marks at these points, 80 px long, shrinking with distance.
    private val TICK_POINTS = listOf(RED_END, 15, MID_END, POINTS - 1)
    private val TICK_SCALE = listOf(1.0, 0.8, 0.64, 0.512)
    private const val TICK_PX = 80.0

    /**
     * The OEM track index for a steering-wheel angle: -19..19, negative for a positive angle.
     * A negative word loses one count on the way (`(0xFFFF - raw) / 14`), kept for fidelity.
     */
    fun trackIndex(wheelDeg: Double): Int {
        val raw = wheelDeg.roundToInt()
        val magnitude = if (raw < 0) -raw - 1 else raw
        val index = minOf(magnitude / RAW_PER_COUNT, MAX_COUNT) / COUNTS_PER_INDEX
        return if (raw > 0) -index else index
    }

    fun zoneOf(point: Int): Zone = when {
        point <= RED_END -> Zone.NEAR
        point <= MID_END -> Zone.MID
        else -> Zone.FAR
    }

    fun rails(steeringDeg: Double, mirrored: Boolean): Rails {
        val index = STEER_SIGN * trackIndex(steeringDeg)
        val (groundL, groundR) = ground(index)
        val left = groundL.mapIndexed { i, g -> project(g, INSET, index, i) }
        val right = groundR.mapIndexed { i, g -> project(g, -INSET, index, i) }
        val rails = Rails(left.map(::fraction), right.map(::fraction), ticks(left, right))
        if (!mirrored) {
            return rails
        }

        // A mirrored feed flips the picture left to right; the lines and marks follow it.
        fun flip(p: Point) = Point(1f - p.x, p.y)
        val flipped = rails.ticks.map { Tick(flip(it.from), flip(it.to), it.zone) }
        val swapped = flipped.chunked(2).flatMap { it.reversed() }
        return Rails(rails.right.map(::flip), rails.left.map(::flip), swapped)
    }

    /**
     * Ground points of both rails, x across and y back from the camera. Straight ahead they
     * widen by [WIDEN_PER_POINT] per point; turning, each follows its circle round the pivot.
     */
    private fun ground(index: Int): Pair<List<Pair<Double, Double>>, List<Pair<Double, Double>>> {
        val left = mutableListOf<Pair<Double, Double>>()
        val right = mutableListOf<Pair<Double, Double>>()
        for (i in 0 until POINTS) {
            val widen = WIDEN_PER_POINT * i
            if (index == 0) {
                val y = Y_BIAS + i * LENGTH / POINTS
                left += (-TRACK_WIDTH / 2 - widen) to y
                right += (TRACK_WIDTH / 2 + widen) to y
                continue
            }

            val side = if (index < 0) -1.0 else 1.0
            val r = WHEELBASE / tan(abs(index) * PI / 180)
            val theta = i * LENGTH / r / POINTS
            left += arc(r - side * TRACK_WIDTH / 2, r, side, theta, -widen)
            right += arc(r + side * TRACK_WIDTH / 2, r, side, theta, widen)
        }
        return left to right
    }

    /** One point on a circle of [radius] around the turn centre at [r], per the OEM's algebra. */
    private fun arc(radius: Double, r: Double, side: Double, theta: Double, widen: Double): Pair<Double, Double> {
        val x = side * (radius * cos(theta) - r)
        val offset = x + side * r
        val y = sqrt(abs(radius * radius - offset * offset)) - PIVOT_BACK
        return (x - side * PIVOT_BACK * sin(theta) + widen) to (y + PIVOT_BACK * cos(theta) + Y_BIAS)
    }

    /** Ground point to OEM box pixels, with the rail's inset and the per-point skew. */
    private fun project(g: Pair<Double, Double>, inset: Double, index: Int, point: Int): Point {
        val (x, y) = g
        val tilt = CAMERA_TILT_DEG * PI / 180
        val down = atan(y / CAMERA_HEIGHT)
        val pitch = tilt - down
        val py = BOX_H * SENSOR_ASPECT * FOCAL * tan(pitch) + BOX_H / 2
        val px = BOX_W * FOCAL * cos(down) * x / CAMERA_HEIGHT / cos(pitch) + BOX_W / 2
        val skew = index * point * SKEW / SKEW_DIVISOR
        return Point((px + inset - skew).toFloat(), (py - LIFT).toFloat())
    }

    /**
     * Inward marks at [TICK_POINTS]. The direction is the left-to-right line without the skew,
     * as the OEM computes it, so the marks tilt with the rails on a turn.
     */
    private fun ticks(left: List<Point>, right: List<Point>): List<Tick> {
        val out = mutableListOf<Tick>()
        for ((k, i) in TICK_POINTS.withIndex()) {
            val l = left[i]
            val r = right[i]
            val a = atan(((r.y - l.y) / (r.x - l.x)).toDouble())
            val len = TICK_PX * TICK_SCALE[k]
            val dx = (cos(a) * len).toFloat()
            val dy = (sin(a) * len).toFloat()
            val zone = zoneOf(i)
            out += Tick(fraction(l), fraction(Point(l.x + dx, l.y + dy)), zone)
            out += Tick(fraction(r), fraction(Point(r.x - dx, r.y - dy)), zone)
        }
        return out
    }

    private fun fraction(p: Point) = Point((p.x / BOX_W).toFloat(), (p.y / BOX_H).toFloat())
}
