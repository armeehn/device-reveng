package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the unit may install, what it installs, and in which order. An install restarts the app
 * being replaced, and replacing the launcher blanks the screen, so every install waits for a
 * parked car with nobody on the phone or in CarPlay, a minute after ACC on.
 */
class OtaPlanTest {

    private val plan = OtaPlan()
    private val accOnAt = 1_000_000L
    private val settled = accOnAt + OtaPlan.SETTLE_MS

    private fun car(
        acc: NoisePlan.Acc = NoisePlan.Acc.ON,
        speed: Int = 0,
        gear: Gear? = Gear.PARK,
        call: NoisePlan.Call = NoisePlan.Call.NONE,
        carPlay: NoisePlan.Session = NoisePlan.Session.NONE,
    ) = NoisePlan.Car(acc, speed, gear, call, carPlay, otherRecorders = 0, fanLevel = 0, fanMax = 0)

    // --- gating -----------------------------------------------------------------------------

    @Test
    fun `parked, settled, idle car may install`() {
        assertEquals(OtaPlan.Hold.NONE, plan.hold(car(), accOnAt, settled))
    }

    @Test
    fun `nothing installs in the first minute after ACC on`() {
        assertEquals(OtaPlan.Hold.SETTLING, plan.hold(car(), accOnAt, settled - 1))
        assertEquals(OtaPlan.Hold.SETTLING, plan.hold(car(), null, settled))
    }

    @Test
    fun `reverse wins over everything`() {
        val c = car(gear = Gear.REVERSE, call = NoisePlan.Call.ACTIVE, carPlay = NoisePlan.Session.ACTIVE)
        assertEquals(OtaPlan.Hold.REVERSE, plan.hold(c, accOnAt, settled))
    }

    @Test
    fun `a call or a CarPlay session holds the install`() {
        assertEquals(OtaPlan.Hold.CALL, plan.hold(car(call = NoisePlan.Call.ACTIVE), accOnAt, settled))
        assertEquals(OtaPlan.Hold.CARPLAY, plan.hold(car(carPlay = NoisePlan.Session.ACTIVE), accOnAt, settled))
    }

    @Test
    fun `only a parked car installs`() {
        assertEquals(OtaPlan.Hold.NOT_PARKED, plan.hold(car(gear = Gear.DRIVE), accOnAt, settled))
        assertEquals(OtaPlan.Hold.NOT_PARKED, plan.hold(car(gear = Gear.NEUTRAL), accOnAt, settled))
        assertEquals(OtaPlan.Hold.NOT_PARKED, plan.hold(car(speed = 3), accOnAt, settled))
        // Gear unknown (no CAN box): standing still is the best evidence there is.
        assertEquals(OtaPlan.Hold.NONE, plan.hold(car(gear = null), accOnAt, settled))
        assertEquals(OtaPlan.Hold.NONE, plan.hold(car(gear = Gear.UNKNOWN), accOnAt, settled))
        assertEquals(OtaPlan.Hold.NOT_PARKED, plan.hold(car(gear = null, speed = 1), accOnAt, settled))
    }

    @Test
    fun `ACC off or no car state holds`() {
        assertEquals(OtaPlan.Hold.ACC_OFF, plan.hold(car(acc = NoisePlan.Acc.OFF), accOnAt, settled))
        assertEquals(OtaPlan.Hold.NO_CAR, plan.hold(null, accOnAt, settled))
    }

    // --- what and in which order ------------------------------------------------------------

    private val served = "a".repeat(64)
    private val other = "b".repeat(64)

    private fun app(role: ReleaseManifest.Role, pkg: String, code: Long) = ReleaseManifest.App(
        role, pkg, code, "v$code", if (role == ReleaseManifest.Role.SUITE) "suite/$pkg.apk" else "$pkg-vc$code.apk",
        served, 100,
    )

    /** What the unit has: versionCode, and the sha256 of its APK (another build than served). */
    private fun have(vararg rows: Pair<String, Long>, sha: String = other) =
        rows.associate { (pkg, code) -> pkg to OtaPlan.Installed(code, sha) }

    private val manifest = ReleaseManifest(
        "2026-10-01T18:00:00Z",
        listOf(
            app(ReleaseManifest.Role.LAUNCHER, "com.ripostelabs.carlauncher", 976),
            app(ReleaseManifest.Role.SUITE, "com.ripostelabs.notes", 2),
            app(ReleaseManifest.Role.CARSERVICE, "com.ripostelabs.car", 976),
            app(ReleaseManifest.Role.SUITE, "com.ripostelabs.clock", 4),
            app(ReleaseManifest.Role.SUITE, "com.ripostelabs.radio", 1),
        ),
    )

    @Test
    fun `suite first, car service next, launcher last`() {
        val installed = have(
            "com.ripostelabs.carlauncher" to 975L, "com.ripostelabs.car" to 975L,
            "com.ripostelabs.clock" to 3L, "com.ripostelabs.notes" to 1L, "com.ripostelabs.radio" to 0L,
        )
        assertEquals(
            listOf(
                "com.ripostelabs.clock", "com.ripostelabs.notes", "com.ripostelabs.radio",
                "com.ripostelabs.car", "com.ripostelabs.carlauncher",
            ),
            plan.updates(manifest, installed, emptySet()).map { it.pkg },
        )
    }

    @Test
    fun `only newer versions of installed packages`() {
        // radio is the served build, notes is not installed (the owner never chose it), the
        // launcher is newer on the unit than on the server.
        val installed = have("com.ripostelabs.carlauncher" to 980L, "com.ripostelabs.clock" to 3L) +
            have("com.ripostelabs.radio" to 1L, sha = served)
        assertEquals(listOf("com.ripostelabs.clock"), plan.updates(manifest, installed, emptySet()).map { it.pkg })
    }

    @Test
    fun `a suite rebuild at the same versionCode is taken, a launcher one is not`() {
        // Suite apps all sit at versionCode 1, so for them the bytes decide (as the USB updater
        // does). The launcher and the car service count versions: CI numbers every build.
        val installed = have("com.ripostelabs.radio" to 1L, "com.ripostelabs.carlauncher" to 976L, "com.ripostelabs.car" to 976L)
        assertEquals(listOf("com.ripostelabs.radio"), plan.updates(manifest, installed, emptySet()).map { it.pkg })
        val same = have("com.ripostelabs.radio" to 1L, sha = served)
        assertEquals(emptyList<String>(), plan.updates(manifest, same, emptySet()).map { it.pkg })
    }

    @Test
    fun `a build that was rolled back or refused is never offered again`() {
        val installed = have("com.ripostelabs.carlauncher" to 975L, "com.ripostelabs.clock" to 3L)
        val launcher = manifest.apps.first { it.role == ReleaseManifest.Role.LAUNCHER }
        val refused = setOf(OtaPlan.key(launcher))
        assertEquals(listOf("com.ripostelabs.clock"), plan.updates(manifest, installed, refused).map { it.pkg })
    }

    // --- when to ask ------------------------------------------------------------------------

    @Test
    fun `checks daily and after every ACC on`() {
        val day = OtaPlan.CHECK_EVERY_MS
        assertTrue(plan.checkDue(nowMs = 10 * day, lastCheckMs = 0, accOnAtMs = null))
        assertFalse(plan.checkDue(nowMs = 10 * day, lastCheckMs = 10 * day - 1000, accOnAtMs = null))
        assertTrue(plan.checkDue(nowMs = 11 * day, lastCheckMs = 10 * day, accOnAtMs = null))
        // ACC came on after the last check: ask again, once.
        assertTrue(plan.checkDue(nowMs = 10 * day + 5000, lastCheckMs = 10 * day, accOnAtMs = 10 * day + 1000))
        assertFalse(plan.checkDue(nowMs = 10 * day + 9000, lastCheckMs = 10 * day + 6000, accOnAtMs = 10 * day + 1000))
    }
}
