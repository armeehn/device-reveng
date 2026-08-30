package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A driver profile is stored as one JSON string per entry in a DataStore string set, and
 * [DriverProfilesStore] round-trips every entry through [DriverProfile.decode] on *every* read,
 * upsert and delete. So the codec runs on user-visible storage that a partial write, a restored
 * backup or a format change can corrupt, and it must answer null rather than throw — a throw here
 * propagates out of the profiles StateFlow and takes down the launcher that owns the HOME intent.
 *
 * NOTE ON THE JSON IMPLEMENTATION. These tests run against the reference `org.json` artifact,
 * because Android's copy is a stub in a JVM unit test. The two agree on structure (missing keys,
 * wrong container types, syntax errors) but differ on type *coercion* — Android's `getString`
 * coerces a number to its text, the reference implementation throws. Nothing below depends on
 * coercion; see the PR body.
 */
class DriverProfileCodecTest {

    private fun profile(
        id: String = "profile.1700000000000",
        name: String = "Weekday",
        themeId: String = "builtin.midnight",
        favorites: Set<String> = setOf("com.android.chrome", "com.google.android.apps.maps"),
        appOrder: List<String> = listOf("com.b", "com.a", "com.c"),
        driverSide: DriverSideMode = DriverSideMode.RHD,
    ) = DriverProfile(id, name, themeId, favorites, appOrder, driverSide)

    @Test
    fun roundTripPreservesEveryField() {
        val original = profile()

        assertEquals(original, DriverProfile.decode(original.encode()))
    }

    @Test
    fun appOrderKeepsItsOrder() {
        // The order IS the value here — favourites are a set, the drawer order is not. A codec that
        // routed both through the same set-shaped path would still round-trip the field's contents.
        val original = profile(appOrder = listOf("com.z", "com.a", "com.m"))

        assertEquals(listOf("com.z", "com.a", "com.m"), DriverProfile.decode(original.encode())?.appOrder)
    }

    @Test
    fun duplicatesInTheAppOrderAreNotQuietlyCollapsed() {
        // appOrder is a List because the drawer replays it positionally. Silently de-duplicating it
        // in the codec would make a captured profile differ from the order it was captured from,
        // which the driver would read as the profile "not sticking".
        val original = profile(appOrder = listOf("com.a", "com.b", "com.a"))

        assertEquals(listOf("com.a", "com.b", "com.a"), DriverProfile.decode(original.encode())?.appOrder)
    }

    @Test
    fun emptyCollectionsRoundTrip() {
        // A brand-new profile captured on a fresh install has no favourites and no custom order.
        val original = profile(favorites = emptySet(), appOrder = emptyList())

        val decoded = DriverProfile.decode(original.encode())

        assertEquals(emptySet<String>(), decoded?.favorites)
        assertEquals(emptyList<String>(), decoded?.appOrder)
    }

    @Test
    fun everyDriverSideSurvives() {
        DriverSideMode.values().forEach { side ->
            assertEquals(side, DriverProfile.decode(profile(driverSide = side).encode())?.driverSide)
        }
    }

    @Test
    fun awkwardTextSurvives() {
        // Profile names are free text typed on an in-car keyboard, and package names come from
        // PackageManager. Quotes and backslashes must not break out of the JSON string.
        val original = profile(
            name = """He said "hi" \ then left""",
            favorites = setOf("com.aé", "com.b"),
        )

        assertEquals(original, DriverProfile.decode(original.encode()))
    }

    @Test
    fun malformedInputIsNullNotAThrow() {
        val corrupt = listOf(
            "",                       // an unset key
            "   ",
            "null",                   // what `settings get` prints for an unset key
            "not json at all",
            "{",                      // truncated write
            "[]",                     // right syntax, wrong shape
            "{}",                     // an object with none of the required keys
            """{"id":"profile.1"}""", // half-written: id but no name
        )

        corrupt.forEach { raw ->
            assertNull("decode(\"$raw\") should be null", DriverProfile.decode(raw))
        }
    }

    @Test
    fun wrongShapedCollectionsFallBackToEmpty() {
        // optJSONArray answers null for a non-array, and the codec treats that as "no entries"
        // rather than failing the whole profile — the theme and name are still worth recovering.
        val raw = """{"id":"p1","name":"n","theme":"t","favorites":"com.a","order":42,"side":"LHD"}"""

        val decoded = DriverProfile.decode(raw)

        assertEquals(emptySet<String>(), decoded?.favorites)
        assertEquals(emptyList<String>(), decoded?.appOrder)
        assertEquals(DriverSideMode.LHD, decoded?.driverSide)
    }

    @Test
    fun unknownDriverSideFallsBackToAuto() {
        // A profile written by a newer build (or hand-edited) must not lose the whole bundle over
        // one unrecognised enum name.
        val raw = """{"id":"p1","name":"n","theme":"t","favorites":[],"order":[],"side":"CENTRE"}"""

        assertEquals(DriverSideMode.AUTO, DriverProfile.decode(raw)?.driverSide)
    }

    @Test
    fun missingDriverSideFallsBackToAuto() {
        val raw = """{"id":"p1","name":"n","theme":"t","favorites":[],"order":[]}"""

        assertEquals(DriverSideMode.AUTO, DriverProfile.decode(raw)?.driverSide)
    }

    @Test
    fun blankEntriesAreDropped() {
        // An empty package name matches no installed app; keeping one would put a blank row in the
        // drawer order and a phantom favourite that can never be un-favourited.
        val raw = """{"id":"p1","name":"n","theme":"t","favorites":["","com.a"],"order":["com.a","","  "],"side":"AUTO"}"""

        val decoded = DriverProfile.decode(raw)

        assertEquals(setOf("com.a"), decoded?.favorites)
        assertEquals(listOf("com.a"), decoded?.appOrder)
    }

    @Test
    fun profilesDifferingOnlyByIdEncodeDifferently() {
        // DriverProfilesStore stores encoded strings in a *set* and replaces by id. Two distinct
        // profiles that encoded identically would collapse into one entry.
        assertNotEquals(profile(id = "profile.1").encode(), profile(id = "profile.2").encode())
    }
}
