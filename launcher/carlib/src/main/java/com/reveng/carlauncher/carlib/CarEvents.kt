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

        // ---- v0.4.3: CAN bulk-frame capture (CAR_API §1.3) — unprotected -----
        // Bulk CAN state frame — the route to a real speed reading (CAR_API line 109; the
        // CAN_BASIC_EVT receiver is confirmed in EvtModel.java). The fully-qualified action
        // strings follow the vendor's EventUtils.* convention but are GUESSED at that prefix;
        // the CanCaptureScreen exists precisely to confirm which action + extra actually arrive.
        const val MCU_CAR_CAN_INFO =
            "com.choiceway.eventcenter.EventUtils.MCU_CAR_CAN_INFO"
        const val CAN_BASIC_EVT =
            "com.choiceway.eventcenter.EventUtils.CAN_BASIC_EVT"

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

        // ---- v3.0: cockpit signals (CAR_API §1.3) — unprotected --------------
        /**
         * Outside temperature. The *action* is confirmed (`EvtModel.java:512-517`); the exact
         * extra key strings are not quoted in the decompile, only described as `..._EVT_EXTRA`
         * (int) and `..._EVT_EXTRA_STR` (String), so the candidates below are GUESSED the same
         * way [AIR_BYTE_EXTRA_KEYS] is. The String form is preferred when present because it
         * arrives already formatted with the car's own unit, so we don't have to guess whether
         * the int is °C, °F, or tenths.
         */
        const val CAN_CAR_OUT_SIDE_TEMP_EVT =
            "com.choiceway.eventcenter.CanUtils.CAN_CAR_OUT_SIDE_TEMP_EVT"
        private val OUT_TEMP_STR_EXTRA_KEYS = arrayOf(
            "CAN_CAR_OUT_SIDE_TEMP_EVT_EXTRA_STR",
            "CanUtils.CAN_CAR_OUT_SIDE_TEMP_EVT_EXTRA_STR",
        )
        private val OUT_TEMP_INT_EXTRA_KEYS = arrayOf(
            "CAN_CAR_OUT_SIDE_TEMP_EVT_EXTRA",
            "CanUtils.CAN_CAR_OUT_SIDE_TEMP_EVT_EXTRA",
        )

        /**
         * Steering angle, the signal the vendor uses to bend its reverse trajectory lines
         * (`EvtModel.java:534-538`, confirmed). Units and sign convention are NOT documented —
         * see [steeringAngle].
         */
        const val ZXW_CAN_WHEEL_TRACK_EVT =
            "com.choiceway.eventcenter.EventUtils.ZXW_CAN_WHEEL_TRACK_EVT"
        private val WHEEL_TRACK_EXTRA_KEYS = arrayOf(
            "ZXW_CAN_WHEEL_TRACK_EVT_EXTRA",
            "EventUtils.ZXW_CAN_WHEEL_TRACK_EVT_EXTRA",
        )

        /** Sentinel for a cockpit signal the car has not sent us yet. */
        const val VALUE_UNKNOWN = Int.MIN_VALUE

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

        // ---- v2.5 motion thresholds (LAUNCHER_DESIGN §1.4) -------------------
        /**
         * Hysteresis band for [motion]. We only become [Motion.MOVING] at or above
         * [MOVING_ABOVE_KMH], and only fall back to [Motion.PARKED] at or below
         * [PARKED_BELOW_KMH]; inside the band the previous verdict stands. Without that gap a
         * car creeping at walking pace flaps the gate open and shut every second, which is
         * worse for the driver than either state.
         *
         * 8 km/h ≈ 5 mph, the conventional automotive lockout threshold.
         */
        const val MOVING_ABOVE_KMH = 8
        const val PARKED_BELOW_KMH = 3

        /**
         * Apply the hysteresis band. Note the band's *entry* case: with no previous verdict, a
         * live fix between [PARKED_BELOW_KMH] and [MOVING_ABOVE_KMH] resolves to [Motion.MOVING],
         * not PARKED — we can see the car is not stationary, so the gate closes.
         *
         * Pure and instance-free, so it sits on the companion where a unit test can reach it
         * without standing up a Context. `internal`, not public: the verdict is published through
         * [motion], and a second public entry point would let a consumer read a raw speed past
         * the smoothing and staleness rules that feed it.
         */
        internal fun nextMotion(current: Motion, kmh: Int): Motion = when {
            kmh < 0 -> Motion.UNKNOWN
            kmh >= MOVING_ABOVE_KMH -> Motion.MOVING
            kmh <= PARKED_BELOW_KMH -> Motion.PARKED
            current == Motion.UNKNOWN -> Motion.MOVING
            else -> current
        }
    }

    /** A steering-wheel key event decoded from [STEER_WHEEL_INFOR]. */
    data class SwcKey(val keyIndex: Int, val down: Boolean, val voltage: Int)

    /**
     * v2.5 — stationary / in motion / unreadable, derived from [speedKmh].
     *
     * [UNKNOWN] is an ordinary state rather than an error: no location permission, a cold GPS,
     * a tunnel, any underground car park. What it should *mean* is the consumer's decision —
     * see the fail-open note on [motion].
     */
    enum class Motion { UNKNOWN, PARKED, MOVING }

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

    private val _illuminationSeen = MutableStateFlow(false)
    /**
     * v2.7 — true once an illumination broadcast has actually arrived.
     *
     * [dayNight] cannot answer this. It starts at [DayNight.DAY] and stays there both when the car
     * really is reporting daylight and when the broadcast never arrives at all — and on a normal
     * (non-privileged) install it never arrives, because ACTION_DAY/NIGHT_BACKLIGHT_CHANGED ride
     * `com.szchoiceway.permission.broadcast`, which is very likely `signature` (CAR_API §1.1).
     *
     * The launcher's clock-based day/night fallback keys off this flag: a unit that is genuinely
     * hearing the car keeps following the car, and only a silent one falls back to the clock.
     * Latched, never cleared — one broadcast proves the signal exists for the rest of the session.
     */
    val illuminationSeen: StateFlow<Boolean> = _illuminationSeen.asStateFlow()

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

    // v2.8 --- Undecoded radar payload, for the capture view -------------------
    private val _radarRaw = MutableStateFlow<RadarFrame?>(null)
    /**
     * v2.8 — the raw `CAR_CAN_DATA` payload of the last radar broadcast, undecoded.
     *
     * Published even when [RadarState.fromRadarData] rejects the frame. The guessed layout is
     * precisely what the capture screen exists to disprove, so gating the bytes on that guess being
     * right would hide the evidence in exactly the case where it matters.
     */
    val radarRaw: StateFlow<RadarFrame?> = _radarRaw.asStateFlow()

    // v0.4.3 --- Undecoded CAN bulk frame, for the capture view ----------------
    private val _canRaw = MutableStateFlow<CanFrame?>(null)
    /**
     * v0.4.3 — the last CAN bulk-frame broadcast (CAN_BASIC_EVT / MCU_CAR_CAN_INFO), with every
     * extra captured and any byte[] payload kept undecoded. Null until a frame arrives (which, on a
     * normal app or off a car, may be never — the action strings and payload key are unconfirmed;
     * [com.reveng.carlauncher.ui.settings.CanCaptureScreen] is the instrument that confirms them).
     */
    val canRaw: StateFlow<CanFrame?> = _canRaw.asStateFlow()

    /**
     * Numeric speed in km/h, or [GpsSpeedSource.SPEED_UNKNOWN] when it cannot be read.
     *
     * v2.5: populated from [GpsSpeedSource] — option (b) of the three the v0.x stub listed. The
     * gateway still does NOT broadcast a clean speed extra ([SHOW_CAR_SPEED_EVENT] is a
     * show/hide toggle, CAR_API §1.3), so GPS is the only source a normal app can read.
     * Decoding the CAN bulk frame (CAN_BASIC_EVT / MCU_CAR_CAN_INFO) remains the preferred
     * upgrade and should be *preferred over* GPS once its layout is confirmed on-device: it is
     * available instantly at power-on and indoors, where GPS is not.
     */
    private val _speedKmh = MutableStateFlow(GpsSpeedSource.SPEED_UNKNOWN)
    val speedKmh: StateFlow<Int> = _speedKmh.asStateFlow()

    private val _rootCapture = MutableStateFlow(false)
    /**
     * v2.9 — true once the root helper has actually delivered a protected broadcast.
     *
     * Not "root is available" and not "the helper started": only a real captured event proves the
     * whole chain works, and the chain has several links that can fail quietly on an unfamiliar
     * unit (see [RootBroadcastHelper]). Surfaced so Settings can say which source the reverse and
     * steering-wheel state is really coming from — "no wheel keys because none were pressed" and
     * "no wheel keys because capture never started" look identical otherwise.
     */
    val rootCapture: StateFlow<Boolean> = _rootCapture.asStateFlow()

    private val _motion = MutableStateFlow(Motion.UNKNOWN)
    /**
     * v2.5 — the safety gate's view of [speedKmh], with the [MOVING_ABOVE_KMH] /
     * [PARKED_BELOW_KMH] hysteresis applied.
     *
     * **Unknown fails open.** A consumer gating a feature should block on [Motion.MOVING] only,
     * and permit on [Motion.UNKNOWN]. Failing closed instead would lock the driver out of their
     * own launcher in exactly the places speed is unreadable — a garage, a covered car park, or
     * a unit where the location permission was never granted — and would do so permanently and
     * silently. We never fail open once we *do* have a reading: any fix inside the hysteresis
     * band resolves to [Motion.MOVING], because a car with a live speed fix above
     * [PARKED_BELOW_KMH] is, in fact, moving.
     */
    val motion: StateFlow<Motion> = _motion.asStateFlow()

    // v3.0 --- cockpit signals ------------------------------------------------
    private val _outsideTemp = MutableStateFlow<String?>(null)
    /**
     * Outside temperature as the car renders it (e.g. "12°C"), or null until a frame arrives.
     *
     * Deliberately a String rather than a number: the gateway sends a preformatted string
     * alongside the raw int, and using it means we neither guess the unit nor re-render a value
     * the car already rendered — the dashboard then always agrees with the cluster. If only the
     * int arrives we fall back to labelling it °C, which is a GUESS and marked as such below.
     */
    val outsideTemp: StateFlow<String?> = _outsideTemp.asStateFlow()

    private val _steeringAngle = MutableStateFlow(VALUE_UNKNOWN)
    /**
     * Raw steering angle from `ZXW_CAN_WHEEL_TRACK_EVT`, or [VALUE_UNKNOWN].
     *
     * ⚠ Units and sign are GUESSED: the decompile confirms the extra is an int the vendor feeds
     * into its reverse trajectory, but never says whether it is degrees, tenths of a degree, or
     * a raw CAN count, nor which way positive turns. The dashboard therefore draws it as a
     * *relative* indicator centred on the value seen while travelling straight, and never claims
     * a number of degrees. Confirm on-device by turning lock to lock and reading the extremes.
     */
    val steeringAngle: StateFlow<Int> = _steeringAngle.asStateFlow()

    /**
     * v2.5 — GPS speed. Owned here so [speedKmh] has one front door regardless of which
     * underlying source fills it, matching how [reverse] and [dayNight] hide their broadcasts.
     */
    private val speedSource = GpsSpeedSource(appContext) { kmh -> updateSpeed(kmh) }

    /**
     * v2.9 — root capture of the `signature`-guarded broadcasts. Owned here for the same reason
     * [speedSource] is: consumers keep one front door and never learn which source filled a flow.
     */
    private val rootBridge = RootBroadcastBridge(appContext) { action, ints ->
        _rootCapture.value = true
        handleProtected(action, ints)
    }

    // Copy-on-write: a listener may add/remove itself from inside its own callback while we
    // are iterating during dispatch, which would throw ConcurrentModificationException on a
    // plain mutableSet; it also makes concurrent add/remove from other threads safe.
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<Listener>()
    private var registered = false

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (val action = intent?.action) {
                MCU_MSG_BACKCAR_START -> updateReverse(true)
                MCU_MSG_BACKCAR_END -> updateReverse(false)

                ACTION_ACC_OPEN_CLOSE_EVT -> {
                    val on = intent.getIntExtra(EXTRA_ACC_STATUS, 1) == 1
                    _accOn.value = on
                    listeners.forEach { it.onAcc(on) }
                }

                // v3.0: outside temperature. Prefer the car's own formatted string over the
                // int, so the dashboard shows the same value and unit as the cluster.
                CAN_CAR_OUT_SIDE_TEMP_EVT -> {
                    val text = OUT_TEMP_STR_EXTRA_KEYS.firstNotNullOfOrNull { key ->
                        runCatching { intent.getStringExtra(key) }.getOrNull()?.takeIf {
                            it.isNotBlank()
                        }
                    }
                    val fallback = OUT_TEMP_INT_EXTRA_KEYS
                        .map { key -> intent.getIntExtra(key, VALUE_UNKNOWN) }
                        .firstOrNull { it != VALUE_UNKNOWN }
                        // ⚠ GUESSED unit — only used when the preformatted string is absent.
                        ?.let { "$it°C" }
                    val value = text ?: fallback
                    if (value != null) _outsideTemp.value = value
                }

                // v3.0: steering angle (raw; see the KDoc on steeringAngle).
                ZXW_CAN_WHEEL_TRACK_EVT -> {
                    val angle = WHEEL_TRACK_EXTRA_KEYS
                        .map { key -> intent.getIntExtra(key, VALUE_UNKNOWN) }
                        .firstOrNull { it != VALUE_UNKNOWN }
                    if (angle != null) _steeringAngle.value = angle
                }

                // v2.9: the protected set. Still filtered for here, because a privileged/system
                // install DOES receive them directly and must not need the root helper. When root
                // capture is live we drop these instead: on such an install both paths deliver the
                // same event, and a duplicate SwcKey is a second key press as far as the focus ring
                // is concerned. handleProtected parses STEER_WHEEL_INFOR into the same SwcKey the
                // v3.0 inline handler did, so SWC input is unchanged; root-capture dedup is added.
                ACTION_BACKCAR_START, ACTION_BACKCAR_END, STEER_WHEEL_INFOR,
                ACTION_DAY_BACKLIGHT_CHANGED, ACTION_NIGHT_BACKLIGHT_CHANGED -> {
                    if (!_rootCapture.value) handleProtected(action, swcIntExtras(intent))
                }

                // v0.7: raw parking-radar frame → best-effort decode (offsets GUESSED).
                MCU_CAR_CAN_RADAR_INFO -> {
                    val bytes = intent.getByteArrayExtra(EXTRA_CAR_CAN_DATA)
                    // v2.8: keep the payload before it is interpreted (see radarRaw).
                    if (bytes != null) {
                        _radarRaw.value = RadarFrame(bytes, System.currentTimeMillis())
                    }
                    val rs = RadarState.fromRadarData(bytes)
                    if (rs.valid) _radar.value = rs
                }

                // v0.4.3: bulk CAN frame — capture every extra + any byte[] payload, undecoded.
                MCU_CAR_CAN_INFO, CAN_BASIC_EVT -> {
                    _canRaw.value = CanFrame.from(intent, System.currentTimeMillis())
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

    /**
     * v2.9 — apply one protected event, whatever carried it. Both the in-process receiver and the
     * root helper land here so the two sources cannot drift apart in how they decode an event.
     *
     * [ints] holds only the extras that action defines; a missing one falls back to the same
     * default the v2.5 receiver used.
     */
    private fun handleProtected(action: String, ints: Map<String, Int>) {
        when (action) {
            ACTION_BACKCAR_START -> updateReverse(true)
            ACTION_BACKCAR_END -> updateReverse(false)
            ACTION_DAY_BACKLIGHT_CHANGED -> updateDayNight(DayNight.DAY)
            ACTION_NIGHT_BACKLIGHT_CHANGED -> updateDayNight(DayNight.NIGHT)

            STEER_WHEEL_INFOR -> {
                val key = SwcKey(
                    keyIndex = ints[EXTRA_SWC_LPARAM] ?: 0,
                    down = (ints[EXTRA_SWC_WPARAM] ?: 0) == SWC_STATE_DOWN,
                    voltage = ints[EXTRA_SWC_VOLTAGE] ?: 0,
                )
                _swcKeys.tryEmit(key)
                listeners.forEach { it.onSwcKey(key) }
            }
        }
    }

    /** v2.9 — the int extras [handleProtected] may want, read off a directly-received Intent. */
    private fun swcIntExtras(intent: Intent): Map<String, Int> {
        if (intent.action != STEER_WHEEL_INFOR) {
            return emptyMap()
        }
        return mapOf(
            EXTRA_SWC_LPARAM to intent.getIntExtra(EXTRA_SWC_LPARAM, 0),
            EXTRA_SWC_WPARAM to intent.getIntExtra(EXTRA_SWC_WPARAM, 0),
            EXTRA_SWC_VOLTAGE to intent.getIntExtra(EXTRA_SWC_VOLTAGE, 0),
        )
    }

    private fun updateReverse(engaged: Boolean) {
        if (_reverse.value != engaged) {
            _reverse.value = engaged
            listeners.forEach { it.onReverse(engaged) }
        }
    }

    private fun updateDayNight(mode: DayNight) {
        _dayNight.value = mode
        _illuminationSeen.value = true // v2.7
        listeners.forEach { it.onDayNight(mode) }
    }

    /** v2.5 — publish a new speed reading and re-derive [motion] from it. */
    private fun updateSpeed(kmh: Int) {
        _speedKmh.value = kmh
        _motion.value = nextMotion(_motion.value, kmh)
    }

    /**
     * Register the receiver. Safe to call once; a second call is a no-op.
     *
     * We register for BOTH the protected (`ACTION_BACKCAR_*`, `STEER_WHEEL_INFOR`,
     * day/night) and the unprotected (`MCU_MSG_BACKCAR_*`, ACC) actions in a single
     * receiver. As a normal app the protected ones are silently never delivered; as a
     * privileged/system app holding [PERMISSION_CHOICEWAY_BROADCAST] they start flowing
     * with no code change.
     *
     * v2.9 also starts the root capture ([RootBroadcastBridge]), which gets the protected events
     * on a plain user install of a rooted unit — the tier that replaced "platform-signed system
     * app" once the vendor key was confirmed unobtainable. It degrades silently: with no root
     * nothing changes and the v2.5 fallbacks stand.
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
            addAction(MCU_CAR_CAN_INFO) // v0.4.3 CAN bulk-frame capture
            addAction(CAN_BASIC_EVT) // v0.4.3
            addAction(CAR_AIR_STATE_ACTION)
            addAction(CAN_CAR_OUT_SIDE_TEMP_EVT) // v3.0
            addAction(ZXW_CAN_WHEEL_TRACK_EVT) // v3.0
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
        // v2.5: GPS is a separate subsystem from the gateway broadcasts, but it feeds the same
        // front door, so it shares this lifecycle instead of asking every caller to manage it.
        speedSource.start()
        rootBridge.start() // v2.9
        Log.i(TAG, "CarEvents registered")
    }

    fun unregister() {
        if (!registered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        speedSource.stop() // v2.5
        rootBridge.stop() // v2.9
        registered = false
    }

    /**
     * v2.5 — retry the GPS speed source. Idempotent, and needed because [register] runs before
     * the user has answered the location prompt: the first attempt finds no permission and gives
     * up, so the grant callback calls this to actually start listening.
     */
    fun startSpeedSource() = speedSource.start()

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
