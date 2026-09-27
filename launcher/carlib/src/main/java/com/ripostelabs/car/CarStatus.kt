package com.ripostelabs.car

import android.os.Parcel
import android.os.Parcelable
import com.ripostelabs.carlauncher.carlib.McuOwner

/**
 * One snapshot of the car, as ICarService.status() and ICarListener.onStatus() carry it.
 * [uid] is the service's own uid: 1000 proves it runs as android.uid.system. The rest is the
 * service's [McuOwner.Status], flattened so it crosses the binder.
 */
data class CarStatus(
    val uid: Int,
    val link: Link = Link.IDLE,
    val reason: String = "",
    val acked: Boolean = false,
    val frames: Long = 0,
    val badChecksum: Long = 0,
    val skipped: Long = 0,
) : Parcelable {

    /** The owner state names; the parcel carries the ordinal. */
    enum class Link { IDLE, BLOCKED, FAILED, RUNNING }

    val mcuLinkUp: Boolean
        get() = link == Link.RUNNING

    /** The owner status the launcher shows, rebuilt on the client side. */
    fun ownerStatus(): McuOwner.Status = when (link) {
        Link.IDLE -> McuOwner.Status.Idle
        Link.BLOCKED -> McuOwner.Status.Blocked(reason)
        Link.FAILED -> McuOwner.Status.Failed(reason)
        Link.RUNNING -> McuOwner.Status.Running(acked, frames, badChecksum, skipped)
    }

    override fun describeContents() = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(uid)
        dest.writeInt(link.ordinal)
        dest.writeString(reason)
        dest.writeBoolean(acked)
        dest.writeLong(frames)
        dest.writeLong(badChecksum)
        dest.writeLong(skipped)
    }

    companion object {
        /** The service's side: [status] of its owner, stamped with its [uid]. */
        fun of(uid: Int, status: McuOwner.Status): CarStatus = when (status) {
            is McuOwner.Status.Idle -> CarStatus(uid)
            is McuOwner.Status.Blocked -> CarStatus(uid, Link.BLOCKED, status.reason)
            is McuOwner.Status.Failed -> CarStatus(uid, Link.FAILED, status.reason)
            is McuOwner.Status.Running ->
                CarStatus(uid, Link.RUNNING, "", status.acked, status.frames, status.badChecksum, status.skipped)
        }

        // An unknown ordinal (a newer service) reads as FAILED, never as a live link.
        private fun link(ordinal: Int) = Link.entries.getOrElse(ordinal) { Link.FAILED }

        @JvmField
        val CREATOR = object : Parcelable.Creator<CarStatus> {
            override fun createFromParcel(src: Parcel) = CarStatus(
                uid = src.readInt(),
                link = link(src.readInt()),
                reason = src.readString().orEmpty(),
                acked = src.readBoolean(),
                frames = src.readLong(),
                badChecksum = src.readLong(),
                skipped = src.readLong(),
            )

            override fun newArray(size: Int) = arrayOfNulls<CarStatus>(size)
        }
    }
}
