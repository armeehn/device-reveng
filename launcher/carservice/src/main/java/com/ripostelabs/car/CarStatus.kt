package com.ripostelabs.car

import android.os.Parcel
import android.os.Parcelable

/**
 * One snapshot of the car, as ICarService.status() and ICarListener.onStatus() carry it.
 * [uid] is the service's own uid: 1000 proves it runs as android.uid.system.
 */
data class CarStatus(val mcuLinkUp: Boolean, val uid: Int) : Parcelable {

    override fun describeContents() = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeBoolean(mcuLinkUp)
        dest.writeInt(uid)
    }

    companion object {
        @JvmField
        val CREATOR = object : Parcelable.Creator<CarStatus> {
            override fun createFromParcel(src: Parcel) = CarStatus(src.readBoolean(), src.readInt())
            override fun newArray(size: Int) = arrayOfNulls<CarStatus>(size)
        }
    }
}
