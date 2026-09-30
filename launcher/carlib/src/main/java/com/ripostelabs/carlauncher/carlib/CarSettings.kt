package com.ripostelabs.carlauncher.carlib

/**
 * Toyota car customisations through the HiWorld box, after stock canbus2's
 * `HiworldToyotaSetConfig` (can-integration/docs/TOYOTA_CUSTOMIZATION.md).
 *
 *     page ── set:   03 6A group key value ──▶ box ──▶ car        one frame per change
 *     page ── query: 03 6A 05 01 62        ──▶ box
 *     page ◀── report: cmd 0x62, field = (p[byte] >> shift) & mask ── box
 *
 * Every Toyota item stock lists (`HiworldToyotaSetConfig.java:33-67`) except the climate panel
 * type, which only picks stock's own climate layout. Items a RAV4 may not have (powered column
 * and seat exit moves, radar settings the cluster owns) are listed anyway: a car that ignores
 * one leaves its report unchanged. Units and language have no report field, so they show no
 * value. Nothing here has been sent to the car yet; a value is only ever shown from a report.
 */
enum class CarSettingGroup(val code: Int) {
    VEHICLE(0x01),
    REMOTE(0x02),
    LIGHTS(0x03),

    /** Not a `6A` group: language is its own `02 9A key value` command (sendType 4). */
    LANGUAGE(0x04),
}

/** Whether a change can lock someone out: [LOCKS] rows ask first, and only while parked. */
enum class CarSettingRisk { NONE, LOCKS }

/** [CarSetting.reportByte] for a setting the 0x62 report does not carry. */
private const val NO_REPORT = -1

/**
 * One setting: its command [group] and [key], the [values] the box takes, and where the 0x62
 * report carries it. [reportByte] is a payload index: stock `bArr[n]` is `p[n - 2]`.
 * [sendValue], when set, is sent whatever value is picked: stock's toggle commands.
 */
enum class CarSetting(
    val group: CarSettingGroup,
    val key: Int,
    val values: IntRange,
    val reportByte: Int,
    val shift: Int,
    val bits: Int,
    val risk: CarSettingRisk,
    val sendValue: Int? = null,
) {
    AUTO_LOCK_SPEED(CarSettingGroup.VEHICLE, 0x01, 0..1, 1, 6, 1, CarSettingRisk.LOCKS),

    /** 0 all doors, 1 driver's door. */
    SMART_DOOR_UNLOCK(CarSettingGroup.VEHICLE, 0x02, 0..1, 1, 5, 1, CarSettingRisk.LOCKS),
    AUTO_UNLOCK_DRIVER_DOOR(CarSettingGroup.VEHICLE, 0x03, 0..1, 1, 4, 1, CarSettingRisk.LOCKS),
    AUTO_UNLOCK_INTO_P(CarSettingGroup.VEHICLE, 0x04, 0..1, 1, 3, 1, CarSettingRisk.LOCKS),
    AUTO_LOCK_OUT_OF_P(CarSettingGroup.VEHICLE, 0x05, 0..1, 1, 2, 1, CarSettingRisk.LOCKS),
    DAYTIME_LIGHTS(CarSettingGroup.VEHICLE, 0x0B, 0..1, 3, 7, 1, CarSettingRisk.NONE),

    /** Hazards flash on remote lock and unlock. */
    LOCK_FLASH(CarSettingGroup.REMOTE, 0x01, 0..1, 2, 7, 1, CarSettingRisk.NONE),

    /** 1: first press unlocks the driver's door, the second all. 0: all on the first press. */
    REMOTE_UNLOCK_TWO_PRESS(CarSettingGroup.REMOTE, 0x04, 0..1, 2, 6, 1, CarSettingRisk.LOCKS),

    /** 0 off, 1 to 7. */
    BUZZER_VOLUME(CarSettingGroup.REMOTE, 0x05, 0..7, 5, 0, 3, CarSettingRisk.NONE),

    /** 5 steps, shown 1 to 5; which end is "brighter" is unknown. */
    LIGHT_SENSOR(CarSettingGroup.LIGHTS, 0x01, 0..4, 3, 0, 3, CarSettingRisk.NONE),

    /** 0 off, 1 7.5 s, 2 15 s, 3 30 s. */
    INTERIOR_LIGHT_OFF(CarSettingGroup.LIGHTS, 0x02, 0..3, 3, 3, 2, CarSettingRisk.NONE),

    /** Box index 0 to 3; on this car off, 30, 60, 90 s is a guess. */
    HEADLIGHT_OFF(CarSettingGroup.LIGHTS, 0x03, 0..3, 3, 5, 2, CarSettingRisk.NONE),

    // RAV4-181: the rest of stock's list, report bits from TOY:644-674.

    /** A/C follows AUTO. */
    CLIMATE_AUTO_LINK(CarSettingGroup.VEHICLE, 0x06, 0..1, 1, 1, 1, CarSettingRisk.NONE),

    /** Recirculation follows AUTO. */
    RECIRC_AUTO_LINK(CarSettingGroup.VEHICLE, 0x07, 0..1, 1, 0, 1, CarSettingRisk.NONE),
    RADAR_DISPLAY(CarSettingGroup.VEHICLE, 0x08, 0..1, 0, 7, 1, CarSettingRisk.NONE),

    /** 1 to 5. */
    RADAR_VOLUME(CarSettingGroup.VEHICLE, 0x09, 1..5, 0, 4, 3, CarSettingRisk.NONE),

    /** 1 or 2 grid squares. Stock sends key 0x0A value 1 to flip it. */
    FRONT_RADAR_RANGE(CarSettingGroup.VEHICLE, 0x0A, 1..2, 0, 2, 2, CarSettingRisk.NONE, sendValue = 1),

    /** 1 or 2 grid squares. Stock sends key 0x0A value 2 to flip it. */
    REAR_RADAR_RANGE(CarSettingGroup.VEHICLE, 0x0A, 1..2, 0, 0, 2, CarSettingRisk.NONE, sendValue = 2),

    /** 0 to 4, shown -2 to +2. */
    LEFT_SEAT_AUTO_TEMP(CarSettingGroup.VEHICLE, 0x0C, 0..4, 4, 5, 3, CarSettingRisk.NONE),
    RIGHT_SEAT_AUTO_TEMP(CarSettingGroup.VEHICLE, 0x0D, 0..4, 4, 2, 3, CarSettingRisk.NONE),

    /** Exhaust gas sensor for auto recirculation, 0 to 6, shown -3 to +3. */
    SMOKE_SENSOR(CarSettingGroup.VEHICLE, 0x0E, 0..6, 2, 1, 3, CarSettingRisk.NONE),

    /** 0 off, 1 tilt, 2 telescopic, 3 both. */
    STEERING_EXIT_MOVE(CarSettingGroup.VEHICLE, 0x0F, 0..3, 4, 0, 2, CarSettingRisk.NONE),

    /** 0 off, 1 partial, 2 full. */
    SEAT_EXIT_MOVE(CarSettingGroup.VEHICLE, 0x10, 0..2, 6, 6, 2, CarSettingRisk.NONE),
    ACC_CUSTOM(CarSettingGroup.VEHICLE, 0x11, 0..1, 6, 3, 1, CarSettingRisk.NONE),

    /** 0 off, 1 when stopped, 2 on. */
    RECOMMENDATIONS(CarSettingGroup.VEHICLE, 0x12, 0..2, 6, 4, 2, CarSettingRisk.NONE),

    /** 0 left-hand drive, 1 right-hand drive. */
    DRIVE_SIDE(CarSettingGroup.VEHICLE, 0x13, 0..1, 6, 2, 1, CarSettingRisk.NONE),

    /** 0 MPG (US), 1 km/L, 2 L/100 km, 3 MPG (UK). */
    FUEL_UNIT(CarSettingGroup.VEHICLE, 0x14, 0..3, NO_REPORT, 0, 0, CarSettingRisk.NONE),

    /** 0 °C, 1 °F. */
    TEMP_UNIT(CarSettingGroup.VEHICLE, 0x15, 0..1, NO_REPORT, 0, 0, CarSettingRisk.NONE),

    /** Smart lock and one-push start. */
    SMART_LOCK(CarSettingGroup.REMOTE, 0x02, 0..1, 2, 5, 1, CarSettingRisk.LOCKS),

    /** Unlock when the key is used twice. */
    KEY_TWICE_UNLOCK(CarSettingGroup.REMOTE, 0x03, 0..1, 2, 4, 1, CarSettingRisk.LOCKS),

    /** Cluster language, stock's codes 1 to 43 (1 English, 5 French, 7 Spanish). */
    LANGUAGE(CarSettingGroup.LANGUAGE, 0x01, 1..43, NO_REPORT, 0, 0, CarSettingRisk.NONE),
    ;

    /** False for a setting the 0x62 report never carries: units and language. */
    val reported: Boolean get() = reportByte != NO_REPORT
}

/** The settings as the last 0x62 report gave them; null for a field the report did not carry. */
data class CarSettingsState(private val values: Map<CarSetting, Int>) {
    operator fun get(setting: CarSetting): Int? = values[setting]
}

object CarSettings {

    /** Box command that writes a setting (`6A`, sendType 1 to 3). */
    private const val CMD_SET = 0x6A

    /** Bytes after the length: command, group, key, value. */
    private const val SET_LEN = 0x03

    /** The report the box answers the query with. */
    const val REPORT_OPCODE = 0x62

    /** `03 6A 05 01 62`: ask the box for its settings report, as the stock page does on open. */
    val QUERY: IntArray = intArrayOf(SET_LEN, CMD_SET, 0x05, 0x01, REPORT_OPCODE)

    /** Box command that sets the cluster language (`getSendToCanByteArray4`). */
    private const val CMD_LANGUAGE = 0x9A

    /** Bytes after the length for language: command, key, value. */
    private const val LANGUAGE_LEN = 0x02

    /** `03 6A group key value`, or `02 9A key value` for language: the box payload for one change. */
    fun setPayload(setting: CarSetting, value: Int): IntArray {
        require(value in setting.values) { "${setting.name} takes ${setting.values}, not $value" }

        val sent = setting.sendValue ?: value
        if (setting.group == CarSettingGroup.LANGUAGE) {
            return intArrayOf(LANGUAGE_LEN, CMD_LANGUAGE, setting.key, sent)
        }
        return intArrayOf(SET_LEN, CMD_SET, setting.group.code, setting.key, sent)
    }

    /** Every field of a 0x62 [payload]; a short report or an out-of-set value leaves it null. */
    fun decode(payload: ByteArray): CarSettingsState {
        val values = buildMap {
            for (setting in CarSetting.entries) {
                val value = field(payload, setting) ?: continue
                put(setting, value)
            }
        }
        return CarSettingsState(values)
    }

    private fun field(payload: ByteArray, setting: CarSetting): Int? {
        if (setting.reportByte !in payload.indices) {
            return null
        }

        val mask = (1 shl setting.bits) - 1
        val value = ((payload[setting.reportByte].toInt() and 0xFF) shr setting.shift) and mask
        return value.takeIf { it in setting.values }
    }
}
