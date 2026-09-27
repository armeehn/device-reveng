package com.ripostelabs.carlauncher.carlib

import java.io.ByteArrayOutputStream

/**
 * A list of MCU frames as one byte[], so the handshake frames cross ICarService.setStartup
 * (AIDL has no List<byte[]>). Each frame is a 2-byte big-endian length, then its bytes:
 *
 *     [00 03] AA BB CC [00 01] DD   =  [AA BB CC], [DD]
 */
object FramePack {
    private const val LEN_BYTES = 2
    private const val MAX_FRAME = 0xFFFF
    private const val BYTE = 0xFF
    private const val BITS = 8

    fun pack(frames: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (frame in frames) {
            require(frame.size <= MAX_FRAME) { "frame of ${frame.size} bytes" }
            out.write(frame.size shr BITS)
            out.write(frame.size and BYTE)
            out.write(frame)
        }
        return out.toByteArray()
    }

    /** The frames back; a truncated tail is an error, not a short frame. */
    fun unpack(packed: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var at = 0
        while (at < packed.size) {
            require(at + LEN_BYTES <= packed.size) { "length cut at byte $at" }
            val len = ((packed[at].toInt() and BYTE) shl BITS) or (packed[at + 1].toInt() and BYTE)
            at += LEN_BYTES
            require(at + len <= packed.size) { "frame of $len bytes cut at byte $at" }
            frames += packed.copyOfRange(at, at + len)
            at += len
        }
        return frames
    }
}
