package com.ripostelabs.carlauncher.carlib

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * ApkCert: the SHA-256 of an APK's signing certificate, read from the file itself.
 *
 *     zip entries │ APK Signing Block │ central directory │ end of central directory
 *                   ├─ v3 0xf05368c0: signers[0] -> signed data -> certificates[0]
 *                   └─ v2 0x7109871a: same layout
 *
 * This only reads which certificate the APK names; it does not check the signature. Android
 * does that at install (and the app checks the certificate Android verified as well), so an APK
 * that names the pinned certificate but carries someone else's signature still fails.
 */
object ApkCert {

    const val V2 = 0x7109871a
    const val V3 = 0xf05368c0.toInt()

    private const val EOCD_MAGIC = 0x06054b50
    private const val EOCD_MIN = 22
    private const val EOCD_MAX_COMMENT = 0xffff
    private const val CD_OFFSET_AT = 16
    private val BLOCK_MAGIC = "APK Sig Block 42".toByteArray()
    private const val BLOCK_TAIL = 24
    private const val MAX_BLOCK = 16L * 1024 * 1024

    /** Hex SHA-256 of the first signer's certificate (DER), v3 before v2; null if none or not an APK. */
    fun signer(apk: File): String? = try {
        RandomAccessFile(apk, "r").use { raf ->
            val pairs = pairs(raf) ?: return null
            val value = pairs[V3] ?: pairs[V2] ?: return null
            hex(MessageDigest.getInstance("SHA-256").digest(firstCert(value)))
        }
    } catch (e: IOException) {
        null
    } catch (e: BufferUnderflowException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun pairs(raf: RandomAccessFile): Map<Int, ByteBuffer>? {
        val cd = centralDirectory(raf) ?: return null
        if (cd < BLOCK_TAIL) {
            return null
        }
        val tail = read(raf, cd - BLOCK_TAIL, BLOCK_TAIL)
        if (!tail.array().copyOfRange(8, BLOCK_TAIL).contentEquals(BLOCK_MAGIC)) {
            return null
        }
        val size = tail.getLong(0)
        if (size !in BLOCK_TAIL.toLong()..MAX_BLOCK || cd - size - 8 < 0) {
            return null
        }
        val block = read(raf, cd - size - 8, (size + 8).toInt())
        if (block.getLong(0) != size) {
            return null
        }

        val out = mutableMapOf<Int, ByteBuffer>()
        block.position(8)
        val end = block.capacity() - BLOCK_TAIL
        while (block.position() + 12 <= end) {
            val n = block.getLong()
            val id = block.getInt()
            val value = slice(block, (n - 4).toInt())
            out[id] = value
        }
        return out
    }

    /** Offset of the zip central directory, from the end-of-central-directory record. */
    private fun centralDirectory(raf: RandomAccessFile): Long? {
        val len = raf.length()
        if (len < EOCD_MIN) {
            return null
        }
        val span = minOf(len, (EOCD_MIN + EOCD_MAX_COMMENT).toLong()).toInt()
        val tail = read(raf, len - span, span)
        for (at in span - EOCD_MIN downTo 0) {
            if (tail.getInt(at) == EOCD_MAGIC) {
                return tail.getInt(at + CD_OFFSET_AT).toLong() and 0xffffffffL
            }
        }
        return null
    }

    /** signers -> signer -> signed data -> (digests, certificates) -> certificates[0]. */
    private fun firstCert(scheme: ByteBuffer): ByteArray {
        val signers = prefixed(scheme)
        val signer = prefixed(signers)
        val signed = prefixed(signer)
        prefixed(signed)                     // digests
        val certs = prefixed(signed)
        val cert = prefixed(certs)
        return ByteArray(cert.remaining()).also { cert.get(it) }
    }

    private fun prefixed(b: ByteBuffer): ByteBuffer = slice(b, b.getInt())

    private fun slice(b: ByteBuffer, n: Int): ByteBuffer {
        require(n >= 0 && n <= b.remaining()) { "length prefix runs past its block" }
        val s = b.slice().order(ByteOrder.LITTLE_ENDIAN)
        s.limit(n)
        b.position(b.position() + n)
        return s
    }

    private fun read(raf: RandomAccessFile, at: Long, n: Int): ByteBuffer {
        val bytes = ByteArray(n)
        raf.seek(at)
        raf.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
