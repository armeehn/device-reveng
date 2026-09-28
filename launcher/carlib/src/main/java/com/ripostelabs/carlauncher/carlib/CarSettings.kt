package com.ripostelabs.carlauncher.carlib

/**
 * Toyota car customisations through the HiWorld box, after stock canbus2's
 * `HiworldToyotaSetConfig` (can-integration/docs/TOYOTA_CUSTOMIZATION.md).
 *
 *     page ── set:   03 6A group key value ──▶ box ──▶ car        one frame per change
 *     page ── query: 03 6A 05 01 62        ──▶ box
 *     page ◀── report: cmd 0x62, field = (p[byte] >> shift) & mask ── box
 *
 * Only the doc's "likely to work" items are listed. Left out: the steering column and seat exit
 * moves (this car has neither powered), climate linkage (box climate does nothing here), radar
 * (cluster-only), smart key and key-twice unlock (dealer-only), and the unexplained items.
 * Nothing here has been sent to the car yet; a value is only ever shown from a report.
 */
enum class CarSettingGroup(val code: Int) {
    VEHICLE(0x01),
    REMOTE(0x02),
    LIGHTS(0x03),
}

/** Whether a change can lock someone out: [LOCKS] rows ask first, and only while parked. */
enum class CarSettingRisk { NONE, LOCKS }

/**
 * One setting: its command [group] and [key], the [values] the box takes, and where the 0x62
 * report carries it. [reportByte] is a payload index: stock `bArr[n]` is `p[n - 2]`.
 */
enum class CarSetting(
    val group: CarSettingGroup,
    val key: Int,
    val values: IntRange,
    val reportByte: Int,
    val shift: Int,
    val bits: Int,
    val risk: CarSettingRisk,
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

    /** `03 6A group key value`, the box payload for one change. */
    fun setPayload(setting: CarSetting, value: Int): IntArray {
        require(value in setting.values) { "${setting.name} takes ${setting.values}, not $value" }
        return intArrayOf(SET_LEN, CMD_SET, setting.group.code, setting.key, value)
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
