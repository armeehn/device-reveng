package com.ripostelabs.car

import com.ripostelabs.carlauncher.carlib.McuOwnerProtocol
import java.util.concurrent.ConcurrentHashMap

/**
 * The vendor IEventService subset (os/CARHAL.md "IEventService compatibility"), mapped onto the
 * service's own owner so a stock-built app gets an answer instead of a dead binder.
 *
 * Reads answer anyone: READ is a normal permission, and no stock app asks for it. Changes need
 * CONTROL, as on ICarService. A refused change is dropped and noted once, never thrown, because
 * a stock app expects no SecurityException from these calls.
 */
class EventCalls(
    private val gate: Gate,
    private val link: Link,
    private val power: Power,
    private val reversing: () -> Boolean,
    private val note: (String) -> Unit,
) {

    // What the MCU last reported; the vendor cached the same frames for its getters.
    @Volatile
    private var version = ""

    @Volatile
    private var muted = false

    @Volatile
    private var volume = 0

    private val noted = ConcurrentHashMap.newKeySet<String>()

    /** Every inbound MCU event, in the owner's order: keeps the version, mute and volume. */
    fun observe(event: McuEvent) {
        val command = event.command() ?: return
        McuOwnerProtocol.mcuVersion(command)?.let { version = it }
        McuOwnerProtocol.mute(command)?.let { muted = it.muted }
        McuOwnerProtocol.mainVolume(command)?.let { volume = it.level }
    }

    /** getValidMode: the source last set, NONE (0) before any, never the owner's -1. */
    fun validMode(): Int {
        val source = link.currentSource()
        if (source == Link.NO_SOURCE) {
            return McuOwnerProtocol.Mode.NONE.code
        }
        return source
    }

    /** IsBackCarConneted: the `71` reverse bit, as ICarService.reverseState().trigger. */
    fun backCar(): Boolean = reversing()

    /** getMCUVer: empty until the version ack arrives. */
    fun mcuVer(): String = version

    fun muteOn(): Boolean = muted

    fun mainVolume(): Int = volume

    /**
     * getSetting*: the caller's fallback. The Sys_* rows live in the launcher's SysVarLocalStore,
     * not here, so the fallback is the answer that app would get on a fresh unit.
     */
    fun <T> setting(key: String?, fallback: T): T = fallback

    /** sendMode: the vendor's second argument is not read; the owner has one way to set a source. */
    fun sendMode(mode: Int) = control("sendMode") { link.setSource(mode) }

    fun radioKey(key: Int) = control("sendRadioKey") { link.send(McuOwnerProtocol.radioKey(key)) }

    fun userFreq(freq: Int, fm: Boolean) =
        control("sendUserFreq") { link.send(McuOwnerProtocol.userFreq(freq, fm)) }

    fun mute(on: Boolean) = control("sendMuteState") { link.send(McuOwnerProtocol.mute(on)) }

    fun backlight(day: Int, night: Int) =
        control("sendBacklight") { link.send(McuOwnerProtocol.backlight(day, night)) }

    fun reboot() = control("sendSoftWareReboot") { power.reboot() }

    /** A call outside the subset: noted the first time only, so a polling app cannot flood logcat. */
    fun unserved(name: String) = once(name, "$name is not served; default returned")

    private fun control(name: String, action: () -> Unit) {
        try {
            gate.enforce(Access.CONTROL)
        } catch (e: SecurityException) {
            once(name, "$name refused: ${e.message}")
            return
        }
        action()
    }

    private fun once(key: String, message: String) {
        if (!noted.add(key)) {
            return
        }
        note(message)
    }
}
