package com.ripostelabs.carlauncher.carlib

/**
 * McuCanRelay — the CAN box's own byte stream, rebuilt from the MCU's 0xA5 relays.
 *
 * ── Why a stream, not one frame per command ─────────────────────────────────────────────────────
 * The MCU relays slices of the box's UART stream, cut wherever its buffer ends. On 2026-09-22 the
 * car split one 66-byte frame over two commands:
 *
 *     0xA5 #1: 5A A5 3D cmd p0 … p45     50 bytes   "len says 61, so 66 bytes, but got 50"
 *     0xA5 #2: p46 … p60 ck              16 bytes   "bad header; expected 5A A5"
 *
 * canbus2 never assumed one frame per command: `CanbusDataService.header5AA5` appends each body,
 * opcode and CK cut off, to a 512-byte buffer and cuts `5A A5 | len | cmd | payload | ck` frames
 * out of it. This does the same, so a relay may carry part of a frame, one, or several.
 *
 * ── Resync ──────────────────────────────────────────────────────────────────────────────────────
 * Bytes before a header are reported once. A frame [McuFrame.decode] rejects is reported, then the
 * scan resumes right after its header: a lost relay costs the frame it cut, not the ones behind
 * it. Waiting is bounded: len is one byte, so at most 260 bytes sit behind a header.
 */
class McuCanRelay {

    private var pending = ByteArray(0)

    /** Push one 0xA5 body; returns every box frame it completed, in stream order. */
    fun feed(body: ByteArray): List<McuFrame.Decoded> {
        pending += body

        val out = ArrayList<McuFrame.Decoded>()
        var pos = 0

        // Bytes below this index were already reported inside a rejected frame; not junk twice.
        var reported = 0

        while (true) {
            val h = findHeader(pos)
            if (h < 0) {
                // Nothing frames the tail. Keep a lone 5A: its A5 may open the next relay.
                val keep = if (pending.isNotEmpty() && pending.last() == HEADER_0) 1 else 0
                val tail = maxOf(pos, pending.size - keep)
                junk(out, tail - maxOf(pos, reported))
                pos = tail
                break
            }
            junk(out, h - maxOf(pos, reported))

            // The whole frame must be here before it is judged; otherwise wait for the next relay.
            val lenAt = h + HEADER_SIZE
            if (lenAt >= pending.size) {
                pos = h
                break
            }
            val end = h + (pending[lenAt].toInt() and 0xFF) + FRAME_OVERHEAD
            if (end > pending.size) {
                pos = h
                break
            }

            val decoded = McuFrame.decode(pending.copyOfRange(h, end))
            out.add(decoded)
            reported = end
            pos = if (decoded is McuFrame.Decoded.Frame) end else h + HEADER_SIZE
        }

        pending = pending.copyOfRange(pos, pending.size)
        return out
    }

    private fun junk(out: MutableList<McuFrame.Decoded>, count: Int) {
        if (count <= 0) {
            return
        }
        out.add(McuFrame.Decoded.Malformed("skipped $count bytes before 5A A5"))
    }

    /** Index of the next `5A A5` at or after [from], or -1. */
    private fun findHeader(from: Int): Int {
        for (i in from until pending.size - 1) {
            if (pending[i] == HEADER_0 && pending[i + 1] == HEADER_1) {
                return i
            }
        }
        return -1
    }

    private companion object {
        const val HEADER_0: Byte = 0x5A
        const val HEADER_1: Byte = 0xA5.toByte()
        const val HEADER_SIZE = 2

        /** header(2) + len(1) + cmd(1) + ck(1), as in [McuFrame]. */
        const val FRAME_OVERHEAD = 5
    }
}
