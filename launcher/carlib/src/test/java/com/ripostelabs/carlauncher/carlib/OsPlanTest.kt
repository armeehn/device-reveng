package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which OS payload the unit takes and when it may download, apply and reboot. A payload is 1.8 GB
 * and a reboot changes the running system, so every step is gated harder than an APK update.
 */
class OsPlanTest {

    private val plan = OsPlan()
    private val accOnAt = 1_000_000L
    private val settled = accOnAt + OtaPlan.SETTLE_MS
    private val roomy = OsPlan.MIN_FREE_BYTES

    private val running = OsPlan.Running("0.2+20261005.vc1055", carOwner = true, bench = false)

    private fun car(speed: Int = 0, gear: Gear? = Gear.PARK, acc: NoisePlan.Acc = NoisePlan.Acc.ON) =
        NoisePlan.Car(acc, speed, gear, NoisePlan.Call.NONE, NoisePlan.Session.NONE, otherRecorders = 0, fanLevel = 0, fanMax = 0)

    private fun row(version: String, carOwner: Boolean = true, bench: Boolean = false, sha: String = "a".repeat(64)) =
        ReleaseManifest.Os(version, "os/$version/payload.bin", sha, 1_900_000_000L, HEADERS, "gsi", carOwner, bench)

    private fun manifest(vararg rows: ReleaseManifest.Os) = ReleaseManifest(null, emptyList(), rows.toList())

    // --- version ----------------------------------------------------------------------------

    @Test
    fun `version reads milestone, build day and launcher code`() {
        assertEquals(OsPlan.Version("0.2", 20261005, 1055), OsPlan.Version.parse("0.2+20261005.vc1055"))
        assertNull(OsPlan.Version.parse("0.2-bench"))
        assertNull(OsPlan.Version.parse(""))
    }

    // --- pick -------------------------------------------------------------------------------

    @Test
    fun `newest later build of the same milestone and flavour is picked`() {
        val m = manifest(row("0.2+20261006.vc1056"), row("0.2+20261009.vc1060"), row("0.2+20261001.vc1040"))
        assertEquals("0.2+20261009.vc1060", plan.pick(m, running, emptySet())?.version)
    }

    @Test
    fun `same or older build is never offered`() {
        assertNull(plan.pick(manifest(row("0.2+20261005.vc1055"), row("0.2+20261004.vc1070")), running, emptySet()))
    }

    @Test
    fun `a new day with the same launcher is a newer build`() {
        assertEquals("0.2+20261006.vc1055", plan.pick(manifest(row("0.2+20261006.vc1055")), running, emptySet())?.version)
    }

    @Test
    fun `another milestone is a USB job with a wipe, never over the air`() {
        assertNull(plan.pick(manifest(row("0.3+20261009.vc1060")), running, emptySet()))
    }

    @Test
    fun `a bench image never reaches the car and a car image never loses the MCU`() {
        assertNull(plan.pick(manifest(row("0.2+20261009.vc1060", bench = true)), running, emptySet()))
        assertNull(plan.pick(manifest(row("0.2+20261009.vc1060", carOwner = false)), running, emptySet()))
    }

    @Test
    fun `a refused payload is skipped, the next one is taken`() {
        val bad = row("0.2+20261009.vc1060")
        val m = manifest(bad, row("0.2+20261008.vc1059"))
        assertEquals("0.2+20261008.vc1059", plan.pick(m, running, setOf(OsPlan.key(bad)))?.version)
    }

    @Test
    fun `a hand-named running build is left alone`() {
        val custom = running.copy(version = "0.2-bench")
        assertNull(plan.pick(manifest(row("0.2+20261009.vc1060")), custom, emptySet()))
    }

    // --- gating -----------------------------------------------------------------------------

    @Test
    fun `download only on Wi-Fi with room to spare`() {
        assertEquals(OsPlan.Hold.NONE, plan.downloadHold(DataBudget.Link.UNMETERED, roomy, 0))
        assertEquals(OsPlan.Hold.OFFLINE, plan.downloadHold(DataBudget.Link.NONE, roomy, 0))
        assertEquals(OsPlan.Hold.METERED, plan.downloadHold(DataBudget.Link.METERED, roomy, 0))
        assertEquals(OsPlan.Hold.LOW_SPACE, plan.downloadHold(DataBudget.Link.UNMETERED, roomy - 1, 0))
    }

    @Test
    fun `room is counted after the rest of the payload`() {
        assertEquals(OsPlan.Hold.LOW_SPACE, plan.downloadHold(DataBudget.Link.UNMETERED, roomy + 99, 100))
        assertEquals(OsPlan.Hold.NONE, plan.downloadHold(DataBudget.Link.UNMETERED, roomy + 100, 100))
    }

    @Test
    fun `apply only when parked and settled, with room for the snapshots`() {
        assertEquals(OsPlan.Hold.NONE, plan.applyHold(car(), accOnAt, settled, roomy))
        assertEquals(OsPlan.Hold.NOT_PARKED, plan.applyHold(car(gear = Gear.DRIVE), accOnAt, settled, roomy))
        assertEquals(OsPlan.Hold.SETTLING, plan.applyHold(car(), accOnAt, settled - 1, roomy))
        assertEquals(OsPlan.Hold.NO_CAR, plan.applyHold(null, accOnAt, settled, roomy))
        assertEquals(OsPlan.Hold.LOW_SPACE, plan.applyHold(car(), accOnAt, settled, roomy - 1))
    }

    @Test
    fun `reboot only on the ACC off edge, once the update is staged`() {
        val staged = UpdateEngine.Status.UPDATED_NEED_REBOOT
        assertTrue(plan.rebootDue(staged, NoisePlan.Acc.ON, NoisePlan.Acc.OFF))
        assertFalse(plan.rebootDue(staged, NoisePlan.Acc.OFF, NoisePlan.Acc.OFF))
        assertFalse(plan.rebootDue(staged, NoisePlan.Acc.ON, NoisePlan.Acc.ON))
        assertFalse(plan.rebootDue(UpdateEngine.Status.DOWNLOADING, NoisePlan.Acc.ON, NoisePlan.Acc.OFF))
    }

    private companion object {
        const val HEADERS = "FILE_HASH=qg==\nFILE_SIZE=1900000000\nMETADATA_HASH=bWV0YQ==\nMETADATA_SIZE=12\n"
    }
}
