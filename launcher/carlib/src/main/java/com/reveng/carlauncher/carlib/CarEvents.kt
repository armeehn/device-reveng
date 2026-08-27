package com.reveng.carlauncher.carlib

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CarEvents — the launcher's front door to the vendor gateway
 * (`com.szchoiceway.eventcenter`) broadcast API. See CAR_API.md §1 and §4.
 *
 * All action/extra strings below are transcribed from the decompiled `EventUtils.java`
 * constant table documented in CAR_API.md. Where the exact string is not fully quoted in
 * the spec, the value is marked with a TODO and a best-effort literal is used.
 *
 * Usage:
 * ```
 * val car = CarEvents(context)
 * car.register()                       // call from Activity.onCreate / Service.onCreate
 * lifecycleScope.launch { car.reverse.collect { engaged -> ... } }
 * ...
 * car.unregister()                     // onDestroy
 * ```
 *
 * State is exposed as [StateFlow] (latest value, good for UI) and discrete presses as
 * [SharedFlow]. A plain callback ([Listener]) is offered for Java/non-coroutine callers.
 */
class CarEvents(private val appContext: Context) {

    companion object {
        private const val TAG = "CarEvents"

        // ---- Broadcast permission (CAR_API §1.1) ----------------------------
        /** Protected car events are sent with this permission. Likely `signature`. */
        const val PERMISSION_CHOICEWAY_BROADCAST = "com.szchoiceway.permission.broadcast"

        // ---- Reverse / backcar (CAR_API §1.3) -------------------------------
        /** Protected: fired by startBackcar(). Requires the permission to receive. */
        const val ACTION_BACKCAR_START = "com.choiceway.eventcenter.ACTION_BACKCAR_START"
        const val ACTION_BACKCAR_END = "com.choiceway.eventcenter.ACTION_BACKCAR_END"

        /** Unprotected raw MCU-level reverse events — the normal-app fallback. */
        const val MCU_MSG_BACKCAR_START = "com.choiceway.eventcenter.EventUtils.MCU_MSG_BACKCAR_START"
        const val MCU_MSG_BACKCAR_END = "com.choiceway.eventcenter.EventUtils.MCU_MSG_BACKCAR_END"

        // ---- ACC power (CAR_API §1.3) — unprotected -------------------------
        const val ACTION_ACC_OPEN_CLOSE_EVT =
            "com.choiceway.eventcenter.EventUtils.ACTION_ACC_OPEN_CLOSE_EVT"
        const val ACTION_ACC_SLEEP_STATUS_EVT =
            "com.choiceway.eventcenter.EventUtils.ACTION_ACC_SLEEP_STATUS_EVT"
        /** int extra: 1 = ACC on, 0 = off. */
        const val EXTRA_ACC_STATUS = "ACC_Status"

        // ---- Steering-wheel keys (CAR_API §4) — protected -------------------
        const val STEER_WHEEL_INFOR = "com.choiceway.eventcenter.EventUtils.STEER_WHEEL_INFOR"
        /** int: key index (bArr[1] + 1). */
        const val EXTRA_SWC_LPARAM = "EventUtils.STEER_WHEEL_INFOR_LPARAM"
        /** int: 3 = pressed/down, 4 = released/up. */
        const val EXTRA_SWC_WPARAM = "EventUtils.STEER_WHEEL_INFOR_WPARAM"
        /** int: raw resistive-key ADC voltage. */
        const val EXTRA_SWC_VOLTAGE = "EventUtils.STEER_WHEEL_INFOR_VOLTAGE"

        /** Secondary host/panel key path (unprotected). */
        const val ACTION_HOST_MCU_BUTTON_KEY =
            "com.choiceway.eventcenter.EventUtils.ACTION_HOST_MCU_BUTTON_KEY"
        const val EXTRA_HOST_KEY = "HostKeyWord"
        const val EXTRA_HOST_STATUS_KEY = "HostKeyStatus"

        /** Tertiary panel key path (unprotected). */
        const val MCU_KEY_INFOR_ACTION = "com.choiceway.eventcenter.EventUtils.MCU_KEY_INFOR"
        const val EXTRA_MCU_KEY_VALUE = "EventUtils.MCU_KEY_VALUE"

        // ---- Speed (CAR_API §1.3 note) --------------------------------------
        /**
         * NOTE: this is a *show/hide UI toggle only* — it carries NO speed value.
         * The numeric speed is not published as a clean extra (see [speedKmh] KDoc).
         */
        const val SHOW_CAR_SPEED_EVENT =
            "com.choiceway.eventcenter.EventUtils.SHOW_CAR_SPEED_EVENT"

        // ---- Day / night backlight (CAR_API §1.3) — protected ---------------
        const val ACTION_DAY_BACKLIGHT_CHANGED = "com.szchoiceway.ACTION_DAY_BACKLIGHT_CHAGNED"
        const val ACTION_NIGHT_BACKLIGHT_CHANGED = "com.szchoiceway.ACTION_NIGHT_BACKLIGHT_CHAGNED"

        // ---- Radar (CAR_API §1.3) — unprotected -----------------------------
        const val MCU_CAR_CAN_RADAR_INFO =
            "com.choiceway.eventcenter.EventUtils.MCU_CAR_CAN_RADAR_INFO"
        const val EXTRA_CAR_CAN_DATA = "EventUtils.CAR_CAN_DATA"

        // ---- Climate (CAR_API §1.3) — unprotected ---------------------------
        const val CAR_AIR_STATE_ACTION = "com.szchoiceway.canbus.carairstruct"
        /** Parcelable com.szchoiceway.canbus.CarAirState (class not bundled — TODO). */
        const val EXTRA_CAR_AIR_STATE = "com.choiceway.canbus.carairstruct.airstate"
        /** Candidate byte[] extra keys the gateway may also attach (CAR_API §5). GUESSED. */
        private val AIR_BYTE_EXTRA_KEYS = arrayOf(
            "EventUtils.CAR_AIR_DATA",
            "CAR_AIR_DATA",
            "EventUtils.CAR_CAN_DATA",
        )

        // ---- SWC keycodes (CAR_API §4, CAR_KEY_*) ---------------------------
        const val CAR_KEY_POWER = 1
        const val CAR_KEY_HOME = 2
        const val CAR_KEY_FAV = 3
        const val CAR_KEY_PREV = 4
        const val CAR_KEY_NEXT = 5
        const val CAR_KEY_MENU = 6
        const val CAR_KEY_PHONE = 7
        const val CAR_KEY_MEDIA = 8
        const val CAR_KEY_RADIO = 9
        const val CAR_KEY_BACK = 10
        const val CAR_KEY_L_TUNE_L = 11
        const val CAR_KEY_L_TUNE_R = 12
        const val CAR_KEY_R_TUNE_L = 13
        const val CAR_KEY_R_TUNE_R = 14

        /** WPARAM press-state values. */
        const val SWC_STATE_DOWN = 3
        const val SWC_STATE_UP = 4
    }

    /** A steering-wheel key event decoded from [STEER_WHEEL_INFOR]. */
    data class SwcKey(val keyIndex: Int, val down: Boolean, val voltage: Int)

    /** Day/night illumination source (from the backlight broadcasts). */
    enum class DayNight { DAY, NIGHT }

    /** Optional plain callback for Java / non-coroutine consumers. */
    interface Listener {
        fun onReverse(engaged: Boolean) {}
        fun onAcc(on: Boolean) {}
        fun onSwcKey(key: SwcKey) {}
        fun onDayNight(mode: DayNight) {}
    }

    // ---- State flows --------------------------------------------------------
    private val _reverse = MutableStateFlow(false)
    /** true while the car is in reverse (backcar engaged). */
    val reverse: StateFlow<Boolean> = _reverse.asStateFlow()

    private val _accOn = MutableStateFlow(true)
    /** true while ACC (ignition accessory) is on. */
    val accOn: StateFlow<Boolean> = _accOn.asStateFlow()

    private val _dayNight = MutableStateFlow(DayNight.DAY)
    /** Latest day/night illumination state for theming. */
    val dayNight: StateFlow<DayNight> = _dayNight.asStateFlow()

    private val _swcKeys = MutableSharedFlow<SwcKey>(extraBufferCapacity = 16)
    /** Discrete steering-wheel key presses/releases. */
    val swcKeys: SharedFlow<SwcKey> = _swcKeys.asSharedFlow()

    private val _climate = MutableStateFlow<ClimateState?>(null)
    /**
     * Latest HVAC snapshot decoded from the `carairstruct` broadcast, or null if none has
     * been decoded. The vendor Parcelable class isn't bundled, so this is only populated when
     * the gateway also attaches a raw byte[] frame (best-effort); otherwise consumers fall
     * back to AIDL `getAirData()`.
     */
    val climate: StateFlow<ClimateState?> = _climate.asStateFlow()

    // v0.7 --- Parking radar (CAR_API §1.3 MCU_CAR_CAN_RADAR_INFO) ------------
    private val _radar = MutableStateFlow<RadarState?>(null)
    /**
     * Latest parking-sensor frame decoded from `MCU_CAR_CAN_RADAR_INFO` (byte[] CAR_CAN_DATA),
     * or null until the first frame arrives. The frame is unprotected (a normal app receives
     * it), typically only while reversing / at low speed. Byte layout is GUESSED — see
     * [RadarState].
     */
    val radar: StateFlow<RadarState?> = _radar.asStateFlow()

    /**
     * Numeric speed in km/h.
     *
     * TODO(CAR_API §1.3 note): the gateway does NOT broadcast a clean speed extra.
     * [SHOW_CAR_SPEED_EVENT] is only a show/hide toggle. To populate this flow, one of:
     *   (a) parse the CAN bulk frame (CAN_BASIC_EVT / MCU_CAR_CAN_INFO),
     *   (b) read GPS speed via LocationManager,
     *   (c) query the AIDL/socket channel.
     * Until one of those is wired up, this stays at -1 (unknown).
     */
    private val _speedKmh = MutableStateFlow(-1)
    val speedKmh: StateFlow<Int> = _speedKmh.asStateFlow()

    private val listeners = mutableSetOf<Listener>()
    private var registered = false

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_BACKCAR_START, MCU_MSG_BACKCAR_START -> updateReverse(true)
                ACTION_BACKCAR_END, MCU_MSG_BACKCAR_END -> updateReverse(false)

                ACTION_ACC_OPEN_CLOSE_EVT -> {
                    val on = intent.getIntExtra(EXTRA_ACC_STATUS, 1) == 1
                    _accOn.value = on
                    listeners.forEach { it.onAcc(on) }
                }

                STEER_WHEEL_INFOR -> {
                    val idx = intent.getIntExtra(EXTRA_SWC_LPARAM, 0)
                    val st = intent.getIntExtra(EXTRA_SWC_WPARAM, 0)
                    val v = intent.getIntExtra(EXTRA_SWC_VOLTAGE, 0)
                    val key = SwcKey(idx, down = st == SWC_STATE_DOWN, voltage = v)
                    _swcKeys.tryEmit(key)
                    listeners.forEach { it.onSwcKey(key) }
                }

                ACTION_DAY_BACKLIGHT_CHANGED -> updateDayNight(DayNight.DAY)
                ACTION_NIGHT_BACKLIGHT_CHANGED -> updateDayNight(DayNight.NIGHT)

                // v0.7: raw parking-radar frame → best-effort decode (offsets GUESSED).
                MCU_CAR_CAN_RADAR_INFO -> {
                    val bytes = intent.getByteArrayExtra(EXTRA_CAR_CAN_DATA)
                    val rs = RadarState.fromRadarData(bytes)
                    if (rs.valid) _radar.value = rs
                }

                CAR_AIR_STATE_ACTION -> {
                    // The primary extra is a Parcelable CarAirState we can't deserialize
                    // (class not bundled). Best-effort: pick up a raw byte[] frame if the
                    // gateway also attached one, and decode it. Otherwise leave the flow as-is
                    // so consumers fall back to AIDL getAirData().
                    val bytes = AIR_BYTE_EXTRA_KEYS.firstNotNullOfOrNull { key ->
                        runCatching { intent.getByteArrayExtra(key) }.getOrNull()
                    }
                    if (bytes != null) {
                        val cs = ClimateState.fromAirData(bytes)
                        if (cs.valid) _climate.value = cs
                    }
                }

                else -> Log.d(TAG, "unhandled action: ${intent?.action}")
            }
        }
    }

    private fun updateReverse(engaged: Boolean) {
        if (_reverse.value != engaged) {
            _reverse.value = engaged
            listeners.forEach { it.onReverse(engaged) }
        }
    }

    private fun updateDayNight(mode: DayNight) {
        _dayNight.value = mode
        listeners.forEach { it.onDayNight(mode) }
    }

    /**
     * Register the receiver. Safe to call once; a second call is a no-op.
     *
     * We register for BOTH the protected (`ACTION_BACKCAR_*`, `STEER_WHEEL_INFOR`,
     * day/night) and the unprotected (`MCU_MSG_BACKCAR_*`, ACC) actions in a single
     * receiver. As a normal app the protected ones are silently never delivered; as a
     * privileged/system app holding [PERMISSION_CHOICEWAY_BROADCAST] they start flowing
     * with no code change.
     */
    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_BACKCAR_START)
            addAction(ACTION_BACKCAR_END)
            addAction(MCU_MSG_BACKCAR_START)
            addAction(MCU_MSG_BACKCAR_END)
            addAction(ACTION_ACC_OPEN_CLOSE_EVT)
            addAction(ACTION_ACC_SLEEP_STATUS_EVT)
            addAction(STEER_WHEEL_INFOR)
            addAction(ACTION_HOST_MCU_BUTTON_KEY)
            addAction(MCU_KEY_INFOR_ACTION)
            addAction(ACTION_DAY_BACKLIGHT_CHANGED)
            addAction(ACTION_NIGHT_BACKLIGHT_CHANGED)
            addAction(MCU_CAR_CAN_RADAR_INFO)
            addAction(CAR_AIR_STATE_ACTION)
        }
        // Vendor gateway is a separate app -> this is not an app-internal broadcast,
        // so it must be exported on API 33+ (RECEIVER_EXPORTED).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
        registered = true
        Log.i(TAG, "CarEvents registered")
    }

    fun unregister() {
        if (!registered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        registered = false
    }

    /**
     * Cold [Flow] variant of [reverse] that manages its own receiver lifecycle: it
     * registers on collection and unregisters on cancellation. Handy in a `collectAsState`
     * without a manual register()/unregister() pair.
     */
    fun reverseFlow(): Flow<Boolean> = callbackFlowReverse()

    private fun callbackFlowReverse(): Flow<Boolean> =
        kotlinx.coroutines.flow.callbackFlow {
            val local = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    when (i?.action) {
                        ACTION_BACKCAR_START, MCU_MSG_BACKCAR_START -> trySend(true)
                        ACTION_BACKCAR_END, MCU_MSG_BACKCAR_END -> trySend(false)
                    }
                }
            }
            val f = IntentFilter().apply {
                addAction(ACTION_BACKCAR_START)
                addAction(ACTION_BACKCAR_END)
                addAction(MCU_MSG_BACKCAR_START)
                addAction(MCU_MSG_BACKCAR_END)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(local, f, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(local, f)
            }
            awaitClose { runCatching { appContext.unregisterReceiver(local) } }
        }
}
