package com.ripostelabs.carlauncher.carlib

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

        // ---- v0.4.9: vendor "open the app drawer" broadcast (CUSTOMERUI_NOTES §6) ----
        // The gateway broadcasts this (unprotected) to make the launcher open its in-process
        // drawer; customerui registers exactly this action. Only the const NAME and the extra
        // are quoted in the decompile, so the fully-qualified action string follows the
        // EventUtils.* convention and is GUESSED at that prefix (same status as CAN_BASIC_EVT).
        const val ZXW_ACTION_LAUNCHER_ALLAPPS_START_EVT =
            "com.choiceway.eventcenter.EventUtils.ZXW_ACTION_LAUNCHER_ALLAPPS_START_EVT"
        const val EXTRA_LAUNCHER = "LAUNCHER_EXTRA"
        const val LAUNCHER_EXTRA_APPLIST = "AppList"

        // ---- v0.4.9: vendor BT module status (CUSTOMERUI_NOTES §3e/§4) ------
        // The vendor launcher's BT chip rides `com.szchoiceway.btsuite.HBCP_EVT_*` broadcasts
        // (power / connected-device / HSHF status — unprotected). The decompile names only the
        // prefix and those three categories, so the concrete action names below are GUESSED
        // candidates; dispatch matches on the prefix, so a name confirmed on-device later is a
        // one-line addition. A wrong guess is inert: no delivery, no state change.
        const val HBCP_ACTION_PREFIX = "com.szchoiceway.btsuite.HBCP_EVT_"
        val HBCP_CANDIDATE_ACTIONS = arrayOf(
            HBCP_ACTION_PREFIX + "POWER_STATUS",
            HBCP_ACTION_PREFIX + "POWER_ON_OFF",
            HBCP_ACTION_PREFIX + "CONNECT_STATUS",
            HBCP_ACTION_PREFIX + "CONNECTED_DEVICE",
            HBCP_ACTION_PREFIX + "HSHF_STATUS",
        )

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
        // CONFIRMED on GT6-CAR (dumpsys broadcasts, engine on): the bulk frame rides
        // MCU_MSG_CAN_ALL_INFO (com.choiceway prefix) and MCU_CAR_CAN_INFO (com.SZchoiceway
        // prefix - note the different vendor prefix). CAN_BASIC_EVT was not observed.
        const val MCU_MSG_CAN_ALL_INFO =
            "com.choiceway.eventcenter.EventUtils.MCU_MSG_CAN_ALL_INFO"
        const val MCU_CAR_CAN_INFO =
            "com.szchoiceway.eventcenter.EventUtils.MCU_CAR_CAN_INFO"
        const val CAN_BASIC_EVT =
            "com.choiceway.eventcenter.EventUtils.CAN_BASIC_EVT"

        // ---- v0.4.3: radio info sniffer (CAR_API line 113) — unprotected ----
        // Both radio broadcasts, captured raw. Actions are CONFIRMED consts; only the
        // action of ZXW_RADIO_INFO_EVT was recovered (payload never traced), so the sniffer
        // exists to discover what extras it and the frequency broadcast actually carry -- the
        // one route to a station name (README "Known TODOs").
        const val ZXW_RADIO_INFO_EVT =
            "com.choiceway.eventcenter.EventUtils.ZXW_RADIO_INFO_EVT"
        const val RADIO_FREQUENCY_EVENT = "com.szchoiceway.radio.frequency"

        // ---- v0.4.3: vehicle data sniffer (CAR_API line 111) — unprotected --
        // A cluster of CONFIRMED-const CAN events whose payloads were never decoded. One
        // generic sniffer captures every extra of each: to pin the key names v3.0 guesses
        // (outside temp, steering) and to open the ones with no reader at all (TPMS, seat,
        // fuel, trip computer, centre console). FQ strings follow the EventUtils.* convention.
        const val CAN_TPMS_DATA_EVT = "com.choiceway.eventcenter.EventUtils.CAN_TPMS_DATA_EVT"
        const val CAN_SEAT_DATA_EVT = "com.choiceway.eventcenter.EventUtils.CAN_SEAT_DATA_EVT"
        const val CAN_SLS_DATA_EVT = "com.choiceway.eventcenter.EventUtils.CAN_SLS_DATA_EVT"
        const val CAN_FUEL_CONSUMPTION_INFOR = "com.choiceway.eventcenter.EventUtils.CAN_FUEL_CONSUMPTION_INFOR"
        const val CAN_CENTER_CONSOLE_INFOR = "com.choiceway.eventcenter.EventUtils.CAN_CENTER_CONSOLE_INFOR"
        const val CAN_CAR_TIRP_INFO = "com.choiceway.eventcenter.EventUtils.CAN_CAR_TIRP_INFO"
        val VEHICLE_SNIFF_ACTIONS = arrayOf(
            CAN_TPMS_DATA_EVT, CAN_SEAT_DATA_EVT, CAN_SLS_DATA_EVT,
            CAN_FUEL_CONSUMPTION_INFOR, CAN_CENTER_CONSOLE_INFOR, CAN_CAR_TIRP_INFO,
            CAN_CAR_OUT_SIDE_TEMP_EVT, ZXW_CAN_WHEEL_TRACK_EVT,
        )

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
         * (`EvtModel.java:534-538`, confirmed). Its extra keys are undocumented and the guessed
         * ones never matched on the car (RAV4-38), so it is only sniffed now; [steeringAngle]
         * comes from the CAN bulk frame instead.
         */
        const val ZXW_CAN_WHEEL_TRACK_EVT =
            "com.choiceway.eventcenter.EventUtils.ZXW_CAN_WHEEL_TRACK_EVT"

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

        /**
         * v0.4.9 — the int-map a SWC event is DEDUPED on, as opposed to dispatched with.
         *
         * [ProtectedEventDedupe.accept] compares the whole map, and the same physical press can
         * now arrive on up to three carriers: protected `STEER_WHEEL_INFOR` (direct or via the
         * root helper, both carrying VOLTAGE) and the unprotected fallbacks (which carry no
         * voltage at all). Deduping on the full map would therefore never match across carriers,
         * so the dedupe key is the stable subset every carrier can produce: key index + press
         * state. Voltage still reaches [handleProtected] untouched.
         */
        internal fun swcDedupeInts(action: String, ints: Map<String, Int>): Map<String, Int> {
            if (action != STEER_WHEEL_INFOR) {
                return ints
            }
            return ints.filterKeys { it == EXTRA_SWC_LPARAM || it == EXTRA_SWC_WPARAM }
        }
    }

    /** A steering-wheel key event decoded from [STEER_WHEEL_INFOR]. */
    data class SwcKey(val keyIndex: Int, val down: Boolean, val voltage: Int)

    /**
     * v0.4.9 — one `ACTION_ACC_SLEEP_STATUS_EVT` arrival (CAR_API §1.3).
     *
     * ⚠ Decode UNCONFIRMED: the extra key (`ACC_Status`) is documented, but which value means
     * sleep vs wake is not — the spec says only "int (sleep/wake)". So this exposes presence
     * plus the raw value and deliberately does NOT claim a boolean; a consumer that needs the
     * direction must confirm the encoding on-device first.
     */
    data class AccSleep(
        /** Raw `ACC_Status` int, or null when the extra was absent. */
        val rawStatus: Int?,
        val atMs: Long,
    )

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

    private val _accSleep = MutableStateFlow<AccSleep?>(null)
    /**
     * v0.4.9 — latest `ACTION_ACC_SLEEP_STATUS_EVT`, or null until one arrives. The action was
     * registered since v2.5 but silently dropped by dispatch. See [AccSleep] for why this stays
     * a raw presence signal rather than a decoded sleep/wake boolean.
     */
    val accSleep: StateFlow<AccSleep?> = _accSleep.asStateFlow()

    private val _swcKeys = MutableSharedFlow<SwcKey>(extraBufferCapacity = 16)
    /** Discrete steering-wheel key presses/releases. */
    val swcKeys: SharedFlow<SwcKey> = _swcKeys.asSharedFlow()

    private val _openAppList = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    /**
     * v0.4.9 — fires when the vendor gateway asks the launcher to open its app drawer
     * ([ZXW_ACTION_LAUNCHER_ALLAPPS_START_EVT], CUSTOMERUI_NOTES §6). The UI decides what
     * "the drawer" is; this only reports the request.
     */
    val openAppList: SharedFlow<Unit> = _openAppList.asSharedFlow()

    private val _vendorBt = MutableStateFlow(VendorBtState())
    /**
     * v0.4.9 — Bluetooth status per the vendor bt module's `HBCP_EVT_*` broadcasts
     * (CUSTOMERUI_NOTES §3e). Starts as the all-null [VendorBtState]; consumers must gate on
     * [VendorBtState.lastEventMs] so absence of events changes nothing.
     */
    val vendorBt: StateFlow<VendorBtState> = _vendorBt.asStateFlow()

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
     * [com.ripostelabs.carlauncher.ui.settings.CanCaptureScreen] is the instrument that confirms them).
     */
    val canRaw: StateFlow<CanFrame?> = _canRaw.asStateFlow()

    // v0.4.3 --- Undecoded radio info broadcast, for the capture view -----------
    private val _radioInfoRaw = MutableStateFlow<CanFrame?>(null)
    /**
     * v0.4.3 - the last radio broadcast (ZXW_RADIO_INFO_EVT / com.szchoiceway.radio.frequency),
     * every extra captured undecoded. The route to a station name RadioScreen cannot show today;
     * null until a frame arrives (never off a car / tuner).
     */
    val radioInfoRaw: StateFlow<CanFrame?> = _radioInfoRaw.asStateFlow()

    // v0.4.3 --- Vehicle-data sniffer: last frame per confirmed-but-undecoded action --------
    private val _vehicleSniff = MutableStateFlow<Map<String, CanFrame>>(emptyMap())
    /**
     * v0.4.3 - the latest broadcast per action in [VEHICLE_SNIFF_ACTIONS], every extra kept
     * undecoded, so the extra key names (guessed for temp/steering, unknown for the rest) are
     * confirmed on a car. Empty until a frame of a given action arrives.
     */
    val vehicleSniff: StateFlow<Map<String, CanFrame>> = _vehicleSniff.asStateFlow()

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

    private val _steeringAngle = MutableStateFlow<SteeringReading?>(null)
    /**
     * Steering angle decoded from the 0x11 Basic Status frame of the CAN bulk broadcast, or null
     * until one arrives. The same decode the capture screen shows (RAV4-38: the vendor
     * `ZXW_CAN_WHEEL_TRACK_EVT` path never delivered). Degrees on the OEM scale; sign convention
     * unconfirmed — turn lock to lock on-device to settle it. Consumers must apply
     * [SteeringReading.isStale] so a dead feed never reads as a held wheel.
     */
    val steeringAngle: StateFlow<SteeringReading?> = _steeringAngle.asStateFlow()

    /**
     * v2.5 — GPS speed. Owned here so [speedKmh] has one front door regardless of which
     * underlying source fills it, matching how [reverse] and [dayNight] hide their broadcasts.
     */
    private val speedSource = GpsSpeedSource(appContext) { kmh -> updateSpeed(kmh) }

    /**
     * v0.4.3.8 — one arrival slot both protected-event paths pass through, so a frame that really
     * is delivered twice is applied once. See [ProtectedEventDedupe] for why the source flag it
     * replaces could not do this.
     */
    private val protectedDedupe = ProtectedEventDedupe()

    /**
     * v2.9 — root capture of the `signature`-guarded broadcasts. Owned here for the same reason
     * [speedSource] is: consumers keep one front door and never learn which source filled a flow.
     */
    private val rootBridge = RootBroadcastBridge(appContext) { action, ints ->
        _rootCapture.value = true
        // v0.4.9: dedupe on the stable key subset (see swcDedupeInts) so a copy arriving on an
        // unprotected fallback carrier — which has no voltage extra — still matches this one.
        if (protectedDedupe.accept(action, swcDedupeInts(action, ints), System.currentTimeMillis())) {
            handleProtected(action, ints)
        }
    }

    // Copy-on-write: a listener may add/remove itself from inside its own callback while we
    // are iterating during dispatch, which would throw ConcurrentModificationException on a
    // plain mutableSet; it also makes concurrent add/remove from other threads safe.
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<Listener>()
    private var registered = false

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private val receiver = object : BroadcastReceiver() {
        /**
         * v0.4.3.8 — the one place an exception here must not escape.
         *
         * An exception out of `onReceive` is fatal: ActivityManager kills the process, and for the
         * HOME app that is a black screen in a moving car. The gateway attaches vendor Parcelables
         * (e.g. `com.szchoiceway.canbus.CarAirState`) to `CAN_CAR_TIRP_INFO` / `MCU_CAR_CAN_INFO`,
         * and those classes are deliberately not bundled into this APK, so the lazy
         * `Bundle.unparcel()` that reading the extras forces throws `BadParcelableException`
         * (ClassNotFoundException underneath). Those frames arrive continuously with the engine
         * running, so an unguarded throw is a crash loop, not a one-off.
         *
         * [Intent.setExtrasClassLoader] first, so any Parcelable we *do* ship still resolves; the
         * guard covers the ones we do not. Every drop is logged with the action that caused it —
         * this must narrow a bug down, not hide one.
         */
        override fun onReceive(context: Context?, intent: Intent?) {
            runCatching {
                intent?.setExtrasClassLoader(javaClass.classLoader)
                dispatch(intent)
            }.onFailure {
                Log.w(TAG, "dropped ${intent?.action}: ${it.javaClass.simpleName}: ${it.message}")
            }
        }

        /** The real handler; anything it throws is caught and logged by [onReceive]. */
        private fun dispatch(intent: Intent?) {
            // v0.4.3: capture any sniffed vehicle-data action raw, before the typed handlers.
            intent?.action?.let { a ->
                if (a in VEHICLE_SNIFF_ACTIONS) {
                    _vehicleSniff.value = _vehicleSniff.value +
                        (a to CanFrame.from(intent, System.currentTimeMillis()))
                }
                // v0.4.9: vendor BT module status. Prefix match, because the concrete
                // HBCP_EVT_* names are guessed candidates (see HBCP_CANDIDATE_ACTIONS).
                if (a.startsWith(HBCP_ACTION_PREFIX)) {
                    _vendorBt.value = VendorBtDecode.apply(
                        _vendorBt.value, a, rawExtras(intent), System.currentTimeMillis(),
                    )
                }
            }
            when (val action = intent?.action) {
                MCU_MSG_BACKCAR_START -> updateReverse(true)
                MCU_MSG_BACKCAR_END -> updateReverse(false)

                ACTION_ACC_OPEN_CLOSE_EVT -> {
                    val on = intent.getIntExtra(EXTRA_ACC_STATUS, 1) == 1
                    _accOn.value = on
                    listeners.forEach { it.onAcc(on) }
                }

                // v0.4.9: registered since v2.5 but silently dropped by the else branch.
                // Exposed as raw presence only — the value encoding is UNCONFIRMED (AccSleep).
                ACTION_ACC_SLEEP_STATUS_EVT -> {
                    val raw = intent.getIntExtra(EXTRA_ACC_STATUS, VALUE_UNKNOWN)
                    _accSleep.value = AccSleep(
                        rawStatus = raw.takeIf { it != VALUE_UNKNOWN },
                        atMs = System.currentTimeMillis(),
                    )
                }

                // v0.4.9: the gateway's "open the app drawer" request (CUSTOMERUI_NOTES §6).
                // The documented payload is LAUNCHER_EXTRA="AppList"; a missing extra still
                // opens (the action itself is the request), but an extra naming something
                // OTHER than the app list is a request we don't understand — ignore it.
                ZXW_ACTION_LAUNCHER_ALLAPPS_START_EVT -> {
                    val what = intent.getStringExtra(EXTRA_LAUNCHER)
                    if (what == null || what == LAUNCHER_EXTRA_APPLIST) {
                        _openAppList.tryEmit(Unit)
                    }
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

                // v2.9: the protected set. Still filtered for here, because a privileged/system
                // install DOES receive them directly and must not need the root helper. On an
                // install where both paths deliver, the same event arrives twice and a duplicate
                // SwcKey is a second key press as far as the focus ring is concerned.
                // v0.4.3.8: that duplicate is now rejected on the *event*, not on the source flag —
                // reading _rootCapture here was a check-then-act across two threads (the flag is
                // published from the root reader thread while this test runs on the main thread),
                // so a frame arriving in that window was handled twice anyway.
                ACTION_BACKCAR_START, ACTION_BACKCAR_END, STEER_WHEEL_INFOR,
                ACTION_DAY_BACKLIGHT_CHANGED, ACTION_NIGHT_BACKLIGHT_CHANGED -> {
                    val ints = swcIntExtras(intent)
                    // v0.4.9: deduped on the stable subset so the unprotected fallback
                    // carriers (no voltage extra) match too — see swcDedupeInts.
                    val key = swcDedupeInts(action, ints)
                    if (protectedDedupe.accept(action, key, System.currentTimeMillis())) {
                        handleProtected(action, ints)
                    }
                }

                // v0.4.9: the UNPROTECTED steering-wheel fallbacks (CAR_API §4 paths 2+3) —
                // registered since v0.8 but dropped by the else branch, so a non-root install
                // had zero wheel control. Decoded conservatively by SwcFallback, normalised to
                // the canonical STEER_WHEEL_INFOR form, and pushed through the SAME dedupe:
                // on a rooted unit the protected capture delivers the same press, and exactly
                // one of the co-arriving copies may reach handleProtected.
                ACTION_HOST_MCU_BUTTON_KEY -> {
                    val edge = SwcFallback.hostKey(
                        intExtra(intent, EXTRA_HOST_KEY),
                        intExtra(intent, EXTRA_HOST_STATUS_KEY),
                    )
                    if (edge != null) {
                        applyFallbackEdge(edge)
                    }
                }

                MCU_KEY_INFOR_ACTION -> {
                    // This path carries NO press state (CAR_API §1.3), so one broadcast is one
                    // complete tap: synthesise the down and up edges back to back. KeyPump
                    // treats that as a normal short press on every key class.
                    val index = SwcFallback.mcuKey(intExtra(intent, EXTRA_MCU_KEY_VALUE))
                    if (index != null) {
                        applyFallbackEdge(SwcFallback.Edge(index, down = true))
                        applyFallbackEdge(SwcFallback.Edge(index, down = false))
                    }
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
                MCU_CAR_CAN_INFO, MCU_MSG_CAN_ALL_INFO, CAN_BASIC_EVT -> {
                    val frame = CanFrame.from(intent, System.currentTimeMillis())
                    _canRaw.value = frame

                    // RAV4-38: the dashboard's steering is this frame's 0x11 decode, the one
                    // the capture screen already shows. Other opcodes leave it untouched.
                    frame.bytes
                        ?.let { SteeringReading.fromFrame(it, frame.atMs) }
                        ?.let { _steeringAngle.value = it }
                }

                // v0.4.3: radio info sniff - capture every extra of either radio broadcast.
                ZXW_RADIO_INFO_EVT, RADIO_FREQUENCY_EVENT -> {
                    _radioInfoRaw.value = CanFrame.from(intent, System.currentTimeMillis())
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

    /**
     * v0.4.9 — deliver one unprotected-fallback key edge through the protected pipeline: same
     * canonical action, same dedupe, same [handleProtected], so the two sources cannot drift
     * apart in how a press is applied (and a rooted unit's duplicate copy is dropped).
     */
    private fun applyFallbackEdge(edge: SwcFallback.Edge) {
        val ints = SwcFallback.canonicalInts(edge)
        if (protectedDedupe.accept(STEER_WHEEL_INFOR, ints, System.currentTimeMillis())) {
            handleProtected(STEER_WHEEL_INFOR, ints)
        }
    }

    /**
     * v0.4.9 — an int extra whatever its carrier type. `HostKeyStatus` is documented as a BYTE
     * (CAR_API §1.3), and `Intent.getIntExtra` returns the default for a Byte value, which
     * would silently drop every event. Null when absent or non-numeric.
     */
    private fun intExtra(intent: Intent, key: String): Int? {
        @Suppress("DEPRECATION")
        val value = runCatching { intent.extras?.get(key) }.getOrNull()
        return (value as? Number)?.toInt()
    }

    /** v0.4.9 — snapshot every extra untyped, for decoders that must not assume key names. */
    private fun rawExtras(intent: Intent): Map<String, Any?> {
        val bundle = intent.extras ?: return emptyMap()
        val map = LinkedHashMap<String, Any?>()
        for (key in bundle.keySet()) {
            @Suppress("DEPRECATION")
            map[key] = runCatching { bundle.get(key) }.getOrNull()
        }
        return map
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
            addAction(MCU_MSG_CAN_ALL_INFO) // v0.4.3 (confirmed bulk frame on GT6-CAR)
            addAction(CAN_BASIC_EVT) // v0.4.3
            addAction(ZXW_RADIO_INFO_EVT) // v0.4.3 radio sniffer
            addAction(RADIO_FREQUENCY_EVENT) // v0.4.3
            addAction(CAN_TPMS_DATA_EVT) // v0.4.3 vehicle sniffer
            addAction(CAN_SEAT_DATA_EVT) // v0.4.3
            addAction(CAN_SLS_DATA_EVT) // v0.4.3
            addAction(CAN_FUEL_CONSUMPTION_INFOR) // v0.4.3
            addAction(CAN_CENTER_CONSOLE_INFOR) // v0.4.3
            addAction(CAN_CAR_TIRP_INFO) // v0.4.3
            addAction(CAR_AIR_STATE_ACTION)
            addAction(CAN_CAR_OUT_SIDE_TEMP_EVT) // v3.0
            addAction(ZXW_CAN_WHEEL_TRACK_EVT) // v3.0
            addAction(ZXW_ACTION_LAUNCHER_ALLAPPS_START_EVT) // v0.4.9 drawer request
            HBCP_CANDIDATE_ACTIONS.forEach { addAction(it) } // v0.4.9 vendor BT status
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
