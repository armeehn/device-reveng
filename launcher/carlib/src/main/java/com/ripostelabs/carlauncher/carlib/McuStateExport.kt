package com.ripostelabs.carlauncher.carlib

import android.util.Log

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.lang.reflect.Modifier
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.LocalDateTime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * McuStateExport — the owner's decoded MCU events, one JSON line each, on a loopback socket.
 *
 *     McuOwner ──▶ FanOut ──▶ McuStateExport ──▶ 127.0.0.1:5589 ──adb forward──▶ Helm (car computer)
 *
 * Helm needs the car's state (ACC line, volume, radio, CAN signals) and must never open the MCU
 * link itself: `riposte-mcubridge` takes one client, and a second reader on the wire splits the
 * stream (see [McuTapSource]). So the launcher, which already decodes every frame, publishes the
 * result here. Loopback only: the socket is reached through `adb forward`, never the LAN.
 *
 * A client gets the last value of every event on connect, so it starts with the whole state and
 * not with silence until the next frame. Lines look like
 * `{"event":"sysEvent","atMs":1726800000000,"accLine":true,"brake":false,...}`.
 */
class McuStateExport(
    private val port: Int = DEFAULT_PORT,
    private val now: () -> Long = System::currentTimeMillis,
) : McuOwner.Listener {

    companion object {
        const val DEFAULT_PORT = 5589
        private const val EVENT = "event"
        private const val AT_MS = "atMs"
        private const val VALUE = "value"
        private const val TYPE = "type"
        private const val LOOPBACK = "127.0.0.1"
        private const val TAG = "McuStateExport"
        private const val BACKLOG = 4
    }

    private val clients = CopyOnWriteArrayList<BufferedWriter>()

    // Last line per event name, replayed to every new client. Keyed events (radio, canSignal)
    // also carry their subtype so a Frequency does not overwrite a StationName.
    private val last = LinkedHashMap<String, String>()

    private var server: ServerSocket? = null

    private var acceptor: Thread? = null

    /** The bound port: [port], or the ephemeral one when constructed with 0 (tests). */
    val boundPort: Int
        get() = server?.localPort ?: port

    /**
     * Bind and serve. A port already taken (a second launcher build on the same device, or an
     * instance the system has not reaped yet) is not a reason to lose the launcher: the export
     * is a diagnostic, Helm reconnects, and the car UI must come up regardless. It crashed
     * MainActivity.onCreate before this guard (farm, 2026-09-22).
     */
    fun start() {
        if (server != null) {
            return
        }

        val socket = runCatching { ServerSocket(port, BACKLOG, InetAddress.getByName(LOOPBACK)) }
            .onFailure { Log.w(TAG, "state export off: port $port is taken ($it)") }
            .getOrNull() ?: return
        server = socket
        acceptor = Thread({ acceptLoop(socket) }, "mcu-state-export").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: return
            attach(client)
        }
    }

    private fun attach(client: Socket) {
        val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8))
        val replay = synchronized(last) { last.values.toList() }

        // Replay before joining the live set, so the snapshot never interleaves with a new frame.
        if (replay.any { !write(writer, it) }) {
            return
        }
        clients.add(writer)
    }

    private fun write(writer: BufferedWriter, line: String): Boolean = runCatching {
        writer.write(line)
        writer.newLine()
        writer.flush()
    }.isSuccess

    private fun publish(key: String, json: JSONObject) {
        val line = json.toString()
        synchronized(last) { last[key] = line }

        for (client in clients) {
            if (!write(client, line)) {
                clients.remove(client)
                runCatching { client.close() }
            }
        }
    }

    private fun envelope(event: String): JSONObject = JSONObject()
        .put(EVENT, event)
        .put(AT_MS, now())

    private fun publishFields(event: String, value: Any, key: String = event) {
        publish(key, fields(value, envelope(event)))
    }

    override fun onSysEvent(event: McuOwnerProtocol.SysEvent) = publishFields("sysEvent", event)

    override fun onMainVolume(volume: McuOwnerProtocol.MainVolume) = publishFields("mainVolume", volume)

    override fun onMute(mute: McuOwnerProtocol.Mute) = publishFields("mute", mute)

    override fun onKey(key: Int) = publish("key", envelope("key").put(VALUE, key))

    override fun onPanelKey(key: McuOwnerProtocol.PanelKey) = publishFields("panelKey", key)

    override fun onWheelKey(key: McuOwnerProtocol.WheelKey) = publishFields("wheelKey", key)

    override fun onRadio(event: McuOwnerProtocol.RadioEvent) = publishTyped("radio", event)

    override fun onCanSignal(signal: CanSignal, atMs: Long) = publishTyped("canSignal", signal)

    override fun onWake() = publish("wake", envelope("wake"))

    override fun onRtc(time: LocalDateTime) = publish("rtc", envelope("rtc").put(VALUE, time.toString()))

    /** Sealed-type events: the subclass name is the [TYPE] and part of the replay key. */
    private fun publishTyped(event: String, value: Any) {
        val type = value.javaClass.simpleName
        publish("$event/$type", fields(value, envelope(event).put(TYPE, type)))
    }

    /**
     * A data class's fields by name, through Java reflection: carlib has no kotlin-reflect, and
     * the CAN and radio families are too many classes to hand-map. Enums become their name,
     * nested data classes nested objects, lists arrays.
     */
    private fun fields(value: Any, into: JSONObject): JSONObject {
        for (field in value.javaClass.declaredFields) {
            if (Modifier.isStatic(field.modifiers) || field.isSynthetic) {
                continue
            }

            field.isAccessible = true
            into.put(field.name, jsonValue(field.get(value)))
        }
        return into
    }

    private fun jsonValue(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is Boolean, is Number, is String -> v
        is Enum<*> -> v.name
        is Collection<*> -> JSONArray(v.map(::jsonValue))
        else -> fields(v, JSONObject())
    }
}
