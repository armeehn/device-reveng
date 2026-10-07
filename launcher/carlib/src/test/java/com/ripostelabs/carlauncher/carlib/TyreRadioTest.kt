package com.ripostelabs.carlauncher.carlib

import com.ripostelabs.carlauncher.carlib.CarEvents.Motion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class TyreRadioTest {

    private val toyota = """{"time" : "2026-10-02 19:01:04", "model" : "Toyota", "type" : "TPMS", "id" : "d8609be2", "status" : 128, "pressure_PSI" : 31.750, "temperature_C" : 17.000, "mic" : "CRC"}"""

    @Test
    fun parsesToyotaPsiToKpa() {
        val r = TyreRadioParser.parse(toyota, 5L)!!

        assertEquals("d8609be2", r.id)
        assertEquals("Toyota", r.model)
        assertEquals(218.9, r.kpa, 0.1)
        assertEquals(17.0, r.tempC!!, 0.0)
        assertEquals(31.75, r.psi, 0.001)
    }

    @Test
    fun parsesKpaAndBarAndNumericIds() {
        val kpa = TyreRadioParser.parse("""{"type":"TPMS","model":"Abarth","id":"bc4560ff","pressure_kPa":310.5}""", 0)!!
        val bar = TyreRadioParser.parse("""{"type":"TPMS","model":"X","id":1234,"pressure_bar":2.4}""", 0)!!

        assertEquals(310.5, kpa.kpa, 0.0)
        assertEquals(240.0, bar.kpa, 0.001)
        assertEquals("1234", bar.id)
    }

    @Test
    fun dropsNonTpmsAndNoise() {
        assertNull(TyreRadioParser.parse("""{"model":"Efergy-e2CT","id":33282,"current":0.02}""", 0))
        assertNull(TyreRadioParser.parse("Found Rafael Micro R820T tuner", 0))
        assertNull(TyreRadioParser.parse("""{"type":"TPMS","model":"Ford","id":"36050818"}""", 0))
    }

    /** The owner's 2026-10-02 drive: four Toyota sensors all along, three neighbours briefly. */
    @Test
    fun learnsOwnSensorsFromRealDrive() {
        val set = TyreSensorSet()
        val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

        val stream = javaClass.getResourceAsStream("/tpms-drive-20261002.jsonl")!!
        stream.bufferedReader().forEachLine { line ->
            val at = clock.parse(line.substringAfter("\"time\" : \"").take(19))!!.time
            TyreRadioParser.parse(line, at)?.let { set.record(it, Motion.UNKNOWN) }
        }

        assertEquals(setOf("d8609be2", "d8667e9f", "d8683855", "d8620c57"), set.ownIds())
        assertEquals(27.5, set.own().first { it.id == "d8683855" }.psi, 0.3)
    }

    @Test
    fun parkedReadingsDoNotTeach() {
        val set = TyreSensorSet()

        for (minute in 0 until 10) {
            set.record(reading("neighbour", 220.0, minute * MINUTE), Motion.PARKED)
        }

        assertTrue(set.ownIds().isEmpty())
    }

    @Test
    fun forgetsSensorUnheardForAWeek() {
        val set = TyreSensorSet(mapOf("old" to 0L))

        set.record(reading("x", 220.0, 8 * DAY), Motion.MOVING)

        assertTrue("old" !in set.ownIds())
    }

    @Test
    fun keepsAtMostFiveMostRecent() {
        val now = 10 * MINUTE
        val set = TyreSensorSet((1..5).associate { "s$it" to it * 1000L })

        for (minute in 0 until 4) {
            set.record(reading("new", 230.0, now + minute * MINUTE), Motion.MOVING)
        }

        assertEquals(5, set.ownIds().size)
        assertTrue("new" in set.ownIds())
        assertTrue("s1" !in set.ownIds())
    }

    @Test
    fun healthFlagsLowAndSoftTyres() {
        val others = listOf(219.0, 220.0, 218.0).mapIndexed { i, k -> reading("o$i", k, 0) }

        assertEquals(TyreState.OK, TyreHealth.of(reading("a", 219.0, 0), others))
        assertEquals(TyreState.SOFT, TyreHealth.of(reading("b", 189.6, 0), others))
        assertEquals(TyreState.LOW, TyreHealth.of(reading("c", 170.0, 0), others))
        assertEquals(TyreState.OK, TyreHealth.of(reading("d", 219.0, 0), emptyList()))
    }

    /** rtl_433 prints "Tuned to" only as a log message, and -F json alone silences those. */
    @Test
    fun commandKeepsLogMessagesForTunedMarker() {
        val args = TyreRadioLink.command("/x/rtl_433").split(" ")

        assertTrue(args.windowed(2).contains(listOf("-F", "json")))
        assertTrue(args.windowed(2).contains(listOf("-F", "log")))
    }

    private fun reading(id: String, kpa: Double, at: Long) = TyreReading(id, "Toyota", kpa, null, at)

    private companion object {
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE
    }
}
