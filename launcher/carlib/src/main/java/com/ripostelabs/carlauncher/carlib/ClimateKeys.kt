package com.ripostelabs.carlauncher.carlib

/**
 * ClimateKeys — the HVAC key frames the port owner writes on Riposte OS 0.2.
 *
 * Stock route, transcribed from the canbus2 decompile:
 *
 *   button ──▶ HiworldCanParseToyota.verticalScreen (:1784-1941)  `02 3D code 01`
 *          ──▶ CanDataParseBase.sendKeyNeedActionUP (:2186-2230)  send, sleep 100 ms,
 *                                                                  action byte := 0, send again
 *          ──▶ SendUtil.SendCmdLstToCanbus5AA5Header (:60-85)     `5A A5 … CK`, CK = sum - 1
 *          ──▶ SendUtil.sendDataToCanbus (:40-58)                 `0D 08` in front, broadcast
 *          ──▶ eventcenter port owner                             outer MCU frame, /dev/ttyHS1
 *
 * [McuCommand.framed] builds everything up to the broadcast; [McuSerial.encode] is the port
 * owner's envelope, `0D` being the outer opcode and `08 5A A5 …` its payload, as canbus2's
 * frames looked on the 0.1 strace. A press is two frames, [ClimateKeyPress.down] then
 * [ClimateKeyPress.up] after [RELEASE_GAP_MS].
 *
 * UNVERIFIED ON THE CAR: the bytes are the vendor's, but whether the RAV4's HVAC honours them
 * through this CAN box is still open. Log every send until a car session settles it.
 */
object ClimateKeys {

    /** `Thread.sleep(100L)` between the press and the release (CanDataParseBase.java:2222). */
    const val RELEASE_GAP_MS = 100L

    /** The box's command byte for an HVAC key; the reply state comes back as 0x31. */
    private const val BOX_CMD_AIR_KEY = 0x3D

    /** Length byte the vendor puts first: cmd + key + action. */
    private const val AIR_KEY_LEN = 0x02

    private const val ACTION_PRESS = 0x01
    private const val ACTION_RELEASE = 0x00

    /**
     * `verticalScreen`'s key-value → box-code table, Toyota rows only. AC MAX (key 9) has no
     * row, so the vendor leaves code 0 and sends nothing (:1935); it is null here for the
     * same reason.
     */
    private val BOX_CODE: Map<ClimateButton, Int> = mapOf(
        ClimateButton.POWER to 0x01,            // case 0  (:1807)
        ClimateButton.AC to 0x02,               // case 8  (:1831)
        ClimateButton.SYNC to 0x03,             // i == 66 (:1799)
        ClimateButton.AUTO to 0x04,             // case 7  (:1828)
        ClimateButton.FRONT_DEFROST to 0x05,    // case 15 (:1836)
        ClimateButton.REAR_DEFROST to 0x06,     // case 16 (:1839)
        ClimateButton.RECIRCULATE to 0x07,      // i == 12 (:1789)
        ClimateButton.FAN_UP to 0x0B,           // case 1  (:1810)
        ClimateButton.FAN_DOWN to 0x0C,         // case 2  (:1813)
        ClimateButton.LEFT_TEMP_UP to 0x0D,     // case 3  (:1816)
        ClimateButton.LEFT_TEMP_DOWN to 0x0E,   // case 4  (:1819)
        ClimateButton.RIGHT_TEMP_UP to 0x0F,    // case 5  (:1822)
        ClimateButton.RIGHT_TEMP_DOWN to 0x10,  // case 6  (:1825)
        ClimateButton.LEFT_SEAT_HEAT to 0x11,   // case 18 (:1845)
        ClimateButton.RIGHT_SEAT_HEAT to 0x12,  // case 20 (:1851)
        ClimateButton.MODE to 0x15,             // case 21 (:1854)
        ClimateButton.LEFT_SEAT_COOL to 0x17,   // case 17 (:1842)
        ClimateButton.RIGHT_SEAT_COOL to 0x18,  // case 19 (:1848)
        ClimateButton.REAR_LOCK to 0x22,        // i == 49 (:1795)
        ClimateButton.ECO to 0x23,              // i == 40 (:1791)
        ClimateButton.DUAL to 0x29,             // i == 10 (:1787)
    )

    /** The box code the vendor sends for [button], or null when its table has no row. */
    fun boxCode(button: ClimateButton): Int? = BOX_CODE[button]

    /** Both frames of one press, or null when the vendor would send nothing. */
    fun press(button: ClimateButton): ClimateKeyPress? {
        val code = boxCode(button) ?: return null

        return ClimateKeyPress(
            down = frame(code, ACTION_PRESS),
            up = frame(code, ACTION_RELEASE),
        )
    }

    /** `0D 08 5A A5 02 3D code action CK`, wrapped for the owner's port. */
    private fun frame(code: Int, action: Int): ByteArray {
        val routed = McuCommand.framed(intArrayOf(AIR_KEY_LEN, BOX_CMD_AIR_KEY, code, action))
        return McuSerial.encode(routed[0].toInt() and BYTE_MASK, routed.copyOfRange(1, routed.size))
    }

    private const val BYTE_MASK = 0xFF
}

/** One HVAC key press on the wire: [down] first, [up] after [ClimateKeys.RELEASE_GAP_MS]. */
class ClimateKeyPress(val down: ByteArray, val up: ByteArray)
