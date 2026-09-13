package com.ripostelabs.carlauncher.carlib

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * McuLink — a byte pipe to the MCU. [TtyLink] is the real one; tests substitute their own.
 *
 * ── Three carriers, one interface ───────────────────────────────────────────────────────────────
 *
 *     car   : MCU ──UART──▶ /dev/ttyHS1 ──▶ TtyLink      (termios via stty)
 *     desk  : carsim ──TCP──▶ QEMU virtserialport ──▶ /dev/vportNpM ──▶ CharDevLink  (no termios)
 *     desk  : carsim ──TCP──▶ 10.0.2.2:port ──▶ TcpLink   (any host that can reach the panel)
 *
 * [McuLinkSpec] picks one from the `riposte.mcu.link` property; the protocol above this line is
 * identical on all three, which is the point: the car link is exercised on the desk byte for byte.
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

        /** A baud of zero leaves the line speed alone: the carrier has none (a virtio port, a pty). */
        const val BAUD_UNCHANGED = 0

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

            // Open before stty, not after: a driver flagged TTY_DRIVER_RESET_TERMIOS (hvc, the
            // QEMU virtio console) forgets its termios on last close, so a setting made by a
            // process that then exits is gone before we open. Holding the node open across the
            // stty call keeps the setting on every driver.
            val link = TtyLink(FileInputStream(node), FileOutputStream(node))
            try {
                configure(path, baud)
            } catch (e: IOException) {
                link.close()
                throw e
            }
            return link
        }

        private fun configure(path: String, baud: Int) {
            // raw: no line discipline, no echo, 8N1, no flow control — what the vendor's native
            // open sets, minus anything we cannot see in its decompile.
            val speed = if (baud == BAUD_UNCHANGED) emptyList() else listOf(baud.toString())
            val process = ProcessBuilder(listOf(STTY, "-F", path) + speed + listOf("raw", "-echo", "cs8", "-cstopb", "-parenb", "-crtscts"))
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

/**
 * CharDevLink — one file descriptor on a character device that is not a tty.
 *
 * The QEMU `virtserialport` the emulator farm exposes as `/dev/vportNpM` refuses a second open
 * with EBUSY and has no termios, so [TtyLink]'s two streams plus stty cannot serve it. One
 * [RandomAccessFile] in `rw` mode is a single open that both reads and writes.
 */
class CharDevLink private constructor(private val file: RandomAccessFile) : McuLink {

    @Volatile
    private var closed = false

    /**
     * A virtio port reads EOF while nothing is attached on the host side, and again the moment
     * the host program exits; the port is still ours. So EOF here means "wait for the host",
     * and only [close] ends the stream.
     */
    override fun read(buffer: ByteArray): Int {
        while (!closed) {
            val n = file.read(buffer)
            if (n > 0) {
                return n
            }
            Thread.sleep(HOST_ABSENT_POLL_MS)
        }
        return -1
    }

    /** With no host attached the kernel answers ENODEV; the bytes go where a silent MCU's would. */
    override fun write(bytes: ByteArray) {
        try {
            file.write(bytes)
        } catch (e: IOException) {
            Log.w(LOG_TAG, "write of ${bytes.size} bytes dropped, no host on the port: ${e.message}")
        }
    }

    override fun close() {
        closed = true
        runCatching { file.close() }
    }

    companion object {
        private const val LOG_TAG = "CharDevLink"
        private const val MODE_READ_WRITE = "rw"
        private const val HOST_ABSENT_POLL_MS = 200L

        fun open(path: String): CharDevLink {
            val node = File(path)
            if (!node.exists()) {
                throw IOException("$path does not exist")
            }
            return CharDevLink(RandomAccessFile(node, MODE_READ_WRITE))
        }
    }
}

/**
 * TcpLink — the MCU on the far end of a socket. On the emulator `10.0.2.2` is the host running
 * the simulator; on a bench it is whatever box holds the serial adapter.
 */
class TcpLink private constructor(private val socket: Socket) : McuLink {

    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override fun read(buffer: ByteArray): Int = input.read(buffer)

    override fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 3_000

        fun open(host: String, port: Int): TcpLink {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw IOException("connect $host:$port: ${e.message}")
            }
            return TcpLink(socket)
        }
    }
}
