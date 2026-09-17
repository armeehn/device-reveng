package com.ripostelabs.carlauncher.tuner

import android.os.Parcel
import android.os.Parcelable
import com.ripostelabs.carlauncher.carlib.McuOwnerProtocol
import com.ripostelabs.carlauncher.carlib.RadioState

/**
 * The tuner as the suite radio sees it over [ITuner] (RAV4-97): [RadioState] with the vendor's
 * field names, so `Tuner.java` maps it onto what it read from `IEventService` before.
 *
 * Hand-written Parcelable, in field order, so a client without the aidl tool can read it.
 * [preset] is 0..5 or [NO_PRESET]; [stationList] is 42 slots, 0 = empty.
 */
data class TunerState(
    val band: Int,
    val freq: Int,
    val preset: Int,
    val stationName: String,
    val stereo: Boolean,
    val rds: Boolean,
    val stMono: Boolean,
    val dxLoc: Boolean,
    val ta: Boolean,
    val af: Boolean,
    val stationList: IntArray,
) : Parcelable {

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(band)
        dest.writeInt(freq)
        dest.writeInt(preset)
        dest.writeString(stationName)
        dest.writeInt(if (stereo) 1 else 0)
        dest.writeInt(if (rds) 1 else 0)
        dest.writeInt(if (stMono) 1 else 0)
        dest.writeInt(if (dxLoc) 1 else 0)
        dest.writeInt(if (ta) 1 else 0)
        dest.writeInt(if (af) 1 else 0)
        dest.writeIntArray(stationList)
    }

    companion object {
        const val NO_PRESET = -1

        /**
         * [RadioState.presetNumber] is the wire's 0..5 (sub 2), 0 until the MCU has reported, which
         * the readout cannot tell from slot 1; [RadioState.updatedAt] == 0 is the only "nothing yet".
         */
        fun of(state: RadioState): TunerState = TunerState(
            band = state.band,
            freq = state.freq,
            preset = if (state.updatedAt == 0L) NO_PRESET else state.presetNumber,
            stationName = state.stationName,
            stereo = state.stereo,
            rds = state.rds,
            stMono = state.stMono,
            dxLoc = state.dxLoc,
            ta = state.ta,
            af = state.af,
            stationList = IntArray(McuOwnerProtocol.RADIO_FREQ_LIST_SIZE) { state.stationList.getOrElse(it) { 0 } },
        )

        @JvmField
        val CREATOR = object : Parcelable.Creator<TunerState> {
            override fun createFromParcel(source: Parcel): TunerState = TunerState(
                band = source.readInt(),
                freq = source.readInt(),
                preset = source.readInt(),
                stationName = source.readString() ?: "",
                stereo = source.readInt() != 0,
                rds = source.readInt() != 0,
                stMono = source.readInt() != 0,
                dxLoc = source.readInt() != 0,
                ta = source.readInt() != 0,
                af = source.readInt() != 0,
                stationList = source.createIntArray() ?: IntArray(McuOwnerProtocol.RADIO_FREQ_LIST_SIZE),
            )

            override fun newArray(size: Int): Array<TunerState?> = arrayOfNulls(size)
        }
    }
}
