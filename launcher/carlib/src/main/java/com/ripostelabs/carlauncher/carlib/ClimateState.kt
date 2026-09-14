package com.ripostelabs.carlauncher.carlib

import com.szchoiceway.canbus.CarAirState
import java.util.Locale

/**
 * ClimateState — a display-only snapshot of the car's HVAC (CAR_API §5).
 *
 * Source: the `com.choiceway.canbus.carairstruct` broadcast, whose extra is the vendor
 * Parcelable now mirrored by [CarAirState]. The former AIDL `getAirData()` path is gone: the
 * gateway implements it as `return null` (`EC/EventService.java:1369-1371`), so it could never
 * deliver a frame.
 *
 * On Riposte OS 0.2 there is no canbus2 to broadcast, so the same state comes straight from the
 * box's 0x31 frame ([CanSignal.Climate]) through [from]; the labels are built the way
 * `HiworldCanParseToyota.setCanAirTempInfoVertical` builds them, so the card reads the same.
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

        /** The vendor's own labels for the dial end stops (`lbl_lo` / `lbl_hi`). */
        private const val LO_LABEL = "LO"
        private const val HI_LABEL = "HI"
        private const val CELSIUS_SUFFIX = "℃"
        private const val FAHRENHEIT_SUFFIX = "℉"

        /**
         * Vent direction byte (0x31 p[4]) → the three direction flags, as
         * `OnHandleCanAirCmdVertical` sets them (`HiworldCanParseToyota.java:255-285`):
         * 3 foot, 5 level+foot, 6 level, 12 head+foot, 13 head+level, 14 all three.
         */
        private const val VENT_FOOT = 3
        private const val VENT_LEVEL_FOOT = 5
        private const val VENT_LEVEL = 6
        private const val VENT_HEAD_FOOT = 12
        private const val VENT_HEAD_LEVEL = 13
        private const val VENT_ALL = 14
        private val VENT_HEAD = setOf(VENT_HEAD_FOOT, VENT_HEAD_LEVEL, VENT_ALL)
        private val VENT_LEVEL_SET = setOf(VENT_LEVEL_FOOT, VENT_LEVEL, VENT_HEAD_LEVEL, VENT_ALL)
        private val VENT_FOOT_SET = setOf(VENT_FOOT, VENT_LEVEL_FOOT, VENT_HEAD_FOOT, VENT_ALL)

        /**
         * Map one decoded 0x31 frame onto the same view, for the owner path (no canbus2).
         * Temperatures are formatted like the vendor's `setCanAirTempInfoVertical`
         * (`HiworldCanParseToyota.java:990-1000`): "21.5℃", or the whole-degree "70℉", LO/HI
         * for the end stops, and blank while the HVAC is off.
         */
        fun from(signal: CanSignal.Climate): ClimateState = ClimateState(
            valid = true,
            powerOn = signal.on,
            acOn = signal.acOn,
            acMax = signal.acMax,
            autoOn = signal.auto,
            dualOn = signal.dual,
            outsideAir = !signal.recirculate,
            frontDefrost = signal.maxFront,
            rearDefrost = signal.rearDefog,
            modeHead = signal.ventDirectionRaw in VENT_HEAD,
            modeLevel = signal.ventDirectionRaw in VENT_LEVEL_SET,
            modeFoot = signal.ventDirectionRaw in VENT_FOOT_SET,
            fanLevel = signal.fanStep,
            leftTemp = tempText(signal.on, signal.leftTempC, signal.leftTempLimit, signal.tempUnitCelsius),
            rightTemp = tempText(signal.on, signal.rightTempC, signal.rightTempLimit, signal.tempUnitCelsius),
            tempUnit = if (signal.tempUnitCelsius) TempUnit.CELSIUS else TempUnit.FAHRENHEIT,
            leftSeatHeat = signal.seatHeatLeft,
            rightSeatHeat = signal.seatHeatRight,
            leftSeatCool = signal.seatCoolLeft,
            rightSeatCool = signal.seatCoolRight,
        )

        private fun tempText(
            on: Boolean,
            tempC: Double?,
            limit: CanSignal.Climate.TempLimit?,
            celsius: Boolean,
        ): String {
            if (!on) {
                return ""
            }
            when (limit) {
                CanSignal.Climate.TempLimit.LO -> return LO_LABEL
                CanSignal.Climate.TempLimit.HI -> return HI_LABEL
                null -> {}
            }
            if (tempC == null) {
                return ""
            }
            if (celsius) {
                return String.format(Locale.ROOT, "%.1f", tempC) + CELSIUS_SUFFIX
            }
            // The vendor shows the raw byte halved as a whole number; raw = tempC × 2.
            return "${tempC.toInt()}$FAHRENHEIT_SUFFIX"
        }

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
