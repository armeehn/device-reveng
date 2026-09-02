package com.szchoiceway.canbus

import android.os.Parcel
import android.os.Parcelable

/**
 * Clean-room mirror of the vendor's `com.szchoiceway.canbus.CarAirState` Parcelable.
 *
 * The CAN app broadcasts `com.choiceway.canbus.carairstruct` with this class as the
 * `com.choiceway.canbus.carairstruct.airstate` extra (canbus2 `CanDataParseBase.java:473-486`).
 * `Bundle.unparcel()` looks the class up by its fully-qualified name and replays the fields in
 * the order the sender wrote them, so BOTH must match the vendor exactly:
 *
 *   sender (vendor)                      receiver (this class)
 *   writeToParcel: bAirOn, bAcOn, ...  ->  readFields:  bAirOn, bAcOn, ...
 *
 * Field names, defaults and parcel order are those of the decompiled
 * `com.szchoiceway.canbus2/sources/com/szchoiceway/canbus/CarAirState.java`
 * (`writeToParcel` / `CarAirState(Parcel)`). The vendor class carries ~170 fields but parcels
 * only these 36; anything else (`bECOOn`, `bRearAirOn`, `byRearFunStrength`, `m_OutSideTemp`,
 * `bWheelHeat`...) never crosses the process boundary and is deliberately absent here.
 *
 * Booleans travel as one byte (1/0), ints as int, temperatures as the vendor's already
 * formatted strings ("22.5℃", "LO", "HI", "" while the A/C is off).
 *
 * UNVERIFIED on-device: the order is taken from the decompile, not from a live unparcel.
 */
class CarAirState internal constructor() : Parcelable {
    internal var bAirOn = false
    internal var bAcOn = false
    internal var bOutCircleOn = false
    internal var bBigAutoOn = false
    internal var bSmallAutoOn = false
    internal var bDualOn = false
    internal var bMaxFrontOn = false
    internal var bRearOn = false
    internal var bFunDirectHead = false
    internal var bFunDirectLevel = false
    internal var bFunDirectFoot = false
    internal var bAcMax = false
    internal var byFunStrength = 0
    internal var m_byLeftTemp: String? = ""
    internal var m_byRighTemp: String? = ""
    internal var m_byTempUnit = 0
    internal var bRearLock = false
    internal var bHuaFenOn = false
    internal var byLeftColdLevel = 0
    internal var byRightColdLevel = 0
    internal var bAQSInCircle = false
    internal var bLeftSeatHotLevel = 0
    internal var bRightSeatHotLevel = 0
    internal var byMaxFunStrengthStall = DEFAULT_MAX_FAN
    internal var bFunStrengthAuto = false
    internal var bSmallAutoHide = false
    internal var bDualHide = false
    internal var bFrontHotOn = false
    internal var bNanoeOn = false
    internal var bFanAuto = false
    internal var bFanDirection = false
    internal var bZone = false
    internal var byLeftKnobsAdjustMode = 0
    internal var byRightKnobsAdjustMode = 0
    internal var bQuickCooling = false
    internal var bQuickHeating = false

    private constructor(parcel: Parcel) : this() {
        readFields(ParcelSource(parcel))
    }

    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        writeFields(ParcelSink(dest))
    }

    /** Vendor `writeToParcel` order. Keep [readFields] in lockstep. */
    internal fun writeFields(out: FieldSink) {
        out.bool(bAirOn)
        out.bool(bAcOn)
        out.bool(bOutCircleOn)
        out.bool(bBigAutoOn)
        out.bool(bSmallAutoOn)
        out.bool(bDualOn)
        out.bool(bMaxFrontOn)
        out.bool(bRearOn)
        out.bool(bFunDirectHead)
        out.bool(bFunDirectLevel)
        out.bool(bFunDirectFoot)
        out.bool(bAcMax)
        out.int(byFunStrength)
        out.str(m_byLeftTemp)
        out.str(m_byRighTemp)
        out.int(m_byTempUnit)
        out.bool(bRearLock)
        out.bool(bHuaFenOn)
        out.int(byLeftColdLevel)
        out.int(byRightColdLevel)
        out.bool(bAQSInCircle)
        out.int(bLeftSeatHotLevel)
        out.int(bRightSeatHotLevel)
        out.int(byMaxFunStrengthStall)
        out.bool(bFunStrengthAuto)
        out.bool(bSmallAutoHide)
        out.bool(bDualHide)
        out.bool(bFrontHotOn)
        out.bool(bNanoeOn)
        out.bool(bFanAuto)
        out.bool(bFanDirection)
        out.bool(bZone)
        out.int(byLeftKnobsAdjustMode)
        out.int(byRightKnobsAdjustMode)
        out.bool(bQuickCooling)
        out.bool(bQuickHeating)
    }

    /** Vendor `CarAirState(Parcel)` order. Keep [writeFields] in lockstep. */
    internal fun readFields(src: FieldSource) {
        bAirOn = src.bool()
        bAcOn = src.bool()
        bOutCircleOn = src.bool()
        bBigAutoOn = src.bool()
        bSmallAutoOn = src.bool()
        bDualOn = src.bool()
        bMaxFrontOn = src.bool()
        bRearOn = src.bool()
        bFunDirectHead = src.bool()
        bFunDirectLevel = src.bool()
        bFunDirectFoot = src.bool()
        bAcMax = src.bool()
        byFunStrength = src.int()
        m_byLeftTemp = src.str()
        m_byRighTemp = src.str()
        m_byTempUnit = src.int()
        bRearLock = src.bool()
        bHuaFenOn = src.bool()
        byLeftColdLevel = src.int()
        byRightColdLevel = src.int()
        bAQSInCircle = src.bool()
        bLeftSeatHotLevel = src.int()
        bRightSeatHotLevel = src.int()
        byMaxFunStrengthStall = src.int()
        bFunStrengthAuto = src.bool()
        bSmallAutoHide = src.bool()
        bDualHide = src.bool()
        bFrontHotOn = src.bool()
        bNanoeOn = src.bool()
        bFanAuto = src.bool()
        bFanDirection = src.bool()
        bZone = src.bool()
        byLeftKnobsAdjustMode = src.int()
        byRightKnobsAdjustMode = src.int()
        bQuickCooling = src.bool()
        bQuickHeating = src.bool()
    }

    /** Where the field bytes go: [Parcel] on the device, a list in unit tests. */
    internal interface FieldSink {
        fun bool(value: Boolean)
        fun int(value: Int)
        fun str(value: String?)
    }

    /** Where the field bytes come from; the mirror image of [FieldSink]. */
    internal interface FieldSource {
        fun bool(): Boolean
        fun int(): Int
        fun str(): String?
    }

    // The vendor encodes a boolean as writeByte(1/0) and reads it back as readByte() != 0.
    private class ParcelSink(private val parcel: Parcel) : FieldSink {
        override fun bool(value: Boolean) = parcel.writeByte(if (value) BYTE_TRUE else BYTE_FALSE)
        override fun int(value: Int) = parcel.writeInt(value)
        override fun str(value: String?) = parcel.writeString(value)
    }

    private class ParcelSource(private val parcel: Parcel) : FieldSource {
        override fun bool(): Boolean = parcel.readByte() != BYTE_FALSE
        override fun int(): Int = parcel.readInt()
        override fun str(): String? = parcel.readString()
    }

    companion object {
        /** Vendor default for `byMaxFunStrengthStall`: a 7-step fan. */
        internal const val DEFAULT_MAX_FAN = 7

        private const val BYTE_TRUE: Byte = 1
        private const val BYTE_FALSE: Byte = 0

        /** Public static `CREATOR` is what `Parcel.readParcelable` finds by reflection. */
        @JvmField
        val CREATOR = object : Parcelable.Creator<CarAirState> {
            override fun createFromParcel(source: Parcel): CarAirState = CarAirState(source)
            override fun newArray(size: Int): Array<CarAirState?> = arrayOfNulls(size)
        }
    }
}
