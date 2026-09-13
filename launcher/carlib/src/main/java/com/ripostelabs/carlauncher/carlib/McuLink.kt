package com.ripostelabs.carlauncher.carlib

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * McuLink — a byte pipe to the MCU. [TtyLink] is the real one; tests substitute their own.
 *
 * ── Why toybox `stty` and not JNI ───────────────────────────────────────────────────────────────
 * The vendor sets the line with a native `libserial_port.so`. Java cannot touch termios, but
 * Android 13's toybox ships `/system/bin/stty`, and termios settings outlive the process that set
 * them, so one `stty -F /dev/ttyHS1 115200 raw` before opening the node is enough. The launcher
 * keeps shipping no native code, which is what lets the x86_64 emulator run the same APK.
 * Verified 2026-09-13 on the farm's Android 13 image (`which stty` → `/system/bin/stty`).
 *
 * The node is `crw-rw-rw- system system` on the unit, so no root is needed to open it.
 */
interface McuLink {
    /** Blocks until at least one byte is available; returns the count, or -1 when the link is gone. */
    fun read(buffer: ByteArray): Int

    fun write(bytes: ByteArray)

    fun close()
}

class TtyLink private constructor(
    private val input: FileInputStream,
    private val output: FileOutputStream,
) : McuLink {

    override fun read(buffer: ByteArray): Int = input.read(buffer)

    override fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    override fun close() {
        runCatching { input.close() }
        runCatching { output.close() }
    }

    companion object {
        /** `/dev/ttyHS1` at 115200, flags 0 — `EventService.openSerialPort`, EventService.java:1685. */
        const val VENDOR_PATH = "/dev/ttyHS1"
        const val VENDOR_BAUD = 115200

        private const val STTY = "/system/bin/stty"
        private const val STTY_TIMEOUT_S = 5L

        /**
         * Configure the line and open it. Throws [IOException] naming which step refused, because
         * "no bytes" afterwards would look like a silent MCU.
         */
        fun open(path: String = VENDOR_PATH, baud: Int = VENDOR_BAUD): TtyLink {
            val node = File(path)
            if (!node.exists()) {
                throw IOException("$path does not exist")
            }

            configure(path, baud)
            return TtyLink(FileInputStream(node), FileOutputStream(node))
        }

        private fun configure(path: String, baud: Int) {
            // raw: no line discipline, no echo, 8N1, no flow control — what the vendor's native
            // open sets, minus anything we cannot see in its decompile.
            val process = ProcessBuilder(STTY, "-F", path, baud.toString(), "raw", "-echo", "cs8", "-cstopb", "-parenb", "-crtscts")
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(STTY_TIMEOUT_S, TimeUnit.SECONDS)) {
                process.destroy()
                throw IOException("stty timed out on $path")
            }

            val exit = process.exitValue()
            if (exit != 0) {
                throw IOException("stty exit $exit on $path: ${process.inputStream.bufferedReader().readText().trim()}")
            }
        }
    }
}
