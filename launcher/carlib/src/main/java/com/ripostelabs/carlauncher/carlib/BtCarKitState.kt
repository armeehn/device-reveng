package com.ripostelabs.carlauncher.carlib

/**
 * BtCarKitState — the pure half of [BtCarKit]: what the stock stack's car-kit proxies report,
 * and how that reads as the [VendorBtState] the launcher already shows.
 *
 * ```
 *  BluetoothHeadsetClient ─┐                                   ┌─▶ CarEvents.vendorBt
 *  BluetoothA2dpSink ──────┼─▶ BtCarKitSnapshot ─▶ BtCarKitMap ┤     (Phone screen, chips,
 *  BluetoothAvrcpController┘        (this file)                └─▶ BtCarKitReading (Doctor)
 * ```
 *
 * Nothing here touches the framework, so the tests pin the table directly. Call states and
 * profile ids are the AOSP values (android-14.0.0_r1 `BluetoothHeadsetClientCall.java:41-70`,
 * `BluetoothProfile.java:154,162,177`); the HFP vocabulary on the far side is btsuite's
 * ([HfpState]), kept so one Phone screen serves both slots.
 */
data class BtCarKitSnapshot(
    /** `BluetoothAdapter.isEnabled`. */
    val adapterOn: Boolean = false,
    /** Profile ids whose proxy answered `onServiceConnected` ([BtCarKit.PROFILES]). */
    val bound: Set<Int> = emptySet(),
    /** The bind window ([BtCarKit.BIND_SETTLE_MS]) has elapsed: an unbound profile is OFF. */
    val settled: Boolean = false,
    /** Name of the phone on the HF client, else on the sink; null = none connected. */
    val phoneName: String? = null,
    val hfConnected: Boolean = false,
    /** The HF link's last `CONNECTION_STATE_CHANGED` extra ([HfLink]); CONNECTING before a device is on it. */
    val hfLink: Int = HfLink.DISCONNECTED,
    val sinkConnected: Boolean = false,
    val avrcpConnected: Boolean = false,
    /** Calls the HF client lists (`getCurrentCalls`), terminated ones included. */
    val calls: List<HfCall> = emptyList(),
    /** `BluetoothA2dpSink.isAudioPlaying` on the connected phone. */
    val audioPlaying: Boolean = false,
    /** When the first ACTIVE call of the current call was seen, for the speaking timer. */
    val activeSinceMs: Long? = null,
)

/** One call as the HF client reports it (`BluetoothHeadsetClientCall`). */
data class HfCall(
    val state: Int,
    val number: String? = null,
) {
    val inProgress: Boolean get() = state != HfCallState.TERMINATED
}

/** `BluetoothHeadsetClientCall.CALL_STATE_*` (android-14.0.0_r1, lines 41-70). */
object HfCallState {
    const val ACTIVE = 0
    const val HELD = 1
    const val DIALING = 2
    const val ALERTING = 3
    const val INCOMING = 4
    const val WAITING = 5
    const val HELD_BY_RESPONSE_AND_HOLD = 6
    const val TERMINATED = 7
}

/** `BluetoothProfile.STATE_*` (android-14.0.0_r1 `BluetoothProfile.java:270-285`). */
object HfLink {
    const val DISCONNECTED = 0
    const val CONNECTING = 1
    const val CONNECTED = 2
    const val DISCONNECTING = 3
}

/** One Doctor row: profiles on/off and whether a phone is on them. */
data class BtCarKitReading(
    val ok: Boolean,
    val title: String,
    val detail: String,
)

object BtCarKitMap {

    const val TITLE = "Bluetooth car-kit"
    const val VENDOR_DETAIL = "Vendor btsuite carries the phone on this slot (0.1)."
    private const val WAITING_DETAIL = "Waiting for the Bluetooth stack."
    private const val ADAPTER_OFF_DETAIL = "Bluetooth is off."
    private const val NO_PHONE = "no phone connected"
    private const val MS_PER_SECOND = 1000L

    /** Profile id -> the name the Doctor prints. */
    private val PROFILE_NAMES = linkedMapOf(
        BtCarKit.PROFILE_HEADSET_CLIENT to "HFP client",
        BtCarKit.PROFILE_A2DP_SINK to "A2DP sink",
        BtCarKit.PROFILE_AVRCP_CONTROLLER to "AVRCP controller",
        BtCarKit.PROFILE_PBAP_CLIENT to "PBAP client",
    )

    /**
     * The car-kit profiles are on when every proxy bound; null until the bind window
     * elapsed, since `getProfileProxy` never says "unsupported", it just never calls back.
     */
    fun profilesOn(s: BtCarKitSnapshot): Boolean? {
        if (s.bound.containsAll(BtCarKit.PROFILES)) {
            return true
        }
        if (!s.settled) {
            return null
        }
        return false
    }

    /** The call that decides the HFP state: a ringing one first, then dialling, then active. */
    fun leadCall(calls: List<HfCall>): HfCall? {
        val live = calls.filter { it.inProgress }
        return live.firstOrNull { it.state == HfCallState.INCOMING || it.state == HfCallState.WAITING }
            ?: live.firstOrNull { it.state == HfCallState.DIALING || it.state == HfCallState.ALERTING }
            ?: live.firstOrNull()
    }

    /** btsuite's HFP number for the snapshot ([HfpState]); READY = adapter off or no phone. */
    fun hfp(s: BtCarKitSnapshot): HfpState {
        if (!s.adapterOn) {
            return HfpState.READY
        }
        if (!s.hfConnected) {
            return if (s.hfLink == HfLink.CONNECTING) HfpState.CONNECTING else HfpState.READY
        }
        val lead = leadCall(s.calls) ?: return HfpState.CONNECTED

        return when (lead.state) {
            HfCallState.INCOMING, HfCallState.WAITING -> HfpState.INCOMING_CALL
            HfCallState.DIALING, HfCallState.ALERTING -> HfpState.OUTGOING_CALL
            else -> HfpState.ACTIVE_CALL
        }
    }

    /** Carry [prev] while a call stays active; stamp [nowMs] when one starts; drop otherwise. */
    fun activeSince(prev: Long?, calls: List<HfCall>, nowMs: Long): Long? {
        val active = calls.any { it.state == HfCallState.ACTIVE || it.state == HfCallState.HELD }
        if (!active) {
            return null
        }
        return prev ?: nowMs
    }

    /** The snapshot in the vocabulary the Phone screen, the chips and CallPopupGuard read. */
    fun vendorView(s: BtCarKitSnapshot, nowMs: Long): VendorBtState {
        val hfp = hfp(s)
        val inCall = hfp.code > HfpState.CONNECTED.code
        val lead = if (inCall) leadCall(s.calls) else null

        return VendorBtState(
            powered = s.adapterOn,
            hshf = hfp.code,
            connected = s.adapterOn && (s.hfConnected || s.sinkConnected),
            inCall = inCall,
            deviceName = s.phoneName,
            avPlaying = if (s.sinkConnected) s.audioPlaying else null,
            callerNumber = lead?.number,
            speakingSec = s.activeSinceMs?.let { ((nowMs - it) / MS_PER_SECOND).toInt() },
            lastEventMs = nowMs,
        )
    }

    /** The Doctor row. Null [s] = the vendor slot, where btsuite is the phone. */
    fun reading(s: BtCarKitSnapshot?): BtCarKitReading {
        if (s == null) {
            return BtCarKitReading(ok = true, title = TITLE, detail = VENDOR_DETAIL)
        }
        if (!s.adapterOn) {
            return BtCarKitReading(ok = false, title = TITLE, detail = ADAPTER_OFF_DETAIL)
        }
        val on = profilesOn(s) ?: return BtCarKitReading(ok = false, title = TITLE, detail = WAITING_DETAIL)

        val profiles = PROFILE_NAMES.entries.joinToString(", ") { (id, name) ->
            "$name ${if (id in s.bound) "on" else "OFF"}"
        }
        val phone = s.phoneName?.let { "$it connected" } ?: NO_PHONE
        return BtCarKitReading(ok = on, title = TITLE, detail = "$profiles. Phone: $phone.")
    }
}
