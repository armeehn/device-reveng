package com.ripostelabs.carlauncher.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * v0.7 — the pure half of the auto-updater: parsing a GitHub release into something the
 * launcher can compare against itself.
 *
 * CI tags every green build of main `v<versionName>+<versionCode>.g<short-sha>` and publishes
 * the APK it built as the release's single asset, with the APK's SHA-256 in the release notes
 * (.gitea/workflows/launcher-ci.yml, `release` + `Mirror release to GitHub` steps). This object
 * turns that contract into numbers: which versionCode a release carries, which asset is the APK,
 * and which digest the download must hash to.
 *
 * No Context, no network, no Android types — deliberately, so every parse rule is pinned by a
 * plain JVM test (UpdateFeedTest) the same way ExportFileNamingTest pins backup names. The
 * network and root I/O live in [UpdateController].
 */
object UpdateFeed {

    /** One published launcher build, as read from the releases API. */
    data class Release(
        val tag: String,
        val versionName: String,
        val versionCode: Int,
        /** Asset API URL (`assets[].url`) — the endpoint that serves the binary to a token. */
        val apkUrl: String,
        val apkName: String,
        val apkSizeBytes: Long,
        /** Lower-case hex SHA-256 from the release notes, or null if the notes lost it. */
        val sha256: String?,
    )

    /**
     * `v0.7.132+132.gac09e10` → 132. Null for anything that doesn't match the CI tag shape —
     * a hand-made tag must read as "not an update", never as versionCode 0.
     */
    fun versionCodeOfTag(tag: String): Int? =
        TAG_SHAPE.matchEntire(tag)?.groupValues?.get(2)?.toIntOrNull()

    /** `v0.7.132+132.gac09e10` → `0.7.132`, by the same shape rule as [versionCodeOfTag]. */
    fun versionNameOfTag(tag: String): String? =
        TAG_SHAPE.matchEntire(tag)?.groupValues?.get(1)

    /**
     * The `| SHA-256 | \`hex\` |` row out of the release notes. The digest is the only integrity
     * check the download gets (the transport is TLS, but the file crosses a hotspot link and a
     * flash write before `pm install` reads it), so a missing row parses to null and the caller
     * decides whether to proceed — it must never match some other 64-char string in the body.
     */
    fun sha256OfBody(body: String?): String? =
        body?.let { SHA_ROW.find(it)?.groupValues?.get(1)?.lowercase() }

    /**
     * One release object → [Release], or null when it isn't a CI launcher release: unparseable
     * tag, no `.apk` asset, or malformed JSON. Null rather than throwing, because the feed is
     * remote input — a repo admin creating a hand-made release must degrade to "nothing to
     * update", not crash the settings screen.
     */
    fun parseLatest(json: String): Release? =
        runCatching { parseOne(JSONObject(json)) }.getOrNull()

    /**
     * The newest CI launcher build in a `GET /releases` array — the highest versionCode, not
     * whatever the array happens to lead with.
     *
     * **Why we do not ask GitHub for `/releases/latest`.** That endpoint resolves "latest" by
     * `created_at`, which for a release is the *target commit's* date, not when the release was
     * published. Every release mirrored into carlauncher-releases has carried an identical
     * `created_at` (2026-08-30T15:16:58Z across vc152, vc154 and vc155), so the tie-break is
     * arbitrary: on 2026-09-01 the endpoint answered vc152 while vc154 and vc155 were both
     * published, non-draft and non-prerelease. The car could never update past vc152 no matter
     * what CI shipped, and nothing surfaced — it simply reported "up to date".
     *
     * Ordering by the versionCode we parse out of the tag ourselves removes the dependency on
     * GitHub's ordering entirely, which is the same reason [selfUpdateRefusal] trusts the APK
     * over the release notes: the number we act on should be one we derived, not one we were
     * handed.
     *
     * Drafts and prereleases are skipped — an unauthenticated read never sees drafts anyway, but
     * a token with push rights does, and a half-finished release is not something to install onto
     * a running HOME app.
     */
    fun parseNewest(json: String): Release? = runCatching {
        val arr = JSONArray(json)
        var best: Release? = null
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optBoolean("draft", false) || obj.optBoolean("prerelease", false)) continue
            val release = parseOne(obj) ?: continue
            if (best == null || release.versionCode > best!!.versionCode) best = release
        }
        best
    }.getOrNull()

    private fun parseOne(root: JSONObject): Release? {
        val tag = root.getString("tag_name")
        val code = versionCodeOfTag(tag) ?: return null
        val name = versionNameOfTag(tag) ?: return null

        val assets = root.getJSONArray("assets")
        var apk: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(APK_SUFFIX)) {
                apk = a
                break
            }
        }
        val found = apk ?: return null

        return Release(
            tag = tag,
            versionName = name,
            versionCode = code,
            apkUrl = found.getString("url"),
            apkName = found.getString("name"),
            apkSizeBytes = found.getLong("size"),
            sha256 = sha256OfBody(root.optString("body")),
        )
    }

    /** True when [release] is strictly newer than the running build. */
    fun isNewer(release: Release, installedVersionCode: Int): Boolean =
        release.versionCode > installedVersionCode

    /**
     * Is a downloaded APK actually an update to *us*? Returns null when it is, else the reason
     * to show the driver. Pure so the rule is testable; [UpdateController] supplies the values
     * by reading the APK's own manifest back off disk.
     *
     * Both refusals exist because the feed states a *claim* while `pm install -r` acts on the
     * *file*, and a disagreement between them is silent and expensive:
     *
     *  * **Different package.** On 2026-08-30 main's applicationId changed
     *    (`com.reveng.carlauncher` → `com.ripostelabs.carlauncher`). A launcher on the old
     *    package would have seen the new versionCode, called it an update, and installed a
     *    different package — which Android puts SIDE BY SIDE. The running launcher would be
     *    untouched, its versionCode unchanged, so the next check would find the same "update"
     *    and repeat it forever while the driver saw nothing change. A rename is a migration a
     *    human performs (back up, install, re-assign HOME, re-grant, uninstall the old one),
     *    never something an updater should attempt on its own.
     *  * **Not actually newer.** Makes the install decision rest on the APK rather than on
     *    release notes, which are prose and can be hand-edited into disagreeing with the file.
     */
    fun selfUpdateRefusal(
        apkPackage: String,
        apkVersionCode: Int,
        ourPackage: String,
        ourVersionCode: Int,
        releaseName: String,
    ): String? = when {
        apkPackage != ourPackage ->
            "Release $releaseName is a different app ($apkPackage), not an update to " +
                "$ourPackage. Installing it would add a second launcher rather than update " +
                "this one — that needs a manual migration."
        apkVersionCode <= ourVersionCode ->
            "Release $releaseName really carries versionCode $apkVersionCode, which is not " +
                "newer than this build ($ourVersionCode)."
        else -> null
    }

    // versionName is dotted digits (the release job enforces that shape before tagging),
    // versionCode decimal, then the short SHA. Anchored: a prefix match is not a CI tag.
    private val TAG_SHAPE = Regex("""^v(\d+(?:\.\d+){1,3})\+(\d+)\.g[0-9a-f]+$""")

    private val SHA_ROW = Regex("""\|\s*SHA-256\s*\|\s*`?([0-9a-fA-F]{64})`?\s*\|""")

    private const val APK_SUFFIX = ".apk"
}
