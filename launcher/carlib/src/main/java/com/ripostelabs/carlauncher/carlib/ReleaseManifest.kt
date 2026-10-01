package com.ripostelabs.carlauncher.carlib

import org.json.JSONException
import org.json.JSONObject

/**
 * ReleaseManifest: what the estate offers the car, from `GET /v1/releases/manifest.json` on the
 * ingest server (os/uplink/ingest.py), reached over the tailnet only.
 *
 *     {"schema": "riposte-releases/1", "generated": "...", "apps": [
 *       {"role": "launcher", "package": "...", "version_code": 976, "version_name": "0.7",
 *        "path": "carlauncher-0.7-vc976.apk", "sha256": "<64 hex>", "size": 8150240,
 *        "cert_sha256": "<64 hex>"}, ...]}
 *
 * The manifest says what exists; it is not trusted for what is safe. A row with a malformed
 * digest, path, package or role is dropped. `cert_sha256` is not read at all: the car checks the
 * downloaded file against its own pins ([ReleasePin]), so a lying server can at worst make an
 * update fail.
 */
class ReleaseManifest(val generated: String?, val apps: List<App>) {

    /** Install order is declaration order: suite apps first, the launcher always last. */
    enum class Role(val wire: String) {
        SUITE("suite"),
        CARSERVICE("carservice"),
        LAUNCHER("launcher"),
    }

    data class App(
        val role: Role,
        val pkg: String,
        val versionCode: Long,
        val versionName: String,
        val path: String,
        val sha256: String,
        val size: Long,
    )

    companion object {
        const val SCHEMA = "riposte-releases/1"

        /** Larger than any APK the unit should take over a phone link (the launcher is ~8 MB). */
        const val MAX_APK_BYTES = 64L * 1024 * 1024

        private val SHA = Regex("^[0-9a-f]{64}$")
        private val PATH = Regex("^(suite/)?[A-Za-z0-9][A-Za-z0-9._-]*\\.apk$")
        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

        /** The manifest, or null if it is not one. Bad rows are dropped, good ones kept. */
        fun parse(text: String): ReleaseManifest? {
            val doc = try {
                JSONObject(text)
            } catch (e: JSONException) {
                return null
            }
            if (doc.optString("schema") != SCHEMA) {
                return null
            }
            val rows = doc.optJSONArray("apps") ?: return null

            val apps = mutableListOf<App>()
            for (i in 0 until rows.length()) {
                val app = rows.optJSONObject(i)?.let(::row) ?: continue
                if (apps.none { it.pkg == app.pkg }) {
                    apps += app
                }
            }
            return ReleaseManifest(doc.optString("generated").ifEmpty { null }, apps)
        }

        private fun row(o: JSONObject): App? {
            val role = Role.entries.firstOrNull { it.wire == o.optString("role") } ?: return null
            val pkg = o.optString("package")
            val code = o.optLong("version_code", 0)
            val path = o.optString("path")
            val sha = o.optString("sha256")
            val size = o.optLong("size", 0)
            val valid = PACKAGE.matches(pkg) && code > 0 && PATH.matches(path) && SHA.matches(sha) &&
                size in 1..MAX_APK_BYTES
            if (!valid) {
                return null
            }
            return App(role, pkg, code, o.optString("version_name"), path, sha, size)
        }
    }
}
