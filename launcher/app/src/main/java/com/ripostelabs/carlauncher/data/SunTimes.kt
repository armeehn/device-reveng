package com.ripostelabs.carlauncher.data

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * RAV4-169: day or night from the sun, as stock's SunTimesUtil (SunTimesUtil.java:34) does.
 *
 * Stock computes sunrise and sunset for the day and compares the clock. This asks the one
 * question directly: is the sun's centre more than [HORIZON_DEG] below the horizon now? That
 * is the same sunrise and sunset line, and it needs no special case for polar night or the
 * midnight sun, or for a time zone.
 *
 *     epoch ms ──▶ Julian century ──▶ declination + equation of time ──▶ hour angle ──▶ elevation
 *
 * The formulas are NOAA's solar calculator (Meeus, "Astronomical Algorithms", ch. 25), good to
 * about a minute of sunrise at mid latitudes.
 */
object SunTimes {

    /** Sunrise and sunset: the upper limb on the horizon, with standard refraction. */
    private const val HORIZON_DEG = -0.833

    private const val MS_PER_DAY = 86_400_000.0
    private const val MS_PER_MINUTE = 60_000.0
    private const val MINUTES_PER_DAY = 1_440.0
    private const val FULL_TURN_DEG = 360.0

    /** The earth turns one degree every four minutes. */
    private const val MINUTES_PER_DEG = 4.0
    private const val UNIX_EPOCH_JD = 2_440_587.5
    private const val J2000_JD = 2_451_545.0
    private const val DAYS_PER_CENTURY = 36_525.0

    fun isNight(epochMs: Long, latDeg: Double, lonDeg: Double): Boolean =
        elevation(epochMs, latDeg, lonDeg) < HORIZON_DEG

    /** The sun's elevation in degrees, before refraction. */
    fun elevation(epochMs: Long, latDeg: Double, lonDeg: Double): Double {
        val t = (epochMs / MS_PER_DAY + UNIX_EPOCH_JD - J2000_JD) / DAYS_PER_CENTURY

        // The sun's place on the ecliptic: mean longitude, mean anomaly, orbit eccentricity.
        val l0 = wrap(280.46646 + t * (36000.76983 + t * 0.0003032), FULL_TURN_DEG)
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val center = sinD(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sinD(2 * m) * (0.019993 - 0.000101 * t) + sinD(3 * m) * 0.000289
        val omega = 125.04 - 1934.136 * t
        val lambda = l0 + center - 0.00569 - 0.00478 * sinD(omega)

        // Tilt of the axis, then the declination and the equation of time (minutes).
        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = eps0 + 0.00256 * cosD(omega)
        val decl = Math.toDegrees(asin(sinD(eps) * sinD(lambda)))
        val y = tan(Math.toRadians(eps / 2)).let { it * it }
        val eqTime = MINUTES_PER_DEG * Math.toDegrees(
            y * sinD(2 * l0) - 2 * e * sinD(m) + 4 * e * y * sinD(m) * cosD(2 * l0) -
                0.5 * y * y * sinD(4 * l0) - 1.25 * e * e * sinD(2 * m),
        )

        // Local solar time gives the hour angle; the zenith follows from the spherical triangle.
        val utcMinutes = wrap(epochMs / MS_PER_MINUTE, MINUTES_PER_DAY)
        val solarMinutes = wrap(utcMinutes + eqTime + MINUTES_PER_DEG * lonDeg, MINUTES_PER_DAY)
        val hourAngle = solarMinutes / MINUTES_PER_DEG - FULL_TURN_DEG / 2
        val cosZenith = (sinD(latDeg) * sinD(decl) + cosD(latDeg) * cosD(decl) * cosD(hourAngle)).coerceIn(-1.0, 1.0)
        return FULL_TURN_DEG / 4 - Math.toDegrees(acos(cosZenith))
    }

    /** [value] into 0 until [range], negatives included. */
    private fun wrap(value: Double, range: Double) = ((value % range) + range) % range

    private fun sinD(deg: Double) = sin(Math.toRadians(deg))

    private fun cosD(deg: Double) = cos(Math.toRadians(deg))
}
