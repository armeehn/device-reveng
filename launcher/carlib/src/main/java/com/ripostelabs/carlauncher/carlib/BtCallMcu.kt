package com.ripostelabs.carlauncher.carlib

import android.util.Log

/**
 * BtCallMcu — what the MCU hears about a phone call on Riposte OS 0.2.
 *
 * On the vendor slot the module's HFP line reaches the MCU through two apps. btsuite broadcasts
 * `HBCP_EVT_HSHF_STATUS`; eventcenter's EvtModel turns it into `sendBTState(n)` = `0B n`
 * (EvtModel.java:339-347, EventService.java:4336-4342). Before the answer / hang-up command
 * btsuite asks eventcenter for a short amp mute, `4C n` (BTService.java:1706-1711 ->
 * ACTION_MCU_CMD_EVENT -> EvtModel.java:361-372 -> sendCmdData): 10 before answer, 20 then a
 * 300 ms sleep before hang up (EventHandle.java:45-53). The `0B` is what tells the amp a call
 * is up (and over again on <= 3); the `4C` hides the switch click.
 *
 * ```
 *  BtCarKit ─ hfp() ─▶ onHfp ──▶ 0B n           (on change; held while a CarPlay call is up)
 *           ─ answer ─▶ beforeAnswer ─▶ 4C 0A ─▶ acceptCall
 *           ─ hangUp ─▶ beforeHangUp ─▶ 4C 14 ─ 300 ms ─▶ rejectCall / terminateCall
 * ```
 *
 * eventcenter also sets `sys.zxw.bt.call` and lifts standby / black screen on >= 4
 * (EvtModel.java:348-351, EventService.java:4351-4355): the power subsystem's job, not this.
 * UNVERIFIED on the car: whether the amp needs the `0B` at all beside our HF client's SCO, and
 * the mute length. Every send is logged so the first car session can read it back.
 */
class BtCallMcu(
    private val mcu: Mcu,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {

    interface Mcu {
        fun send(frame: ByteArray)
    }

    /** The last HFP code the MCU got; null until the first send. */
    private var lastSent: Int? = null

    /**
     * The HFP state as [BtCarKitMap.hfp] numbers it. A repeat sends nothing; a CarPlay call
     * holds the frame (`getCarPlayCallState() == 1`, EventService.java:4339) and the held
     * state goes out once the gate lifts.
     */
    fun onHfp(state: HfpState, carPlayCall: Boolean) {
        if (carPlayCall || state.code == lastSent) {
            return
        }
        lastSent = state.code
        mcu.send(McuOwnerProtocol.btState(state.code))
        Log.i(TAG, "sent 0B ${state.code} ($state)")
    }

    /** `onSendMuteToMcu(10)` then `answer()` (EventHandle.java:52-53). */
    fun beforeAnswer() {
        mcu.send(McuOwnerProtocol.btMute(McuOwnerProtocol.BT_MUTE_ANSWER))
    }

    /** `onSendMuteToMcu(20)`, `Thread.sleep(300L)`, then `hungup()` (EventHandle.java:45-51). */
    fun beforeHangUp() {
        mcu.send(McuOwnerProtocol.btMute(McuOwnerProtocol.BT_MUTE_HANG_UP))
        sleep(HANG_UP_SETTLE_MS)
    }

    companion object {
        private const val TAG = "BtCallMcu"

        /** The vendor's sleep between the mute and the hang-up command (EventHandle.java:47). */
        const val HANG_UP_SETTLE_MS = 300L

        fun forOwner(owner: McuOwner): BtCallMcu = BtCallMcu(object : Mcu {
            override fun send(frame: ByteArray) = owner.send(frame)
        })
    }
}
