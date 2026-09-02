package com.ripostelabs.carlauncher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UpdateFeed] parses remote input that ends in a root `pm install` over the running HOME app,
 * so every rule it applies is pinned here. Two failure directions matter and they are not
 * symmetric: parsing garbage as an update risks installing the wrong thing (a hand-made
 * release, a doc PDF attached as an asset); parsing a real release as garbage merely leaves
 * the car a version behind. Every ambiguous case below therefore resolves to null / "not an
 * update", and these tests are what keep a future regex loosening honest.
 *
 * The JSON fixture mirrors the real GitHub `/releases/latest` shape for a CI-published
 * release (launcher-ci.yml release job + GitHub mirror step), body table included.
 */
class UpdateFeedTest {

    private val sha = "5edaa485fd76806993b1b8c56c237d1f2fd47d3681559fce8bf4ee316aa9482c"

    private fun releaseJson(
        tag: String = "v0.7.132+132.gac09e10",
        assetName: String = "carlauncher-v0.7.132+132.gac09e10.apk",
        body: String = "| versionCode | `132` |\n| APK | `x.apk` |\n| SHA-256 | `$sha` |",
    ) = """
        {
          "tag_name": "$tag",
          "name": "$tag",
          "body": "${body.replace("\n", "\\n")}",
          "assets": [
            {
              "name": "output-metadata.json",
              "size": 421,
              "url": "https://api.github.com/repos/o/r/releases/assets/1",
              "browser_download_url": "https://github.com/o/r/releases/download/t/output-metadata.json"
            },
            {
              "name": "$assetName",
              "size": 7001687,
              "url": "https://api.github.com/repos/o/r/releases/assets/2",
              "browser_download_url": "https://github.com/o/r/releases/download/t/$assetName"
            }
          ]
        }
    """.trimIndent()

    // ---- Tag shape ----------------------------------------------------------

    @Test
    fun ciTagParsesToItsVersion() {
        assertEquals(132, UpdateFeed.versionCodeOfTag("v0.7.132+132.gac09e10"))
        assertEquals("0.7.132", UpdateFeed.versionNameOfTag("v0.7.132+132.gac09e10"))
        // The four-part display scheme (0.X.Y.Z) is valid too.
        assertEquals(71, UpdateFeed.versionCodeOfTag("v0.4.4.2+71.g4f7ddef"))
        // Current shape: versionName is the bare base, the build lives in +<code>.
        assertEquals(146, UpdateFeed.versionCodeOfTag("v0.7+146.g91dd836"))
        assertEquals("0.7", UpdateFeed.versionNameOfTag("v0.7+146.g91dd836"))
    }

    @Test
    fun handMadeTagsAreNotUpdates() {
        listOf(
            "v1.0.0",                  // plain tag, no build identity
            "v0.7.132",                // no versionCode
            "0.7.132+132.gac09e10",    // missing the v
            "v0.7.132+abc.gac09e10",   // non-numeric code
            "v0.7.132+132",            // no sha suffix
            "v0.7.132+132.gAC09E10",   // upper-case sha: not what CI emits
            "xv0.7.132+132.gac09e10",  // prefix junk — anchoring matters
            "v0.7.132+132.gac09e10x",  // suffix junk
        ).forEach { tag ->
            assertNull("'$tag' parsed as a CI tag", UpdateFeed.versionCodeOfTag(tag))
        }
    }

    // ---- Digest row ---------------------------------------------------------

    @Test
    fun shaIsReadFromTheNotesTable() {
        assertEquals(sha, UpdateFeed.sha256OfBody("| SHA-256 | `$sha` |"))
        // Un-backticked and mixed-case still land, normalized to lower-case.
        assertEquals(sha, UpdateFeed.sha256OfBody("| SHA-256 | ${sha.uppercase()} |"))
    }

    @Test
    fun aLooseHexStringIsNotADigest() {
        // 64 hex chars floating in prose (a commit range, a pasted hash) must not be
        // mistaken for the APK digest — only the labelled table row counts.
        assertNull(UpdateFeed.sha256OfBody("built from $sha on runner 3"))
        assertNull(UpdateFeed.sha256OfBody(null))
        assertNull(UpdateFeed.sha256OfBody("| SHA-256 | `${sha.dropLast(1)}` |")) // 63 chars
    }

    // ---- Full release parse -------------------------------------------------

    @Test
    fun aRealReleaseParsesWhole() {
        val release = UpdateFeed.parseLatest(releaseJson())!!

        assertEquals("v0.7.132+132.gac09e10", release.tag)
        assertEquals(132, release.versionCode)
        assertEquals("0.7.132", release.versionName)
        // The APK is found by suffix even with the metadata asset listed first.
        assertEquals("carlauncher-v0.7.132+132.gac09e10.apk", release.apkName)
        assertEquals("https://api.github.com/repos/o/r/releases/assets/2", release.apkUrl)
        assertEquals(7001687L, release.apkSizeBytes)
        assertEquals(sha, release.sha256)
    }

    @Test
    fun aReleaseWithoutAnApkIsNotAnUpdate() {
        val json = releaseJson().replace(".apk", ".zip")
        assertNull(UpdateFeed.parseLatest(json))
    }

    @Test
    fun aHandMadeReleaseIsNotAnUpdate() {
        assertNull(UpdateFeed.parseLatest(releaseJson(tag = "v1.0.0")))
    }

    @Test
    fun garbageJsonParsesAsNullNotThrow() {
        // The feed is remote input read while a settings screen is composing.
        assertNull(UpdateFeed.parseLatest("not json"))
        assertNull(UpdateFeed.parseLatest("{}"))
        assertNull(UpdateFeed.parseLatest("""{"tag_name":"v0.7.1+1.gabc"}""")) // no assets
    }

    @Test
    fun aMissingDigestSurvivesTheParse() {
        // No SHA row is a *download-time* refusal (UpdateController), not a parse failure —
        // the screen should still be able to say which version exists.
        val release = UpdateFeed.parseLatest(releaseJson(body = "hand-edited notes"))!!
        assertNull(release.sha256)
        assertEquals(132, release.versionCode)
    }

    // ---- Picking the newest out of the release list --------------------------

    /** A `GET /releases` array, in the order given. */
    private fun listJson(vararg objects: String) = objects.joinToString(",", "[", "]")

    private fun listedRelease(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = false,
    ) = releaseJson(tag = tag, assetName = "carlauncher-$tag.apk")
        .trimEnd()
        .removeSuffix("}")
        .plus(""", "draft": $draft, "prerelease": $prerelease }""")

    @Test
    fun theNewestIsTheHighestVersionCodeNotTheFirstListed() {
        // The regression this replaced: GitHub's /releases/latest answered vc152 while vc154
        // and vc155 were both published, because every mirrored release shares a created_at
        // and the tie-break is arbitrary. Ordering is now ours, so array order cannot cap it.
        val json = listJson(
            listedRelease("v0.7.152+152.ge54e245"),
            listedRelease("v0.7+155.g30e1b60"),
            listedRelease("v0.7+154.gdb83160"),
        )
        val newest = UpdateFeed.parseNewest(json)!!
        assertEquals(155, newest.versionCode)
        assertEquals("v0.7+155.g30e1b60", newest.tag)
        assertEquals("carlauncher-v0.7+155.g30e1b60.apk", newest.apkName)
    }

    @Test
    fun draftsAndPrereleasesAreSkipped() {
        // A token with push rights sees drafts; a half-published release must not reach HOME.
        val json = listJson(
            listedRelease("v0.7+158.gaaaaaaa", draft = true),
            listedRelease("v0.7+157.gbbbbbbb", prerelease = true),
            listedRelease("v0.7+152.gccccccc"),
        )
        assertEquals(152, UpdateFeed.parseNewest(json)!!.versionCode)
    }

    @Test
    fun handMadeReleasesAreIgnoredButDoNotHideRealOnes() {
        // One unparseable tag in the page must not sink the whole check.
        val json = listJson(
            listedRelease("v1.0.0"),
            listedRelease("v0.7+153.gdddddddd"),
        )
        assertEquals(153, UpdateFeed.parseNewest(json)!!.versionCode)
    }

    @Test
    fun anEmptyOrGarbageListIsNullNotACrash() {
        assertNull(UpdateFeed.parseNewest("[]"))
        assertNull(UpdateFeed.parseNewest("not json"))
        // A single object is not a list — the endpoint changed shape, so refuse rather than guess.
        assertNull(UpdateFeed.parseNewest(releaseJson()))
        // A page containing only hand-made releases has nothing to offer.
        assertNull(UpdateFeed.parseNewest(listJson(listedRelease("v1.0.0"))))
    }

    // ---- Self-update guard --------------------------------------------------

    @Test
    fun aRenamedPackageIsRefusedNotInstalled() {
        // The real 2026-08-30 case: main's applicationId changed under a launcher that was
        // still on the old package. Without this refusal the updater installs a SECOND app,
        // its own versionCode never moves, and it repeats the same "update" forever.
        val refusal = UpdateFeed.selfUpdateRefusal(
            apkPackage = "com.ripostelabs.carlauncher",
            apkVersionCode = 147,
            ourPackage = "com.reveng.carlauncher",
            ourVersionCode = 146,
            releaseName = "0.7.147",
        )
        assertNotNull("a package rename must not auto-install", refusal)
        // The message has to name both packages: "update failed" would send someone hunting a
        // network bug instead of reading it as the migration it is.
        assertTrue(refusal!!.contains("com.ripostelabs.carlauncher"))
        assertTrue(refusal.contains("com.reveng.carlauncher"))
        assertTrue(refusal.contains("migration"))
    }

    @Test
    fun aGenuineSelfUpdateIsAllowed() {
        assertNull(
            UpdateFeed.selfUpdateRefusal(
                apkPackage = "com.ripostelabs.carlauncher",
                apkVersionCode = 147,
                ourPackage = "com.ripostelabs.carlauncher",
                ourVersionCode = 146,
                releaseName = "0.7.147",
            )
        )
    }

    @Test
    fun theApkOverrulesTheReleaseNotes() {
        // Right package, but the file is not actually newer than us — the notes claimed
        // otherwise. The install decision must follow the APK, not the prose.
        listOf(146, 145, 0).forEach { code ->
            assertNotNull(
                "versionCode $code is not newer than 146",
                UpdateFeed.selfUpdateRefusal(
                    apkPackage = "com.ripostelabs.carlauncher",
                    apkVersionCode = code,
                    ourPackage = "com.ripostelabs.carlauncher",
                    ourVersionCode = 146,
                    releaseName = "0.7.$code",
                ),
            )
        }
    }

    @Test
    fun theDebugSuffixCountsAsADifferentApp() {
        // `.debug` builds install alongside release by design (applicationIdSuffix), so a
        // release APK is not an update to a debug launcher, however new its versionCode.
        assertNotNull(
            UpdateFeed.selfUpdateRefusal(
                apkPackage = "com.ripostelabs.carlauncher",
                apkVersionCode = 200,
                ourPackage = "com.ripostelabs.carlauncher.debug",
                ourVersionCode = 146,
                releaseName = "0.7.200",
            )
        )
    }

    // ---- Comparison ---------------------------------------------------------

    @Test
    fun onlyStrictlyNewerCountsAsAnUpdate() {
        val release = UpdateFeed.parseLatest(releaseJson())!!
        assertTrue(UpdateFeed.isNewer(release, 131))
        assertFalse("equal is not newer", UpdateFeed.isNewer(release, 132))
        assertFalse("older is not newer", UpdateFeed.isNewer(release, 133))
    }
}
