package com.reveng.carlauncher.data

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
     * One release object (the `/releases/latest` response) → [Release], or null when it isn't a
     * CI launcher release: unparseable tag, no `.apk` asset, or malformed JSON. Null rather than
     * throwing, because the feed is remote input — a repo admin creating a hand-made release
     * must degrade to "nothing to update", not crash the settings screen.
     */
    fun parseLatest(json: String): Release? = runCatching {
        val root = JSONObject(json)
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

        Release(
            tag = tag,
            versionName = name,
            versionCode = code,
            apkUrl = found.getString("url"),
            apkName = found.getString("name"),
            apkSizeBytes = found.getLong("size"),
            sha256 = sha256OfBody(root.optString("body")),
        )
    }.getOrNull()

    /** True when [release] is strictly newer than the running build. */
    fun isNewer(release: Release, installedVersionCode: Int): Boolean =
        release.versionCode > installedVersionCode

    // versionName is dotted digits (the release job enforces that shape before tagging),
    // versionCode decimal, then the short SHA. Anchored: a prefix match is not a CI tag.
    private val TAG_SHAPE = Regex("""^v(\d+(?:\.\d+){1,3})\+(\d+)\.g[0-9a-f]+$""")

    private val SHA_ROW = Regex("""\|\s*SHA-256\s*\|\s*`?([0-9a-fA-F]{64})`?\s*\|""")

    private const val APK_SUFFIX = ".apk"
}
