package com.ripostelabs.carlauncher.carlib

/**
 * Bluetooth status as the VENDOR's bt module reports it.
 *
 * The head unit's phone Bluetooth runs through `com.szchoiceway.btsuite` (system uid), so the
 * Android `BluetoothManager` may see nothing while a phone is connected. btsuite publishes its
 * state as unprotected `com.szchoiceway.btsuite.HBCP_EVT_*` broadcasts, decoded here by
 * [VendorBtDecode] under the contract recovered from the decompile (see that KDoc).
 *
 * Every field but [lastEventMs] stays null until the event that defines it has arrived;
 * [lastEventMs] is 0 until the first HBCP broadcast of any shape. A consumer must treat
 * null / stale as "no vendor signal" and keep its own source.
 */
data class VendorBtState(
    /** Vendor BT power on/off (`HBCP_EVT_BT_POWER_STATUS`), or null when never received. */
    val powered: Boolean? = null,
    /** Raw HFP state 0..6 (`HBCP_EVT_HSHF_STATUS`), see [VendorBtDecode.HSHF_CONNECTED]. */
    val hshf: Int? = null,
    /** A phone is connected: HSHF >= 3, or a non-blank connected-device name. */
    val connected: Boolean? = null,
    /** A call is outgoing, incoming or active: HSHF > 3. */
    val inCall: Boolean? = null,
    /** Name of the connected phone (`HBCP_EVT_CUR_CONNECTED_DEVICE_NAME`). Blank = none. */
    val deviceName: String? = null,
    /** A2DP playing (`HBCP_EVT_AV_STATUS` 4) vs paused (3). */
    val avPlaying: Boolean? = null,
    /** Track title carried by `HBCP_EVT_AV_STATUS`. */
    val avTitle: String? = null,
    /** Arrival time of the last HBCP_EVT_* broadcast of ANY shape; 0 = none this session. */
    val lastEventMs: Long = 0L,
)

/**
 * Pure decoder for the `HBCP_EVT_*` payloads.
 *
 * Contract (`btsuite/BTUtils.java:374-383`, `sendMessage`): every broadcast carries the SAME
 * two extras, int [EXTRA_INT] and String [EXTRA_STR]; the action says what they mean.
 *
 * ```
 *  action (prefix com.szchoiceway.btsuite.)   DATA_INT              DATA_STR
 *  HBCP_EVT_BT_POWER_STATUS                   1 on / 0 off          ""
 *  HBCP_EVT_HSHF_STATUS / _HSHF_GET_STATUS    HFP state 0..6        -
 *  HBCP_EVT_CUR_CONNECTED_DEVICE_NAME         0                     phone name
 *  HBCP_EVT_AV_STATUS                         4 playing / 3 paused  track title
 *  HBCP_EVT_CONTACT_NUM / _CONTACT_NAME       0                     caller number / name
 *  HBCP_EVT_SPEAKING_TIME                     int[]{min, sec}       -   (NOT an int: ignored)
 * ```
 *
 * HFP states (`BTUtils.java:115-121`): 0 initialising, 1 ready (no phone), 2 connecting,
 * 3 connected idle, 4 outgoing, 5 incoming, 6 active call. btsuite itself treats `> 3` as a
 * call in progress (`BTService.java:735`). Phone battery and signal strength are NOT on this
 * broadcast surface; nothing here pretends otherwise.
 *
 * An event outside the table (or with a malformed extra) still stamps
 * [VendorBtState.lastEventMs]: the channel is alive even when the reading is unusable.
 */
internal object VendorBtDecode {

    /** The two fixed extras (`BTUtils.sendMessage`). */
    const val EXTRA_INT = "com.szchoiceway.btsuite.DATA_INT"
    const val EXTRA_STR = "com.szchoiceway.btsuite.DATA_STR"

    /** Suffixes after [CarEvents.HBCP_ACTION_PREFIX]. */
    const val EVT_POWER = "BT_POWER_STATUS"
    const val EVT_HSHF = "HSHF_STATUS"
    const val EVT_HSHF_GET = "HSHF_GET_STATUS"
    const val EVT_DEVICE_NAME = "CUR_CONNECTED_DEVICE_NAME"
    const val EVT_AV_STATUS = "AV_STATUS"
    const val EVT_CONTACT_NUM = "CONTACT_NUM"
    const val EVT_CONTACT_NAME = "CONTACT_NAME"
    const val EVT_SPEAKING_TIME = "SPEAKING_TIME"

    /** `HBCP_STATUS_HSHF_*` (`BTUtils.java:115-121`). */
    const val HSHF_INITIALISING = 0
    const val HSHF_READY = 1
    const val HSHF_CONNECTING = 2
    const val HSHF_CONNECTED = 3
    const val HSHF_OUTGOING_CALL = 4
    const val HSHF_INCOMING_CALL = 5
    const val HSHF_ACTIVE_CALL = 6

    /** `HBCP_STATUS_AV_PLAYING` / `AV_PAUSE` (`BTUtils.java:110,112`). */
    const val AV_PLAYING = 4
    const val AV_PAUSED = 3

    private const val POWER_ON = 1
    private const val POWER_OFF = 0

    /** Fold one HBCP broadcast into [prev]. [extras] is the raw bundle snapshot. */
    fun apply(
        prev: VendorBtState,
        action: String,
        extras: Map<String, Any?>,
        atMs: Long,
    ): VendorBtState {
        val stamped = prev.copy(lastEventMs = atMs)
        val int = (extras[EXTRA_INT] as? Number)?.toInt()
        val str = extras[EXTRA_STR] as? String

        return when (action.removePrefix(CarEvents.HBCP_ACTION_PREFIX)) {
            EVT_POWER -> applyPower(stamped, int)
            EVT_HSHF, EVT_HSHF_GET -> applyHshf(stamped, int)
            EVT_DEVICE_NAME -> applyDeviceName(stamped, str)
            EVT_AV_STATUS -> applyAv(stamped, int, str)
            else -> stamped
        }
    }

    private fun applyPower(state: VendorBtState, int: Int?): VendorBtState = when (int) {
        POWER_ON -> state.copy(powered = true)
        // Power off: nothing can stay connected or in a call.
        POWER_OFF -> state.copy(powered = false, connected = false, inCall = false)
        else -> state
    }

    private fun applyHshf(state: VendorBtState, int: Int?): VendorBtState {
        if (int == null || int !in HSHF_INITIALISING..HSHF_ACTIVE_CALL) {
            return state
        }
        return state.copy(
            hshf = int,
            connected = int >= HSHF_CONNECTED,
            inCall = int > HSHF_CONNECTED,
        )
    }

    /**
     * A device name only ever RAISES `connected`: btsuite re-sends the stored name on request
     * (`BTService.java:1536-1538`), and an empty one is not evidence of a disconnect — the
     * HSHF state is.
     */
    private fun applyDeviceName(state: VendorBtState, str: String?): VendorBtState {
        val name = str?.takeIf { it.isNotBlank() } ?: return state.copy(deviceName = str)
        return state.copy(deviceName = name, connected = true)
    }

    private fun applyAv(state: VendorBtState, int: Int?, str: String?): VendorBtState = when (int) {
        AV_PLAYING -> state.copy(avPlaying = true, avTitle = str)
        AV_PAUSED -> state.copy(avPlaying = false, avTitle = str)
        else -> state
    }
}
