package com.ripostelabs.carlauncher.carlib

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * CarCommandPort — the few things Helm may do to the car, as JSON lines on a loopback socket.
 *
 *     Helm ──adb forward──▶ 127.0.0.1:5590 ──▶ CarCommandPort ──▶ Target (CarService)
 *
 * The read side is [McuStateExport]; this is the write side, and it is an allow-list, not a
 * pass-through: `volume`, `mute` and `mode` exist here, nothing else does, and `mode` only names
 * the sources a passenger could pick on the screen. Power, factory reset, backlight and raw
 * frames are not on the list on purpose. One request line, one reply line:
 *
 *     {"cmd":"volume","level":12}       →  {"ok":true}
 *     {"cmd":"mode","mode":"RADIO"}     →  {"ok":true}
 *     {"cmd":"mode","mode":"POWER_OFF"} →  {"ok":false,"error":"mode not allowed: POWER_OFF"}
 */
class CarCommandPort(
    private val target: Target,
    private val port: Int = DEFAULT_PORT,
) {

    /** What the port may call. [CarService] is the one in the launcher; tests pass a fake. */
    interface Target {
        fun setVolume(level: Int)
        fun setMute(on: Boolean)
        fun setMode(mode: McuOwnerProtocol.Mode): Boolean
    }

    companion object {
        const val DEFAULT_PORT = 5590
        private const val LOOPBACK = "127.0.0.1"
        private const val BACKLOG = 2
        private const val CMD = "cmd"
        private const val OK = "ok"
        private const val ERROR = "error"

        /** Sources a passenger could pick on the screen. Everything else in [McuOwnerProtocol.Mode] stays out. */
        val ALLOWED_MODES = setOf(
            McuOwnerProtocol.Mode.RADIO,
            McuOwnerProtocol.Mode.BT_MUSIC,
            McuOwnerProtocol.Mode.MUSIC,
            McuOwnerProtocol.Mode.ANDROID,
            McuOwnerProtocol.Mode.CARPLAY,
            McuOwnerProtocol.Mode.AUX,
            McuOwnerProtocol.Mode.HOME,
        )
    }

    private var server: ServerSocket? = null

    val boundPort: Int
        get() = server?.localPort ?: port

    fun start() {
        if (server != null) {
            return
        }

        val socket = ServerSocket(port, BACKLOG, InetAddress.getByName(LOOPBACK))
        server = socket
        Thread({ acceptLoop(socket) }, "car-command-port").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: return
            Thread({ serve(client) }, "car-command-client").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    private fun serve(client: Socket) = client.use {
        val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(it.getOutputStream(), Charsets.UTF_8))
        for (line in reader.lineSequence()) {
            if (line.isBlank()) {
                continue
            }
            writer.write(handle(line).toString())
            writer.newLine()
            writer.flush()
        }
    }

    /** One request line to one reply object. Public so tests need no socket. */
    fun handle(line: String): JSONObject {
        val reply = JSONObject()
        val result = runCatching { dispatch(JSONObject(line)) }
        val error = result.exceptionOrNull()?.message ?: result.getOrNull()
        if (error == null) {
            return reply.put(OK, true)
        }
        return reply.put(OK, false).put(ERROR, error)
    }

    /** Null on success, an error string otherwise. */
    private fun dispatch(request: JSONObject): String? = when (val cmd = request.optString(CMD)) {
        "volume" -> {
            target.setVolume(request.getInt("level"))
            null
        }
        "mute" -> {
            target.setMute(request.getBoolean("on"))
            null
        }
        "mode" -> setMode(request.getString("mode"))
        else -> "unknown cmd: $cmd"
    }

    private fun setMode(name: String): String? {
        val mode = McuOwnerProtocol.Mode.entries.firstOrNull { it.name == name }
        if (mode == null || mode !in ALLOWED_MODES) {
            return "mode not allowed: $name"
        }
        if (!target.setMode(mode)) {
            return "mode not acknowledged: $name"
        }
        return null
    }
}
