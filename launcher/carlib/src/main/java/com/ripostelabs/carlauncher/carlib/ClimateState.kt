package com.ripostelabs.carlauncher.carlib

import com.szchoiceway.canbus.CarAirState

/**
 * ClimateState — a display-only snapshot of the car's HVAC (CAR_API §5).
 *
 * Source: the `com.choiceway.canbus.carairstruct` broadcast, whose extra is the vendor
 * Parcelable now mirrored by [CarAirState]. The former AIDL `getAirData()` path is gone: the
 * gateway implements it as `return null` (`EC/EventService.java:1369-1371`), so it could never
 * deliver a frame.
 *
 * Temperatures are the vendor's own formatted strings ("22.5℃", "LO", "HI"), blank while the
 * A/C is off (`HiworldCanParseToyota.java:989-1000`). ECO, rear-zone fan and outside temperature
 * are NOT in the parcel (see [CarAirState]); outside temperature has its own broadcast,
 * [CarEvents.outsideTemp].
 *
 * UNVERIFIED on-device: field semantics follow the RAV4 decoder
 * (`HiworldCanParseToyota.java:213-300`), not a live capture.
 */
data class ClimateState(
    val valid: Boolean = false,
    /** Whole HVAC on/off (`bAirOn`). Temperatures are blank when false. */
    val powerOn: Boolean = false,
    val acOn: Boolean = false,
    val acMax: Boolean = false,
    /** `bSmallAutoOn`, the AUTO the RAV4 decoder sets from frame 0x31 byte 2 bit 3. */
    val autoOn: Boolean = false,
    val dualOn: Boolean = false,
    /** `bOutCircleOn`: outside air in. False means recirculating. */
    val outsideAir: Boolean = false,
    /** `bMaxFrontOn`: windscreen max defrost. */
    val frontDefrost: Boolean = false,
    /** `bRearOn`: rear window defrost. */
    val rearDefrost: Boolean = false,
    val rearLock: Boolean = false,
    val modeHead: Boolean = false,
    val modeLevel: Boolean = false,
    val modeFoot: Boolean = false,
    val fanLevel: Int = 0,
    val fanMax: Int = CarAirState.DEFAULT_MAX_FAN,
    val leftTemp: String = "",
    val rightTemp: String = "",
    val tempUnit: TempUnit = TempUnit.CELSIUS,
    /** Seat heat/cool steps, 0..3 (`HiworldCanParseToyota.java` frame 0x31 bytes 4-5). */
    val leftSeatHeat: Int = 0,
    val rightSeatHeat: Int = 0,
    val leftSeatCool: Int = 0,
    val rightSeatCool: Int = 0,
) {
    enum class TempUnit { CELSIUS, FAHRENHEIT }

    /** Set-temp for the card: "Off" with the power off, "--" when the car sent nothing. */
    fun leftTempLabel(): String = tempLabel(leftTemp)
    fun rightTempLabel(): String = tempLabel(rightTemp)

    private fun tempLabel(temp: String): String {
        if (!powerOn) {
            return OFF_LABEL
        }
        if (temp.isBlank()) {
            return NO_VALUE_LABEL
        }
        return temp
    }

    companion object {
        private const val OFF_LABEL = "Off"
        private const val NO_VALUE_LABEL = "--"

        /** `m_byTempUnit`: 0 = ℃, 1 = ℉ (`HiworldCanParseToyota.java:223`). */
        private const val UNIT_FAHRENHEIT = 1

        /** Map one received [CarAirState] onto the launcher's view of it. */
        fun from(air: CarAirState): ClimateState = ClimateState(
            valid = true,
            powerOn = air.bAirOn,
            acOn = air.bAcOn,
            acMax = air.bAcMax,
            autoOn = air.bSmallAutoOn || air.bBigAutoOn,
            dualOn = air.bDualOn,
            outsideAir = air.bOutCircleOn,
            frontDefrost = air.bMaxFrontOn,
            rearDefrost = air.bRearOn,
            rearLock = air.bRearLock,
            modeHead = air.bFunDirectHead,
            modeLevel = air.bFunDirectLevel,
            modeFoot = air.bFunDirectFoot,
            fanLevel = air.byFunStrength,
            fanMax = air.byMaxFunStrengthStall,
            leftTemp = air.m_byLeftTemp.orEmpty(),
            rightTemp = air.m_byRighTemp.orEmpty(),
            tempUnit = if (air.m_byTempUnit == UNIT_FAHRENHEIT) TempUnit.FAHRENHEIT else TempUnit.CELSIUS,
            leftSeatHeat = air.bLeftSeatHotLevel,
            rightSeatHeat = air.bRightSeatHotLevel,
            leftSeatCool = air.byLeftColdLevel,
            rightSeatCool = air.byRightColdLevel,
        )
    }
}
