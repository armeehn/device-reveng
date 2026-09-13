package com.ripostelabs.carlauncher.carlib

import android.content.Context

/**
 * VendorBroadcastReemitter — the suite's view of eventcenter, kept alive on Riposte OS 0.2.
 *
 * ── Where this sits ─────────────────────────────────────────────────────────────────────────────
 *
 *     McuOwner ──▶ McuOwner.FanOut ──┬─▶ CarEvents.ownerListener   (the launcher's own flows)
 *                                    └─▶ VendorBroadcastReemitter ─▶ sendBroadcast(vendor action)
 *                                                                          │
 *                                              26 suite apps, unchanged ◀──┘
 *
 * The suite (rav4-apps) still registers for `com.choiceway.eventcenter.*` actions. On 0.2 there
 * is no eventcenter to send them, so this listener replays them from the owner's decoded events
 * with the vendor's exact action strings and extras (`EventService.java`, cited per method).
 *
 * ── Edges, as the vendor did them ───────────────────────────────────────────────────────────────
 * Reverse, ACC, headlamps and brake fire on CHANGE only, from the vendor's own boot baseline
 * ([BOOT_LINES]: brake connected, everything else off, EventService.java:231-237). Volume and
 * mute fire on every report (`notifyMainVolChange` / `notifyMuteStateChange`).
 *
 * ── What is deliberately absent ─────────────────────────────────────────────────────────────────
 * SysVar rows (`Sys_LAMP_STAUS_CHECK`, `Sys_CurBreakSate`): the provider is eventcenter's and is
 * gone with it; the suite's readers already treat "no provider" as "no gate". The protected
 * `ACTION_BACKCAR_*` pair: a signature permission we cannot hold. Keys, CAN digests, radio, BT:
 * nothing in the suite listens for them.
 *
 * Intent construction is pure ([step], [mailVol]) so a JVM test asserts action + extras without
 * Android; [IntentSpec.broadcast] is the only framework touch.
 */
class VendorBroadcastReemitter(private val send: (IntentSpec) -> Unit) : McuOwner.Listener {

    constructor(context: Context) : this({ it.broadcast(context) })

    /** The four SYS_EVENT lines the vendor broadcast on change. */
    data class Lines(val reverse: Boolean, val acc: Boolean, val illumination: Boolean, val brake: Boolean)

    private var lines = BOOT_LINES

    private var level = BOOT_LEVEL

    private var muted = BOOT_MUTED

    override fun onSysEvent(event: McuOwnerProtocol.SysEvent) {
        val next = Lines(event.reverse, event.accLine, event.illumination, event.brake)
        val out = step(lines, next)
        lines = next
        out.forEach(send)
    }

    override fun onMainVolume(volume: McuOwnerProtocol.MainVolume) {
        level = volume.level
        send(mailVol(level, muted, showWindow = !volume.silent))
    }

    override fun onMute(mute: McuOwnerProtocol.Mute) {
        muted = mute.muted
        send(mailVol(level, muted, showWindow = !mute.silent))
    }

    companion object {
        /** EventService.java:231-237 field initialisers: only the brake line starts "connected". */
        val BOOT_LINES = Lines(reverse = false, acc = false, illumination = false, brake = true)

        private const val BOOT_LEVEL = 0
        private const val BOOT_MUTED = false

        /** `(mMuteState ? 128 : 0) | mCurVol` (EventService.java:3106). */
        private const val MUTE_BIT = 0x80

        /** Broadcasts the vendor sent when [prev] became [next]; empty when nothing moved. */
        fun step(prev: Lines, next: Lines): List<IntentSpec> {
            val out = mutableListOf<IntentSpec>()

            // HANDLER_BACKCAR_START / END (:664, :709).
            if (prev.reverse != next.reverse) {
                val action = if (next.reverse) CarEvents.MCU_MSG_BACKCAR_START else CarEvents.MCU_MSG_BACKCAR_END
                out += IntentSpec(action)
            }

            // sendAccOpenCloseStatusBroadcast (:3404): int extra 1 = on, 0 = off.
            if (prev.acc != next.acc) {
                val status = if (next.acc) CarEvents.ACC_STATUS_ON else CarEvents.ACC_STATUS_SLEEP
                out += IntentSpec(
                    action = CarEvents.ACTION_ACC_OPEN_CLOSE_EVT,
                    ints = mapOf(CarEvents.EXTRA_ACC_STATUS to status),
                )
            }

            // HANDLER_LAMP_CONNECTION_EVT (:802): no extras, the value lived in a SysVar.
            if (prev.illumination != next.illumination) {
                out += IntentSpec(CarEvents.LAMP_STATUS)
            }

            // HANDLER_BREAK_EVENT (:547): no extras, same shape.
            if (prev.brake != next.brake) {
                out += IntentSpec(CarEvents.MCU_MSG_BRAKE_EVT)
            }

            return out
        }

        /** notifyMainVolChange / notifyMuteStateChange (:3105-3122): raw byte = level | mute bit 7. */
        fun mailVol(level: Int, muted: Boolean, showWindow: Boolean): IntentSpec = IntentSpec(
            action = CarEvents.MCU_MSG_MAIL_VOL,
            ints = mapOf(CarEvents.EXTRA_MAIL_VOL_VAL to ((if (muted) MUTE_BIT else 0) or level)),
            booleans = mapOf(CarEvents.EXTRA_SHOW_VOL_WND to showWindow),
        )
    }
}
