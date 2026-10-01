package com.ripostelabs.carlauncher.carlib

import java.io.File
import java.security.MessageDigest

/**
 * ReleasePin: the one signing certificate the car accepts for each kind of release (RAV4-236).
 *
 * Pinned here, in the launcher, and never taken from the manifest. A new key means a new
 * launcher build that carries the new digest, installed the old way (zero's USB updater).
 *
 * | role       | certificate                                   | who can sign with it        |
 * |------------|-----------------------------------------------|-----------------------------|
 * | launcher   | the CarLauncher release key (SIGNING.md)      | CI only                     |
 * | carservice | AOSP platform *test* key (platform-testkey/)  | anyone: the key is public   |
 * | suite      | the rav4-apps key (`rav4 suite build`)        | the owner's build hosts     |
 *
 * The car service must carry the image's platform key to run as system, and that key is AOSP's
 * public test key. Its pin therefore proves little: for the car service the sha256 from the
 * manifest, fetched over the tailnet from the estate, is what really vouches for the file.
 */
class ReleasePin(private val byRole: Map<ReleaseManifest.Role, String>) {

    fun of(role: ReleaseManifest.Role): String? = byRole[role]

    fun accepts(role: ReleaseManifest.Role, certSha256: String?): Boolean =
        certSha256 != null && byRole[role] == certSha256

    companion object {
        val RELEASE = ReleasePin(mapOf(
            ReleaseManifest.Role.LAUNCHER to "76e003ea7f213b16e8f40f2724a51219152808d5de9d6020f2df4ca43379dca2",
            ReleaseManifest.Role.CARSERVICE to "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8",
            ReleaseManifest.Role.SUITE to "1b074185304ecddd6168684a6dacefd7374e340bf44a7744006aeb671ceb3a0d",
        ))
    }
}

/** OtaVerify: a downloaded APK against its manifest row and the pinned signer, in that order. */
object OtaVerify {

    enum class Verdict { OK, SIZE, SHA256, UNSIGNED, WRONG_SIGNER }

    fun check(app: ReleaseManifest.App, file: File, pins: ReleasePin = ReleasePin.RELEASE): Verdict {
        if (file.length() != app.size) {
            return Verdict.SIZE
        }
        if (sha256(file) != app.sha256) {
            return Verdict.SHA256
        }
        val signer = ApkCert.signer(file) ?: return Verdict.UNSIGNED
        return if (pins.accepts(app.role, signer)) Verdict.OK else Verdict.WRONG_SIGNER
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER_BYTES)
            while (true) {
                val n = input.read(buf)
                if (n < 0) {
                    break
                }
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private const val BUFFER_BYTES = 64 * 1024
}
