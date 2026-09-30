package com.ripostelabs.car

import android.os.Binder
import android.os.Parcel

/**
 * IEventService on the wire, for stock-built apps (os/CARHAL.md "IEventService compatibility").
 * Hand-marshalled like the suite radio's VendorTuner: the subset is 16 of 144 calls, and every
 * other code gets a zero reply of its declared shape ([EventTable]) instead of 128 overrides.
 *
 * [calls] is null until CarService is up; every call then gets its zero reply.
 */
class EventBinder(private val calls: () -> EventCalls?) : Binder() {

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == INTERFACE_TRANSACTION) {
            reply?.writeString(DESCRIPTOR)
            return true
        }

        val call = EventTable.calls[code] ?: return super.onTransact(code, data, reply, flags)
        data.enforceInterface(DESCRIPTOR)
        reply?.writeNoException()

        val live = calls()
        if (live != null && serve(live, code, data, reply)) {
            return true
        }

        live?.unserved(call.name)
        reply?.let { zero(it, call.reply) }
        return true
    }

    // Arguments in the vendor stub's order; AIDL sends boolean and byte as one int each.
    private fun serve(c: EventCalls, code: Int, data: Parcel, reply: Parcel?): Boolean {
        when (code) {
            SEND_MODE -> c.sendMode(data.readInt())
            SEND_RADIO_KEY -> c.radioKey(data.readInt())
            SEND_USER_FREQ -> c.userFreq(data.readInt(), data.readInt() != 0)
            SEND_MUTE -> c.mute(data.readInt() != 0)
            SEND_BACKLIGHT -> c.backlight(unsigned(data.readByte()), unsigned(data.readByte()))
            SOFTWARE_REBOOT -> c.reboot()
            GET_VALID_MODE -> reply?.writeInt(c.validMode())
            GET_MCU_VER -> reply?.writeString(c.mcuVer())
            IS_BACK_CAR -> reply?.writeInt(bit(c.backCar()))
            IS_MUTE_ON -> reply?.writeInt(bit(c.muteOn()))
            GET_MAIN_VOL -> reply?.writeByte(c.mainVolume().toByte())
            GET_SETTING_BOOLEAN -> reply?.writeInt(bit(c.setting(data.readString(), data.readInt() != 0)))
            GET_SETTING_FLOAT -> reply?.writeFloat(c.setting(data.readString(), data.readFloat()))
            GET_SETTING_INT -> reply?.writeInt(c.setting(data.readString(), data.readInt()))
            GET_SETTING_LONG -> reply?.writeLong(c.setting(data.readString(), data.readLong()))
            GET_SETTING_STRING -> reply?.writeString(c.setting(data.readString(), data.readString()))
            else -> return false
        }
        return true
    }

    private fun zero(reply: Parcel, shape: Reply) {
        when (shape) {
            Reply.VOID -> Unit
            Reply.INT -> reply.writeInt(0)
            Reply.LONG -> reply.writeLong(0)
            Reply.FLOAT -> reply.writeFloat(0f)
            Reply.STRING -> reply.writeString(null)
            Reply.ARRAY -> reply.writeInt(NULL_ARRAY)
            Reply.BINDER -> reply.writeStrongBinder(null)
        }
    }

    private fun bit(on: Boolean) = if (on) 1 else 0

    private fun unsigned(b: Byte) = b.toInt() and BYTE

    companion object {
        const val DESCRIPTOR = "com.szchoiceway.eventcenter.IEventService"

        // Vendor transaction codes (ordinals in carlib's IEventService.aidl), the served subset.
        const val SEND_MODE = 1
        const val SEND_RADIO_KEY = 2
        const val SEND_USER_FREQ = 6
        const val SEND_MUTE = 8
        const val GET_MCU_VER = 32
        const val GET_SETTING_BOOLEAN = 40
        const val GET_SETTING_FLOAT = 41
        const val GET_SETTING_INT = 42
        const val GET_SETTING_LONG = 43
        const val GET_SETTING_STRING = 44
        const val GET_VALID_MODE = 46
        const val SEND_BACKLIGHT = 60
        const val IS_BACK_CAR = 88
        const val GET_MAIN_VOL = 103
        const val IS_MUTE_ON = 104
        const val SOFTWARE_REBOOT = 135

        /** Parcel's length marker for a null array. */
        private const val NULL_ARRAY = -1
        private const val BYTE = 0xFF
    }
}
