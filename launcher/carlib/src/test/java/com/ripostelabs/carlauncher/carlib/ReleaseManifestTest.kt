package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The manifest comes from the estate (os/uplink/ingest.py, GET /v1/releases/manifest.json). It is
 * trusted for *what exists*, never for *what is safe*: rows with a malformed digest, an odd path
 * or an unknown role are dropped, and the certificate field is not even read.
 */
class ReleaseManifestTest {

    private val sha = "a".repeat(64)

    private fun row(
        role: String = "suite",
        pkg: String = "com.ripostelabs.clock",
        code: Long = 3,
        path: String = "suite/com.ripostelabs.clock.apk",
        digest: String = sha,
        size: Long = 1000,
    ) = """{"role":"$role","package":"$pkg","version_code":$code,"version_name":"1.$code",
        "path":"$path","sha256":"$digest","size":$size,"cert_sha256":"${"f".repeat(64)}"}"""

    private fun doc(vararg rows: String, schema: String = ReleaseManifest.SCHEMA) =
        """{"schema":"$schema","generated":"2026-10-01T18:00:00Z","apps":[${rows.joinToString(",")}]}"""

    @Test
    fun `a full manifest parses every role`() {
        val m = ReleaseManifest.parse(doc(
            row(),
            row("launcher", "com.ripostelabs.carlauncher", 975, "carlauncher-0.7-vc975.apk"),
            row("carservice", "com.ripostelabs.car", 975, "carservice-0.7-vc975.apk"),
        ))!!
        assertEquals(3, m.apps.size)
        val launcher = m.apps.single { it.role == ReleaseManifest.Role.LAUNCHER }
        assertEquals("com.ripostelabs.carlauncher", launcher.pkg)
        assertEquals(975L, launcher.versionCode)
        assertEquals("carlauncher-0.7-vc975.apk", launcher.path)
        assertEquals(sha, launcher.sha256)
        assertEquals(1000L, launcher.size)
        assertEquals("2026-10-01T18:00:00Z", m.generated)
    }

    @Test
    fun `a wrong schema or broken JSON is no manifest`() {
        assertNull(ReleaseManifest.parse(doc(row(), schema = "riposte-releases/2")))
        assertNull(ReleaseManifest.parse("<html>"))
        assertNull(ReleaseManifest.parse(""))
    }

    @Test
    fun `malformed rows are dropped, the rest kept`() {
        val m = ReleaseManifest.parse(doc(
            row(digest = "A".repeat(64)),                       // upper case: not ours
            row(digest = "abc"),                                // short
            row(path = "../etc/passwd.apk"),                    // traversal
            row(path = "suite/x.zip"),                          // not an APK
            row(role = "firmware"),                             // unknown role
            row(size = 0),                                      // empty
            row(size = ReleaseManifest.MAX_APK_BYTES + 1),      // too big for the unit
            row(code = 0),                                      // no version
            row(pkg = "not a package"),
            row(pkg = "com.ripostelabs.notes", path = "suite/com.ripostelabs.notes.apk"),
        ))!!
        assertEquals(listOf("com.ripostelabs.notes"), m.apps.map { it.pkg })
    }

    @Test
    fun `a package listed twice keeps its first row`() {
        val m = ReleaseManifest.parse(doc(row(code = 3), row(code = 9)))!!
        assertEquals(listOf(3L), m.apps.map { it.versionCode })
    }

    @Test
    fun `the manifest's certificate claim is never part of a row`() {
        val app = ReleaseManifest.parse(doc(row()))!!.apps.single()
        assertEquals(
            listOf("role", "pkg", "versionCode", "versionName", "path", "sha256", "size"),
            app.javaClass.declaredFields.filter { !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                .map { it.name },
        )
    }
}
