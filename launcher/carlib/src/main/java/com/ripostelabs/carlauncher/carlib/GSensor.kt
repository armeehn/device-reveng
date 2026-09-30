package com.ripostelabs.carlauncher.carlib

import kotlin.math.acos
import kotlin.math.sqrt

/**
 * One MCU `8E` G-sensor sample. We used to call the opcode `RADAR_3DH`; the stock gyro page
 * (`zxwlib/GyroScopeWithCompassView.java:940-950`) reads it as x, y, z little-endian pairs from
 * body bytes 1..6, which are payload bytes 0..5 here.
 *
 * Stock builds each axis as `b[lo] + b[hi] * 256` over signed bytes, which is off by 256 when
 * the low byte is 0x80 or more. We read a clean signed 16-bit word instead.
 *
 * Example from the car (2026-09-20): `60 C2 30 FF 20 06` = x -15776, y -208, z 1568.
 * Pure Kotlin so it is unit-testable; nothing on the car consumes it yet.
 */
data class GSensor(val x: Int, val y: Int, val z: Int) {

    private val norm: Double get() = sqrt(x.toDouble() * x + y.toDouble() * y + z.toDouble() * z)

    /** Stock `mXdegree`: angle between the gravity vector and the x axis, 0..180. */
    val xDeg: Double get() = axisDeg(x)

    /** Stock `mYdegree`. */
    val yDeg: Double get() = axisDeg(y)

    /** Stock `mZdegree`. */
    val zDeg: Double get() = axisDeg(z)

    private fun axisDeg(axis: Int): Double = Math.toDegrees(acos(axis / norm))

    companion object {
        private const val PAYLOAD_SIZE = 6

        /** Null when the payload is short or all zero (no direction to take an angle from). */
        fun decode(payload: ByteArray): GSensor? {
            if (payload.size < PAYLOAD_SIZE) {
                return null
            }

            val g = GSensor(s16le(payload, 0), s16le(payload, 2), s16le(payload, 4))
            if (g.x == 0 && g.y == 0 && g.z == 0) {
                return null
            }
            return g
        }

        private fun s16le(p: ByteArray, lo: Int): Int =
            ((p[lo + 1].toInt() shl 8) or (p[lo].toInt() and 0xFF)).toShort().toInt()
    }
}
