package com.ripostelabs.car

import android.os.Parcel
import android.os.Parcelable

/**
 * The reverse camera around its picture, as ICarService.reverseState() and
 * ICarListener.onReverse() carry it. [trigger] is the `71` reverse bit while the MCU link runs
 * (the vendor's "awake"); the speed gate on top stays with the client that shows the picture.
 * [decoderMode] is the PR2000 row (0 auto .. 8), [signal] the raw camera_status or [NO_SIGNAL].
 */
data class ReverseState(
    val trigger: Boolean = false,
    val decoderMode: Int = 0,
    val signal: Int = NO_SIGNAL,
) : Parcelable {

    /** True when the AIS server can size a stream from [signal]. */
    val locked: Boolean
        get() = signal in SIZED_STATUS

    override fun describeContents() = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeBoolean(trigger)
        dest.writeInt(decoderMode)
        dest.writeInt(signal)
    }

    companion object {
        /** camera_status could not be read. */
        const val NO_SIGNAL = -1

        /** libais_pr2000.so accepts status - 1 in 0..12 (`cmp w8, #0xc`), else 0 x 0. */
        private val SIZED_STATUS = 1..13

        @JvmField
        val CREATOR = object : Parcelable.Creator<ReverseState> {
            override fun createFromParcel(src: Parcel) = ReverseState(
                trigger = src.readBoolean(),
                decoderMode = src.readInt(),
                signal = src.readInt(),
            )

            override fun newArray(size: Int) = arrayOfNulls<ReverseState>(size)
        }
    }
}
