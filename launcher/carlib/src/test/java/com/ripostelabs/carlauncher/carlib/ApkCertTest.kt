package com.ripostelabs.carlauncher.carlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The car installs an APK only if its signer is the pinned release certificate. These tests
 * build APKs byte by byte (a zip plus an APK Signing Block holding a chosen certificate), so the
 * pin can be shown to refuse a wrong signer even when the manifest names the right one.
 */
class ApkCertTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val suiteCert = "rav4-apps suite certificate".toByteArray()
    private val strangerCert = "someone else's certificate".toByteArray()
    private val pins = ReleasePin(mapOf(
        ReleaseManifest.Role.SUITE to sha(suiteCert),
        ReleaseManifest.Role.LAUNCHER to sha("launcher".toByteArray()),
        ReleaseManifest.Role.CARSERVICE to sha("platform".toByteArray()),
    ))

    @Test
    fun `v2 signer is read`() {
        assertEquals(sha(suiteCert), ApkCert.signer(file(Apk.build(suiteCert))))
    }

    @Test
    fun `v3 signer wins over v2`() {
        val apk = Apk.build(suiteCert, schemes = listOf(ApkCert.V2 to strangerCert, ApkCert.V3 to suiteCert))
        assertEquals(sha(suiteCert), ApkCert.signer(file(apk)))
    }

    @Test
    fun `an unsigned APK or a non-zip has no signer`() {
        assertNull(ApkCert.signer(file(Apk.build(null))))
        assertNull(ApkCert.signer(file("not a zip".toByteArray())))
    }

    @Test
    fun `the right signer passes every check`() {
        val bytes = Apk.build(suiteCert)
        assertEquals(OtaVerify.Verdict.OK, OtaVerify.check(app(bytes), file(bytes), pins))
    }

    @Test
    fun `a wrong signer is refused although the manifest names the pinned one`() {
        // The manifest row carries the right sha256 and size, and the server's cert field said the
        // pinned digest. The file itself is signed by someone else: refused.
        val bytes = Apk.build(strangerCert)
        assertEquals(OtaVerify.Verdict.WRONG_SIGNER, OtaVerify.check(app(bytes), file(bytes), pins))
    }

    @Test
    fun `a suite signer cannot pass as the launcher`() {
        val bytes = Apk.build(suiteCert)
        val asLauncher = app(bytes).copy(role = ReleaseManifest.Role.LAUNCHER)
        assertEquals(OtaVerify.Verdict.WRONG_SIGNER, OtaVerify.check(asLauncher, file(bytes), pins))
    }

    @Test
    fun `sha256 and size are checked before the signer`() {
        val bytes = Apk.build(suiteCert)
        assertEquals(OtaVerify.Verdict.SHA256, OtaVerify.check(app(bytes).copy(sha256 = "0".repeat(64)), file(bytes), pins))
        assertEquals(OtaVerify.Verdict.SIZE, OtaVerify.check(app(bytes).copy(size = 1), file(bytes), pins))
        val unsigned = Apk.build(null)
        assertEquals(OtaVerify.Verdict.UNSIGNED, OtaVerify.check(app(unsigned), file(unsigned), pins))
    }

    @Test
    fun `the release pins are the real certificates`() {
        // Digests from os/uplink/apkinfo.py over the APKs `rav4 publish` served on 2026-10-01.
        assertEquals("76e003ea7f213b16e8f40f2724a51219152808d5de9d6020f2df4ca43379dca2",
            ReleasePin.RELEASE.of(ReleaseManifest.Role.LAUNCHER))
        // AOSP platform test key (launcher/carservice/platform-testkey/README.md).
        assertEquals("c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8",
            ReleasePin.RELEASE.of(ReleaseManifest.Role.CARSERVICE))
        assertEquals("1b074185304ecddd6168684a6dacefd7374e340bf44a7744006aeb671ceb3a0d",
            ReleasePin.RELEASE.of(ReleaseManifest.Role.SUITE))
    }

    private fun app(bytes: ByteArray) = ReleaseManifest.App(
        role = ReleaseManifest.Role.SUITE, pkg = "com.ripostelabs.clock", versionCode = 4, versionName = "1.4",
        path = "suite/com.ripostelabs.clock.apk", sha256 = shaHex(bytes), size = bytes.size.toLong(),
    )

    private var n = 0
    private fun file(bytes: ByteArray): File = tmp.newFile("a${n++}.apk").apply { writeBytes(bytes) }

    companion object {
        fun sha(b: ByteArray): String = shaHex(b)
        fun shaHex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    }
}

/** A minimal APK: a zip, and an APK Signing Block between its entries and its directory. */
object Apk {

    fun build(cert: ByteArray?, schemes: List<Pair<Int, ByteArray>>? = null, payload: String = "dex"): ByteArray {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            z.putNextEntry(ZipEntry("classes.dex"))
            z.write(payload.toByteArray())
            z.closeEntry()
        }
        val raw = zip.toByteArray()
        val pairs = schemes ?: cert?.let { listOf(ApkCert.V2 to it) } ?: return raw

        val eocd = lastIndexOf(raw, byteArrayOf(0x50, 0x4b, 0x05, 0x06))
        val cd = le(raw).getInt(eocd + 16)
        val block = block(pairs)
        val tail = raw.copyOfRange(cd, raw.size)
        le(tail).putInt(eocd - cd + 16, cd + block.size)
        return raw.copyOfRange(0, cd) + block + tail
    }

    private fun block(schemes: List<Pair<Int, ByteArray>>): ByteArray {
        val pairs = ByteArrayOutputStream()
        for ((id, cert) in schemes) {
            val signed = prefixed(ByteArray(0)) + prefixed(prefixed(cert)) + prefixed(ByteArray(0))
            val signer = prefixed(signed) + prefixed(ByteArray(0)) + prefixed(ByteArray(0))
            val value = prefixed(prefixed(signer))
            pairs.write(ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(4L + value.size).putInt(id).array())
            pairs.write(value)
        }
        val body = pairs.toByteArray()
        val size = body.size + 8L + 16
        val head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(size).array()
        return head + body + head + "APK Sig Block 42".toByteArray()
    }

    private fun prefixed(b: ByteArray) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(b.size).array() + b

    private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

    private fun lastIndexOf(hay: ByteArray, needle: ByteArray): Int {
        for (i in hay.size - needle.size downTo 0) {
            if (needle.indices.all { hay[i + it] == needle[it] }) {
                return i
            }
        }
        return -1
    }
}
