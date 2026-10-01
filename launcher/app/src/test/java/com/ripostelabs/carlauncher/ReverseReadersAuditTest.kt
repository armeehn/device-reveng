package com.ripostelabs.carlauncher

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Audit 2026-09-30 A2: with "Wire or CAN gear" (#347) and no wire fitted, the raw wire
 * `carEvents.reverse` never rises. Whatever must yield to the reverse picture (panel light,
 * screensaver, volume popup) reads `inReverse`, the wire or the picture. Only the readers
 * below may still read the raw wire, each the number of times given.
 */
class ReverseReadersAuditTest {

    @Test
    fun onlyTheAllowedReadersTouchTheRawWire() {
        val readers = File(MAIN_ACTIVITY).readLines()
            .map { it.trim() }
            .filter { "carEvents.reverse" in it }
            .groupingBy { it }
            .eachCount()

        assertEquals("read inReverse instead", ALLOWED, readers)
    }

    private companion object {
        const val MAIN_ACTIVITY = "src/main/java/com/ripostelabs/carlauncher/MainActivity.kt"

        val ALLOWED = mapOf(
            // The picture itself: the wire is one of its inputs.
            "val picture = combine(carEvents.reverse, carEvents.canGear, carEvents.speedKmh) { wire, gear, speed ->" to 1,
            // Focus save around the vendor's own reverse window (stock and 0.1 slots).
            "carEvents.reverse.collect { engaged ->" to 1,
            // The maneuvering strips in the activity, which the vendor's window covers.
            "val reverse by carEvents.reverse.collectAsStateWithLifecycle()" to 1,
        )
    }
}
