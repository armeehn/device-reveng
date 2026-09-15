package com.ripostelabs.carlauncher.carlib

import com.ripostelabs.carlauncher.carlib.VehicleSnapshot.Companion.fold
import com.ripostelabs.carlauncher.carlib.VehicleTiles.Emphasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what the vehicle screen shows, and — more importantly — what it refuses to show.
 */
class VehicleTilesTest {

    private val t0 = 1_000L
    private fun snap(vararg sigs: CanSignal): VehicleSnapshot =
        sigs.fold(VehicleSnapshot()) { acc, s -> acc.fold(s, t0) }

    private fun tiles(vararg sigs: CanSignal) = VehicleTiles.tilesFor(snap(*sigs), now = t0)
    private fun labels(vararg sigs: CanSignal) = tiles(*sigs).map { it.label }

    private fun info(rpm: Int, coolant: Int?) =
        CanSignal.VehicleInfo(rpm = rpm, speedRaw = 0, speedKmh = 0.0, coolantC = coolant)

    private fun status(doorBits: Int) = CanSignal.BasicStatus(
        swcButtonId = 0, swcPressed = false,
        swcAction = HiworldCanDecoder.SwcAction.UNKNOWN,
        doorBits = doorBits,
        doorFrontLeftOpen = doorBits and 0x40 != 0,
        doorFrontRightOpen = doorBits and 0x80 != 0,
        doorRearRightOpen = doorBits and 0x20 != 0,
        doorRearLeftOpen = doorBits and 0x10 != 0,
        tailgateOpen = doorBits and 0x08 != 0,
        hoodOpen = doorBits and 0x04 != 0,
        steerAngleDeg = 0.0,
    )

    /** No car is an empty list, not a dashboard of zeroes. */
    @Test
    fun `an empty snapshot produces no tiles at all`() {
        assertEquals(emptyList<VehicleTiles.Tile>(), VehicleTiles.tilesFor(VehicleSnapshot(), now = t0))
    }

    @Test
    fun `a field with no source contributes nothing rather than a dash`() {
        // rpm only: no coolant tile should appear, not a "Coolant –" one.
        assertEquals(listOf("Engine"), labels(info(2000, null)))
    }

    @Test
    fun `values carry their units`() {
        val m = tiles(info(2500, 90)).associate { it.label to it.value }
        assertEquals("2500 rpm", m["Engine"])
        assertEquals("90°C", m["Coolant"])
    }

    /** Doors closed must not produce an "Open: none" tile. */
    @Test
    fun `all shut shows no openings tile`() {
        assertTrue("Open" !in labels(status(0x00)))
    }

    @Test
    fun `openings are named, using the corrected bit map`() {
        val m = tiles(status(0x40)).associate { it.label to it.value }
        assertEquals("driver", m["Open"])
        // 0x80 is the PASSENGER on this opcode; the vendor swaps 6 and 7 while repacking.
        assertEquals("passenger", tiles(status(0x80)).first { it.label == "Open" }.value)
        assertEquals("tailgate, bonnet", tiles(status(0x0C)).first { it.label == "Open" }.value)
    }

    @Test
    fun `an opening is an alert, an ordinary reading is not`() {
        assertEquals(Emphasis.ALERT, tiles(status(0x40)).first { it.label == "Open" }.emphasis)
        assertEquals(Emphasis.NORMAL, tiles(info(2000, 90)).first { it.label == "Engine" }.emphasis)
    }

    /** A sleeping sensor keeps its place so the reader can tell which wheel is unknown. */
    @Test
    fun `tyres preserve gaps in position`() {
        val v = tiles(CanSignal.Tpms(220, null, 210, 205, null)).first { it.label == "Tyres" }.value
        assertEquals("220 / – / 210 / 205 kPa", v)
    }

    @Test
    fun `no tyre readings at all shows no tyre tile`() {
        assertTrue("Tyres" !in labels(CanSignal.Tpms(null, null, null, null, null)))
    }

    /** Nothing detected must not render as "0 cm", which reads as a collision. */
    @Test
    fun `radar with nothing detected shows no distance tile`() {
        val quiet = CanSignal.ParkingRadar(rearCm = List(4) { null }, frontCm = List(4) { null })
        assertTrue("Nearest object" !in labels(quiet))
    }

    @Test
    fun `radar reports the closest of either end`() {
        val r = CanSignal.ParkingRadar(
            rearCm = listOf(90, null, null, null),
            frontCm = listOf(null, 60, null, null),
        )
        assertEquals("60 cm", tiles(r).first { it.label == "Nearest object" }.value)
    }

    /**
     * The climate decode has never been verified against a real vehicle and the owner reported it
     * does not track the controls. Every climate string must say so; dropping the caveat would
     * present a known-doubtful reading as fact.
     */
    @Test
    fun `climate always carries its unverified caveat`() {
        val on = CanSignal.Climate(
            on = true, acOn = true, acMax = false, auto = false, dual = false, eco = false,
            recirculate = false, airPurifier = false, rearDefog = false, maxFront = false,
            seatHeatLeft = 0, seatHeatRight = 0, seatCoolLeft = 0, seatCoolRight = 0,
            ventDirectionRaw = 1, fanStep = 3, rearFanStep = 0,
            leftTempC = 21.0, rightTempC = 21.0, tempUnitCelsius = true,
        )
        val v = tiles(on).first { it.label == "Climate" }.value
        assertTrue("climate must be labelled unverified, was: $v", v.contains("unverified"))
        assertTrue(v.contains("21.0°C"))
        assertTrue(v.contains("fan 3"))
        assertTrue(tiles(on.copy(on = false)).first { it.label == "Climate" }.value.contains("unverified"))
    }

    /** Reverse is labelled raw because the ACC gate is not applied in the decoder. */
    @Test
    fun `reverse says raw so the tile does not overclaim`() {
        val r = CanSignal.SysEvent(reverseRaw = true, discPresent = false, usbPresent = false, raw = 2)
        val tile = tiles(r).first { it.label == "Reverse" }
        assertTrue(tile.value.contains("raw"))
        assertEquals(Emphasis.ALERT, tile.emphasis)
        assertTrue("Reverse" !in labels(r.copy(reverseRaw = false)))
    }

    /** Trip fields come from the vendor parser only, so they carry the caveat like climate. */
    @Test
    fun `trip tiles are labelled unverified and formatted as the cluster shows them`() {
        val m = tiles(CanSignal.TripInfo(rangeToEmptyKm = 300, elapsedMin = 95, avgSpeedKmh = 42))
            .associate { it.label to it.value }
        assertEquals("300 km", m["Range"])
        assertEquals("1 h 35 min (unverified)", m["Trip time"])
        assertEquals("42 km/h (unverified)", m["Average speed"])
    }

    @Test
    fun `trip time under an hour omits the hours`() {
        val m = tiles(CanSignal.TripInfo(rangeToEmptyKm = null, elapsedMin = 7)).associate { it.label to it.value }
        assertEquals("7 min (unverified)", m["Trip time"])
    }

    /** Fuel figures are drawn in the unit the car reports, one decimal, with the caveat. */
    @Test
    fun `trip fuel tiles carry the car's unit and the caveat`() {
        val m = tiles(CanSignal.TripInfo(rangeToEmptyKm = null, tripFuel = 5.4, bestFuel = 4.8,
                fuelUnit = CanSignal.FuelUnit.L_PER_100KM))
            .associate { it.label to it.value }
        assertEquals("5.4 L/100km (unverified)", m["Trip fuel"])
        assertEquals("4.8 L/100km (unverified)", m["Best fuel"])
    }

    @Test
    fun `trip fuel in miles per gallon says which gallon`() {
        val m = tiles(CanSignal.TripInfo(rangeToEmptyKm = null, tripFuel = 41.0,
                fuelUnit = CanSignal.FuelUnit.MPG_UK))
            .associate { it.label to it.value }
        assertEquals("41.0 MPG (UK) (unverified)", m["Trip fuel"])
        assertTrue("Best fuel" !in m)
    }

    /** A page with the trip words at 0xFFFF contributes no trip tiles, not zeroes. */
    @Test
    fun `absent trip fields produce no trip tiles`() {
        val l = labels(CanSignal.TripInfo(rangeToEmptyKm = 300))
        assertEquals(listOf("Range"), l)
    }

    /** Stale data must disappear from the screen, not linger. */
    @Test
    fun `everything vanishes once stale`() {
        val s = snap(info(2000, 90), CanSignal.Tpms(220, 220, 210, 210, null))
        val late = t0 + VehicleSnapshot.STALE_AFTER_MS + 1
        assertEquals(emptyList<VehicleTiles.Tile>(), VehicleTiles.tilesFor(s, now = late))
    }
}
