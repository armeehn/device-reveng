package com.ripostelabs.car

import android.content.Context
import android.util.Base64
import com.ripostelabs.carlauncher.carlib.AndroidOwnerGate
import com.ripostelabs.carlauncher.carlib.CarProfile
import com.ripostelabs.carlauncher.carlib.CarProfiles
import com.ripostelabs.carlauncher.carlib.FramePack
import com.ripostelabs.carlauncher.carlib.McuOwner
import com.ripostelabs.carlauncher.carlib.McuOwnerProtocol
import kotlinx.coroutines.flow.StateFlow

/**
 * The one McuOwner on the image, in the car service (os/CARHAL.md "One owner"). Same gate and
 * same link spec as the launcher used: ownerEnabled and no eventcenter, `riposte.mcu.link`
 * (on 0.2 `tcp:127.0.0.1:5588` via riposte-mcubridge.sh; /dev/ttyHS1 is labelled `device`,
 * which no app domain may open). Every inbound command and CAN box round goes to [forward].
 *
 * The launcher's startup frames and car choice are kept across boots, so the boot handshake
 * already carries the last volume, zone and setup table before the launcher is up.
 */
class OwnerHost(context: Context, forward: (McuEvent) -> Unit) : Link {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val gate = AndroidOwnerGate(context)

    private val boxCar = object : McuOwner.Listener {
        override fun onCanBoxCar(car: CarProfile) = forward(McuEvent.of(car))
    }

    private val owner = McuOwner(
        gate,
        boxCar,
        openLink = { gate.mcuLink().open() },
        initialCar = CarProfiles.byId(prefs.getString(KEY_CAR, null)),
        tap = { forward(McuEvent.of(it)) },
    )

    val statusFlow: StateFlow<McuOwner.Status> = owner.status

    init {
        stored()?.let { owner.useStartup(FramePack.unpack(it)) }
    }

    override fun status() = owner.status.value

    override fun open() = owner.start()

    override fun close() = owner.stop()

    // New frames reach the MCU the way a launcher restart sent them: a fresh handshake. An
    // unchanged set (the usual boot) sends nothing; a closed link keeps them for the next open.
    override fun setStartup(packed: ByteArray) {
        if (stored()?.contentEquals(packed) == true) {
            return
        }

        val frames = FramePack.unpack(packed)
        prefs.edit().putString(KEY_STARTUP, Base64.encodeToString(packed, Base64.NO_WRAP)).apply()
        owner.useStartup(frames)
        if (owner.status.value !is McuOwner.Status.Idle) {
            owner.stop()
            owner.start()
        }
    }

    override fun setSource(mode: Int): Boolean {
        val m = McuOwnerProtocol.Mode.entries.firstOrNull { it.code == mode } ?: return false
        return owner.setMode(m)
    }

    override fun currentSource(): Int = owner.lastMode?.code ?: Link.NO_SOURCE

    override fun selectCar(id: String) {
        prefs.edit().putString(KEY_CAR, id).apply()
        owner.selectCar(CarProfiles.byId(id))
    }

    override fun send(frame: ByteArray) = owner.send(frame)

    private fun stored(): ByteArray? =
        prefs.getString(KEY_STARTUP, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    private companion object {
        const val PREFS = "owner"
        const val KEY_STARTUP = "startup"
        const val KEY_CAR = "car"
    }
}
