package com.ripostelabs.carlauncher.carlib

import com.ripostelabs.carlauncher.carlib.VehicleSnapshot.Companion.fold
import com.ripostelabs.carlauncher.carlib.VehicleSnapshot.Companion.foldRaw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The raw bus and the vendor MCU use DIFFERENT door bit layouts. Reading one with the other's
 * constants is exactly how "the driver door opening thinks it's the passenger" happened before,
 * and it was only caught in a car.
 *
 * So these tests assert the TILE TEXT, not the byte. That couples the packing here to
 * VehicleTiles' own copy of the layout, which means a divergence between them fails at a desk
 * instead of misreporting a door to someone sitting in the driver's seat.
 */
class VehicleSnapshotRawTest {

    private val t0 = 1_000L

    private fun doors(
        driver: Boolean = false,
        passenger: Boolean = false,
        rearLeft: Boolean = false,
        rearRight: Boolean = false,
        tailgate: Boolean = false,
        hood: Boolean = false,
    ) = RawCanSignal.Doors(driver, passenger, rearLeft, rearRight, tailgate, hood)

    private fun tileFor(sig: RawCanSignal, label: String): String? =
        VehicleTiles.tilesFor(VehicleSnapshot().foldRaw(sig, t0), t0)
            .firstOrNull { it.label == label }?.value

    @Test
    fun `a raw driver door reads as the driver, not the passenger`() {
        assertEquals("driver", tileFor(doors(driver = true), "Open"))
    }

    @Test
    fun `a raw passenger door reads as the passenger`() {
        assertEquals("passenger", tileFor(doors(passenger = true), "Open"))
    }

    @Test
    fun `rear left and rear right do not swap either`() {
        assertEquals("rear left", tileFor(doors(rearLeft = true), "Open"))
        assertEquals("rear right", tileFor(doors(rearRight = true), "Open"))
    }

    @Test
    fun `tailgate and bonnet map through`() {
        assertEquals("tailgate", tileFor(doors(tailgate = true), "Open"))
        assertEquals("bonnet", tileFor(doors(hood = true), "Open"))
    }

    @Test
    fun `several openings are listed in reading order`() {
        assertEquals(
            "driver, rear right",
            tileFor(doors(driver = true, rearRight = true), "Open"),
        )
    }

    @Test
    fun `an opening ajar is an alert`() {
        val tiles = VehicleTiles.tilesFor(VehicleSnapshot().foldRaw(doors(driver = true), t0), t0)

        assertEquals(VehicleTiles.Emphasis.ALERT, tiles.single { it.label == "Open" }.emphasis)
    }

    @Test
    fun `a running fan carries its raw level`() {
        val snap = VehicleSnapshot().foldRaw(RawCanSignal.Fan(running = true, level = 5), t0)

        assertEquals(5, snap.int(VehicleSnapshot.Field.FAN_STEP, t0))
    }

    // ── Negative controls ───────────────────────────────────────────────────────────────────────

    @Test
    fun `all doors shut produces no tile at all`() {
        // Never "0 open": an absent alert and a cleared alert must look the same to the reader.
        assertNull(tileFor(doors(), "Open"))
    }

    @Test
    fun `a stopped fan reports zero rather than its raw byte`() {
        // `level` is meaningless when the blower is off; passing it through would invent a step.
        val snap = VehicleSnapshot().foldRaw(RawCanSignal.Fan(running = false, level = 7), t0)

        assertEquals(0, snap.int(VehicleSnapshot.Field.FAN_STEP, t0))
    }

    @Test
    fun `an undecoded id contributes nothing`() {
        val snap = VehicleSnapshot().foldRaw(RawCanSignal.Unknown(0x123), t0)

        assertTrue(VehicleTiles.tilesFor(snap, t0).isEmpty())
    }

    @Test
    fun `a signal the decoder never emits is not folded`() {
        // Declared in RawCanSignal for a future tap. Folding it would put an unverified reading
        // on screen the moment someone wired the decoder up.
        val snap = VehicleSnapshot().foldRaw(RawCanSignal.Cruise(active = true, adaptiveEngaged = false), t0)

        assertTrue(VehicleTiles.tilesFor(snap, t0).isEmpty())
    }

    @Test
    fun `raw and MCU doors agree on the same physical door`() {
        // Two protocols, two byte layouts, one car. If these ever disagree the packing is wrong.
        val fromRaw = VehicleSnapshot().foldRaw(doors(driver = true), t0)
        val mcuDriverBit = 0x40
        val fromMcu = VehicleSnapshot().fold(
            CanSignal.BasicStatus(
                swcButtonId = 0,
                swcPressed = false,
                swcAction = HiworldCanDecoder.SwcAction.UNKNOWN,
                doorBits = mcuDriverBit,
                doorFrontLeftOpen = true,
                doorFrontRightOpen = false,
                doorRearRightOpen = false,
                doorRearLeftOpen = false,
                tailgateOpen = false,
                hoodOpen = false,
                steerAngleDeg = 0.0,
            ),
            t0,
        )

        assertEquals(
            VehicleTiles.tilesFor(fromMcu, t0).first { it.label == "Open" }.value,
            VehicleTiles.tilesFor(fromRaw, t0).first { it.label == "Open" }.value,
        )
    }
}
