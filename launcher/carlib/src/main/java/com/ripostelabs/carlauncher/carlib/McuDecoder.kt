package com.ripostelabs.carlauncher.carlib

import android.util.Log

/**
 * The listener half of [McuOwner]'s dispatch: one inbound [McuSerial.Command] in, the matching
 * [McuOwner.Listener] calls out. No port, no writes, so it runs wherever the commands arrive:
 *
 *     McuOwner (port owner) ──▶ McuDecoder ──▶ its listener
 *     car service ──ICarListener.onMcuEvent──▶ RemoteMcuOwner ──▶ McuDecoder ──▶ launcher listener
 *
 * Both sides decode the same bytes with the same code, so a launcher bound to the car service
 * hears exactly what it heard as the owner.
 */
class McuDecoder(private val listener: McuOwner.Listener) {

    /** Mirrors `mBackcarConnected`: while set, most panel keys are dropped as the vendor drops them. */
    @Volatile
    private var reversing = false

    private val loggedUnhandled = mutableSetOf<Int>()

    /** The box's stream as the 0xA5 relays rebuild it; [reset] starts a fresh one per session. */
    private var canRelay = McuCanRelay()

    /** One box frame cut from the relay stream; the decoder keys on the box's cmd, not the relay opcode. */
    private val loggedBoxCmds = mutableSetOf<Int>()

    /** A new link (or a new binding): a half relay from the old one must not prefix the next. */
    fun reset() {
        canRelay = McuCanRelay()
    }

    fun decode(command: McuSerial.Command) {
        McuOwnerProtocol.sysEvent(command)?.let { reversing = it.reverse; listener.onSysEvent(it); return }
        McuOwnerProtocol.mainVolume(command)?.let { listener.onMainVolume(it); return }
        McuOwnerProtocol.mute(command)?.let { listener.onMute(it); return }
        McuOwnerProtocol.panelKey(command)?.let { onPanelKey(it); return }
        McuOwnerProtocol.wheelKey(command)?.let { listener.onWheelKey(it); return }
        McuOwnerProtocol.wheelState(command)?.let { listener.onWheelState(it); return }
        McuOwnerProtocol.radioEvent(command)?.let { listener.onRadio(it); return }
        McuOwnerProtocol.rtcTime(command)?.let { listener.onRtc(it); return }
        McuOwnerProtocol.mcuVersion(command)?.let { listener.onMcuVersion(it); return }
        if (McuOwnerProtocol.isWake(command)) {
            listener.onWake()
            return
        }

        // 0xA5 relays a slice of the CAN box's stream, not always one whole frame; see McuCanRelay.
        if (command.opcode == McuSerial.OP_CAN) {
            val cut = canRelay.feed(command.payload)
            listener.onCanRelay(command.payload, cut)
            cut.forEach(::onRelayed)
            return
        }

        // Once per opcode: the MCU streams 0x8E (G-sensor, RADAR_3DH) at 10 Hz for the whole
        // drive and the vendor only stores the bytes; the log is for the first sighting.
        if (loggedUnhandled.add(command.opcode)) {
            Log.i(LOG_TAG, "unhandled opcode 0x%02X (%d bytes): %s".format(
                command.opcode, command.payload.size, command.payload.joinToString(" ") { "%02X".format(it) }))
        }
        listener.onOther(command)
    }

    private fun onRelayed(inner: McuFrame.Decoded) {
        when (inner) {
            is McuFrame.Decoded.Frame -> {
                // Once per box cmd: the first 0x11 in a car log proves the wheel keys arrive.
                if (loggedBoxCmds.add(inner.cmd)) {
                    Log.i(LOG_TAG, "CAN box cmd 0x%02X first seen (%d bytes)".format(inner.cmd, inner.payload.size))
                }
                listener.onCanSignal(HiworldCanDecoder.decodePayload(inner.cmd, inner.payload), System.currentTimeMillis())
            }

            is McuFrame.Decoded.Malformed ->
                Log.w(LOG_TAG, "CAN relay frame malformed: ${inner.reason}")
        }
    }

    // With the camera up only [McuOwnerProtocol.panelKeyPassesReverse] keys count (onCmdKeyEvent).
    private fun onPanelKey(key: McuOwnerProtocol.PanelKey) {
        if (reversing && !McuOwnerProtocol.panelKeyPassesReverse(key.code)) {
            return
        }

        listener.onKey(key.code)
        listener.onPanelKey(key)
    }

    private companion object {
        // The owner's tag: riposte-diag.sh and the car logs grep for McuOwner.
        const val LOG_TAG = "McuOwner"
    }
}
