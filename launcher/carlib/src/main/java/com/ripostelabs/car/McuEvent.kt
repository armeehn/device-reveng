package com.ripostelabs.car

import android.os.Parcel
import android.os.Parcelable
import com.ripostelabs.carlauncher.carlib.CarProfile
import com.ripostelabs.carlauncher.carlib.McuSerial

/**
 * One MCU event on ICarListener.onMcuEvent. The service forwards the raw inbound command and
 * the client decodes it with the same McuDecoder the owner runs, so one parcel type carries
 * every McuOwner.Listener call instead of one parcelable per decoded type:
 *
 *     COMMAND      code = opcode, bytes = payload   (sys, volume, keys, radio, CAN relay, RTC, ...)
 *     CAN_BOX_CAR  text = CarProfile.id             (the box was told this car)
 */
data class McuEvent(
    val kind: Kind,
    val code: Int = 0,
    val bytes: ByteArray = ByteArray(0),
    val text: String = "",
) : Parcelable {

    /** Wire order is the ordinal: append only. UNKNOWN is what an older client reads a newer kind as. */
    enum class Kind { UNKNOWN, COMMAND, CAN_BOX_CAR }

    /** The inbound command, for [Kind.COMMAND]; null otherwise. */
    fun command(): McuSerial.Command? {
        if (kind != Kind.COMMAND) {
            return null
        }
        return McuSerial.Command(code, bytes)
    }

    // ByteArray equality is identity, which would make this data class lie.
    override fun equals(other: Any?): Boolean =
        other is McuEvent && kind == other.kind && code == other.code &&
            bytes.contentEquals(other.bytes) && text == other.text

    override fun hashCode(): Int = ((kind.hashCode() * 31 + code) * 31 + bytes.contentHashCode()) * 31 + text.hashCode()

    override fun describeContents() = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(kind.ordinal)
        dest.writeInt(code)
        dest.writeByteArray(bytes)
        dest.writeString(text)
    }

    companion object {
        fun of(command: McuSerial.Command) = McuEvent(Kind.COMMAND, command.opcode, command.payload)

        fun of(car: CarProfile) = McuEvent(Kind.CAN_BOX_CAR, text = car.id)

        private fun kind(ordinal: Int) = Kind.entries.getOrElse(ordinal) { Kind.UNKNOWN }

        @JvmField
        val CREATOR = object : Parcelable.Creator<McuEvent> {
            override fun createFromParcel(src: Parcel) = McuEvent(
                kind = kind(src.readInt()),
                code = src.readInt(),
                bytes = src.createByteArray() ?: ByteArray(0),
                text = src.readString().orEmpty(),
            )

            override fun newArray(size: Int) = arrayOfNulls<McuEvent>(size)
        }
    }
}
