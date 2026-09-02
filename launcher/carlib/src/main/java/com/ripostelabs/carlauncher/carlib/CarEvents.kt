package com.ripostelabs.carlauncher.carlib

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.szchoiceway.canbus.CarAirState
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

        // ---- ACC power (CAR_API §1.3) — unprotected (EventUtils.java:42,44) -----
        const val ACTION_ACC_OPEN_CLOSE_EVT =
            "com.szchoiceway.eventcenter.EventUtils.ACTION_ACC_OPEN_CLOSE_EVT"
        const val ACTION_ACC_SLEEP_STATUS_EVT =
            "com.szchoiceway.eventcenter.EventUtils.ACTION_ACC_SLEEP_STATUS_EVT"
        /** int extra: 1 = ACC on / awake, 0 = off / entering sleep. */
        const val EXTRA_ACC_STATUS = "ACC_Status"
        const val ACC_STATUS_ON = 1
        const val ACC_STATUS_SLEEP = 0

        // ---- Steering-wheel keys (CAR_API §4) — protected -------------------
        const val STEER_WHEEL_INFOR = "com.choiceway.eventcenter.EventUtils.STEER_WHEEL_INFOR"
        /**
         * int: learned SLOT + 1 (`bArr[1] + 1`, 1..10), NOT a CAR_KEY code. Which function the
         * slot means is the vendor learn app's map — see [WheelKeyMap].
         */
        const val EXTRA_SWC_LPARAM = "EventUtils.STEER_WHEEL_INFOR_LPARAM"
        /** int: 3 = pressed/down, 4 = released/up. */
        const val EXTRA_SWC_WPARAM = "EventUtils.STEER_WHEEL_INFOR_WPARAM"
        /** int: raw resistive-key ADC voltage. */
        const val EXTRA_SWC_VOLTAGE = "EventUtils.STEER_WHEEL_INFOR_VOLTAGE"

        /**
         * Every MCU-reported key, unprotected (EventUtils.java:1521,1633; sent :2147-2154).
         * The int is an `MCU_KEY_*` code, one broadcast per press — see [SwcFallback].
         */
        const val MCU_KEY_INFOR_ACTION = "com.szchoiceway.eventcenter.EventUtils.MCU_KEY_INFOR"
        const val EXTRA_MCU_KEY_VALUE = "EventUtils.MCU_KEY_VALUE"

        // ---- v0.4.9: vendor "open the app drawer" broadcast (CUSTOMERUI_NOTES §6) ----
        // The gateway broadcasts this (unprotected) to make the launcher open its in-process
        // drawer. The action is the BARE constant name, not an EventUtils.* string
        // (EventUtils.java:74), and the extra is "zxw_Launcher" (:1406, sent EventService.java:8225).
        const val ZXW_ACTION_LAUNCHER_ALLAPPS_START_EVT = "ZXW_ACTION_LAUNCHER_ALLAPPS_START_EVT"
        const val EXTRA_LAUNCHER = "zxw_Launcher"
        const val LAUNCHER_EXTRA_APPLIST = "AppList"

        // ---- Vendor BT module status (btsuite/BTUtils.java:88-105) -----------
        // `com.szchoiceway.btsuite` broadcasts its state unprotected as HBCP_EVT_* with two
        // fixed extras (DATA_INT / DATA_STR); the table is in VendorBtDecode. Only the actions
        // btsuite actually sends are registered (PAIR_STATUS, SEARCH, PINCODE, OBD never are).
        const val HBCP_ACTION_PREFIX = "com.szchoiceway.btsuite.HBCP_EVT_"
        val HBCP_ACTIONS = arrayOf(
            HBCP_ACTION_PREFIX + VendorBtDecode.EVT_POWER,
            HBCP_ACTION_PREFIX + VendorBtDecode.EVT_HSHF,
            HBCP_ACTION_PREFIX + VendorBtDecode.EVT_HSHF_GET,
            HBCP_ACTION_PREFIX + VendorBtDecode.EVT_DEVICE_NAME,
            HBCP_ACTION_PREFIX + VendorBtDecode.EVT_AV_STATUS,
            HBCP_ACTION_PREFIX + VendorBtDecode.EVT_CONTACT_NUM,
            HBCP_ACTION_PREFIX + VendorBtDecode.EVT_CONTACT_NAME,
            HBCP_ACTION_PREFIX + VendorBtDecode.EVT_SPEAKING_TIME,
        )

        // ---- Speed (CAR_API §1.3 note) --------------------------------------
        /**
         * NOTE: this is a *show/hide UI toggle only* — it carries NO speed value.
         * The numeric speed is not published as a clean extra (see [speedKmh] KDoc).
         */
        const val SHOW_CAR_SPEED_EVENT =
            "com.choiceway.eventcenter.EventUtils.SHOW_CAR_SPEED_EVENT"

        // ---- Day / night (CAR_API §1.3) — unprotected ----------------------------
        // ACTION_DAY/NIGHT_BACKLIGHT_CHAGNED are NOT illumination: they fire when the brightness
        // targets Set_Day_Light / Set_Night_Light change (EventService.java:4847-4853). The car's
        // headlamp state is LAMP_STATUS (no extras, :802) with the value in SysVar
        // Sys_LAMP_STAUS_CHECK "1"/"0" written just before (:2340). The gateway also announces
        // the system night mode it applied on uiModeNightChanged, boolean "mode" (:14089-14093).
        const val LAMP_STATUS = "com.szchoiceway.eventcenter.LAMP_STATUS"
        /** SysVar holding the headlamp state (SysProviderOpt.java:334). */
        private const val SYSVAR_LAMP_STATUS = "Sys_LAMP_STAUS_CHECK"
        private const val LAMP_ON = "1"
        const val UI_MODE_NIGHT_CHANGED = "com.szchoiceway.uiModeNightChanged"
        /** boolean extra of [UI_MODE_NIGHT_CHANGED]: true = night. */
        const val EXTRA_UI_MODE_NIGHT = "mode"

        // ---- Radar (CAR_API §1.3) — unprotected (CanUtils.java:195) --------------
        const val MCU_CAR_CAN_RADAR_INFO =
            "com.szchoiceway.eventcenter.EventUtils.MCU_CAR_CAN_RADAR_INFO"
        const val EXTRA_CAR_CAN_DATA = "EventUtils.CAR_CAN_DATA"

        // ---- v0.4.3: CAN frames (CAR_API §1.3) — unprotected ----------------------
        // Two different things. MCU_MSG_CAN_ALL_INFO is the gateway's raw MCU 0xA5 passthrough
        // (EventService.java:2060-2067) — the framed CANBOX stream HiworldCanDecoder reads.
        // MCU_CAR_CAN_INFO is canbus2's 3-byte digest [speed km/h, rpmH, rpmL]
        // (CanDataParseBase.java:1205-1208), under EXTRA_CAR_CAN_DATA. CAN_BASIC_EVT is never
        // sent by anyone (its receiver at EvtModel.java:522 is an empty return).
        const val MCU_MSG_CAN_ALL_INFO =
            "com.choiceway.eventcenter.EventUtils.MCU_MSG_CAN_ALL_INFO"
        const val MCU_CAR_CAN_INFO =
            "com.szchoiceway.eventcenter.EventUtils.MCU_CAR_CAN_INFO"
        /** Length of the MCU_CAR_CAN_INFO digest; byte[0] is the speed. */
        private const val CAN_INFO_LEN = 3
        private const val CAN_INFO_SPEED = 0

        // ---- Doors (CanDataParseBase.java:453-460) — unprotected ---------------------
        const val ACCORD_DOOR_INFO = "com.szchoiceway.eventcenter.EventUtils.ACCORD_DOOR_INFO"
        /** byte extra; bit layout in [DoorState]. */
        const val EXTRA_CAR_DOOR_DATA = "EventUtils.CAR_DOOR_DATA"

        // ---- Volume push (EventService.java:3105-3125) — unprotected -----------------
        const val MCU_MSG_MAIL_VOL = "com.choiceway.eventcenter.EventUtils.MCU_MSG_MAIL_VOL"
        /** int: (mute ? 0x80 : 0) | volume — see [VolumeReading]. */
        const val EXTRA_MAIL_VOL_VAL = "com.choiceway.eventcenter.EventUtils.MCU_MSG_MAIL_VOL_VAL"
        /** boolean: the vendor would show its volume window for this change. */
        const val EXTRA_SHOW_VOL_WND = "com.choiceway.eventcenter.EventUtils.MCU_MSG_SHOW_VOL_WND"

        // ---- v0.4.3: radio info sniffer (CAR_API line 113) — unprotected ----
        // Both radio broadcasts, captured raw. Actions are CONFIRMED consts; only the
        // action of ZXW_RADIO_INFO_EVT was recovered (payload never traced), so the sniffer
        // exists to discover what extras it and the frequency broadcast actually carry -- the
        // one route to a station name (README "Known TODOs").
        const val ZXW_RADIO_INFO_EVT =
            "com.choiceway.eventcenter.EventUtils.ZXW_RADIO_INFO_EVT"
        const val RADIO_FREQUENCY_EVENT = "com.szchoiceway.radio.frequency"

        // ---- v0.4.3: vehicle data sniffer — unprotected ------------------------------
        // Raw capture of the two cockpit broadcasts, kept so a car can re-check the decode.
        // CAN_TPMS/SEAT/SLS_DATA_EVT, CAN_FUEL_CONSUMPTION_INFOR, CAN_CENTER_CONSOLE_INFOR and
        // CAN_CAR_TIRP_INFO are constants nothing ever sends (EventUtils.java:186-201,
        // Camera360Receiver.java:13); TPMS and trip data stay inside canbus2's own EventBus.
        val VEHICLE_SNIFF_ACTIONS = arrayOf(CAN_CAR_OUT_SIDE_TEMP_EVT, ZXW_CAN_WHEEL_TRACK_EVT)

        // ---- Climate (CAR_API §1.3) — unprotected ---------------------------
        const val CAR_AIR_STATE_ACTION = "com.szchoiceway.canbus.carairstruct"
        /** Parcelable [CarAirState], mirrored in carlib (canbus2 `CanDataParseBase.java:473-486`). */
        const val EXTRA_CAR_AIR_STATE = "com.choiceway.canbus.carairstruct.airstate"

        // ---- v3.0: cockpit signals (CAR_API §1.3) — unprotected --------------
        /**
         * Outside temperature, sent by canbus2 as ONE String extra already carrying the car's
         * unit, e.g. "23℃" (`CanDataParseBase.java:1552-1555`, `CanUtils.java:14-15`). The int
         * `..._EXTRA` form is never put, so there is nothing to fall back to.
         */
        const val CAN_CAR_OUT_SIDE_TEMP_EVT =
            "com.choiceway.eventcenter.CanUtils.CAN_CAR_OUT_SIDE_TEMP_EVT"
        const val EXTRA_OUT_SIDE_TEMP_STR =
            "com.choiceway.eventcenter.CanUtils.CAN_CAR_OUT_SIDE_TEMP_EVT_EXTRA_STR"

        /**
         * Steering, the signal the vendor bends its reverse trajectory with. canbus2 sends an int
         * under [EXTRA_WHEEL_TRACK] (`CanDataParseBase.java:1316-1318`): bit7 = raw angle was
         * negative, bits 0-6 = |raw| / 14 — the same OEM scale as the 0x11 decode, never degrees.
         * Decoded by [SteeringReading.fromWheelTrack]; the 0x11 frame keeps priority while fresh
         * because it carries the un-truncated value.
         */
        const val ZXW_CAN_WHEEL_TRACK_EVT =
            "com.choiceway.eventcenter.EventUtils.ZXW_CAN_WHEEL_TRACK_EVT"
        const val EXTRA_WHEEL_TRACK =
            "com.choiceway.eventcenter.EventUtils.ZXW_CAN_WHEEL_TRACK_EVT_EXTRA"

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

        // ---- CAN speed (MCU_CAR_CAN_INFO byte[0]) ---------------------------------
        /**
         * How long one CAN digest stays a valid speed. canbus2 re-sends on every 0x32 frame, so
         * silence this long means the feed is gone, not that the car is stationary.
         */
        const val CAN_SPEED_STALE_MS = 5_000L

        /**
         * Whether a fresh CAN speed outranks GPS. The digest's speed byte is canbus2's
         * `iCarSpeed`, i.e. the 0x32 frame's p[4:5] (`HiworldCanParseToyota.java:413-415`), and
         * the 2026-08-29 drive capture showed that field does NOT track road speed on this car
         * (see [HiworldCanDecoder.SPEED_SCALE_KMH]). Until a steady-cruise capture proves it,
         * the reading is published on [canSpeedKmh] for diagnosis but does not feed the motion
         * gate: a wrong "0 km/h" there would unlock text entry in a moving car. Flip to true
         * once verified; [pickSpeed] and its test already cover both settings.
         */
        const val CAN_SPEED_TRUSTED = false

        /**
         * Speed arbitration: a fresh, trusted CAN reading wins, otherwise GPS. Pure, so the test
         * can pin the priority without a Context. [canKmh] < 0 means no CAN reading yet.
         */
        internal fun pickSpeed(
            canKmh: Int,
            canAgeMs: Long,
            gpsKmh: Int,
            trusted: Boolean = CAN_SPEED_TRUSTED,
        ): Pair<Int, SpeedSource> {
            val canFresh = canKmh >= 0 && canAgeMs < CAN_SPEED_STALE_MS
            if (trusted && canFresh) {
                return canKmh to SpeedSource.CAN
            }
            if (gpsKmh >= 0) {
                return gpsKmh to SpeedSource.GPS
            }
            return GpsSpeedSource.SPEED_UNKNOWN to SpeedSource.NONE
        }

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

    /**
     * A steering-wheel key event decoded from [STEER_WHEEL_INFOR].
     *
     * [keyIndex] lives in one of two spaces, and [space] says which: the resistive wheel
     * reports a learned slot + 1 ([WheelKeyMap.slotOfLparam]), while the unprotected panel
     * fallbacks ([SwcFallback]) carry `CAR_KEY_*` codes. The two overlap numerically, so a
     * consumer must branch on [space] before reading [keyIndex].
     */
    data class SwcKey(
        val keyIndex: Int,
        val down: Boolean,
        val voltage: Int,
        val space: KeySpace = KeySpace.LEARNED_SLOT,
    )

    /** Which number space an [SwcKey.keyIndex] is in. */
    enum class KeySpace {
        /** `STEER_WHEEL_INFOR` LPARAM: learned slot + 1, resolved through [WheelKeyMap]. */
        LEARNED_SLOT,
        /** `CAR_KEY_*` (CAR_API §4) from the host/panel fallback broadcasts. */
        CAR_KEY,
    }

    /**
     * v0.4.9 — one `ACTION_ACC_SLEEP_STATUS_EVT` arrival (CAR_API §1.3).
     *
     * `ACC_Status` is 1 = awake / ACC on (`EventService.java:492,2274`, MCU wake frame 0x96) and
     * 0 = entering sleep (`:3535`, sent together with `EVENT_DISCONNECT_BT`).
     */
    data class AccSleep(
        /** Raw `ACC_Status` int, or null when the extra was absent. */
        val rawStatus: Int?,
        val atMs: Long,
    ) {
        /** True when the unit is going to sleep; null when the extra was absent. */
        val sleeping: Boolean? get() = rawStatus?.let { it == ACC_STATUS_SLEEP }
    }

    /**
     * v2.5 — stationary / in motion / unreadable, derived from [speedKmh].
     *
     * [UNKNOWN] is an ordinary state rather than an error: no location permission, a cold GPS,
     * a tunnel, any underground car park. What it should *mean* is the consumer's decision —
     * see the fail-open note on [motion].
     */
    enum class Motion { UNKNOWN, PARKED, MOVING }

    /** Where the current [speedKmh] came from. */
    enum class SpeedSource { NONE, GPS, CAN }

    /** Day/night illumination, from the headlamps ([LAMP_STATUS] + `Sys_LAMP_STAUS_CHECK`). */
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
     * v2.7 — true once a headlamp broadcast ([LAMP_STATUS]) has actually arrived.
     *
     * [dayNight] cannot answer this. It starts at [DayNight.DAY] and stays there both when the car
     * really is reporting daylight and when the broadcast never arrives at all. LAMP_STATUS is
     * unprotected, but the gateway only sends it on a headlamp CHANGE (`EventService.java:2334`),
     * so a unit whose lamps never toggle in a session hears nothing.
     *
     * The launcher's clock-based day/night fallback keys off this flag: a unit that is genuinely
     * hearing the car keeps following the car, and only a silent one falls back to the clock.
     * Latched, never cleared — one broadcast proves the signal exists for the rest of the session.
     */
    val illuminationSeen: StateFlow<Boolean> = _illuminationSeen.asStateFlow()

    private val _gatewayNight = MutableStateFlow<Boolean?>(null)
    /**
     * The system night mode the gateway last applied ([UI_MODE_NIGHT_CHANGED]), or null until it
     * says. Diagnostic only, never a theming source: the gateway applies whatever
     * [GatewayHandshake.sendUiMode] told it, so reading this back as "the car's illumination"
     * would latch our own clock-fallback decision as if the car had made it.
     */
    val gatewayNight: StateFlow<Boolean?> = _gatewayNight.asStateFlow()

    private val _accSleep = MutableStateFlow<AccSleep?>(null)
    /** v0.4.9 — latest `ACTION_ACC_SLEEP_STATUS_EVT`, or null until one arrives. */
    val accSleep: StateFlow<AccSleep?> = _accSleep.asStateFlow()

    private val _doors = MutableStateFlow<DoorState?>(null)
    /** Latest `ACCORD_DOOR_INFO` decode, or null until canbus2 reports a door. */
    val doors: StateFlow<DoorState?> = _doors.asStateFlow()

    private val _volume = MutableStateFlow<VolumeReading?>(null)
    /**
     * Main volume as last pushed by the gateway on [MCU_MSG_MAIL_VOL], or null until the MCU
     * reports one. Arrives on every volume or mute change, so consumers need not poll.
     */
    val volume: StateFlow<VolumeReading?> = _volume.asStateFlow()

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
     * Bluetooth status per the vendor bt module's `HBCP_EVT_*` broadcasts (contract in
     * [VendorBtDecode]). Starts as the all-null [VendorBtState]; consumers must gate on
     * [VendorBtState.lastEventMs] so absence of events changes nothing.
     */
    val vendorBt: StateFlow<VendorBtState> = _vendorBt.asStateFlow()

    private val _carPlay = MutableStateFlow(CarPlayState())
    /**
     * RAV4-52 — the Zlink phone-projection session (contract in [CarPlayState]). Starts
     * disconnected; consumers gate on [CarPlayState.lastEventMs] so silence shows nothing.
     */
    val carplayState: StateFlow<CarPlayState> = _carPlay.asStateFlow()

    private val _climate = MutableStateFlow<ClimateState?>(null)
    /**
     * Latest HVAC snapshot from the `carairstruct` broadcast, or null until the CAN app sends
     * one. Unparcelled through the mirrored [CarAirState]; there is no other read path
     * (`getAirData()` is a stub, see [ClimateState]).
     */
    val climate: StateFlow<ClimateState?> = _climate.asStateFlow()

    // v0.7 --- Parking radar (CAR_API §1.3 MCU_CAR_CAN_RADAR_INFO) ------------
    private val _radar = MutableStateFlow<RadarState?>(null)
    /**
     * Latest parking-sensor frame decoded from `MCU_CAR_CAN_RADAR_INFO` (byte[] CAR_CAN_DATA),
     * or null until the first frame arrives. Unprotected, sent by canbus2 only while
     * `Sys_Plugin_radar_Set` is 0. Layout and scale in [RadarState]; sensor order UNVERIFIED.
     */
    val radar: StateFlow<RadarState?> = _radar.asStateFlow()

    // v2.8 --- Undecoded radar payload, for the capture view -------------------
    private val _radarRaw = MutableStateFlow<RadarFrame?>(null)
    /**
     * v2.8 — the raw `CAR_CAN_DATA` payload of the last radar broadcast, undecoded.
     *
     * Published even when [RadarState.fromRadarData] rejects the frame, so the capture screen
     * can check the left→right order the decompile leaves open.
     */
    val radarRaw: StateFlow<RadarFrame?> = _radarRaw.asStateFlow()

    // v0.4.3 --- Undecoded CAN bulk frame, for the capture view ----------------
    private val _canRaw = MutableStateFlow<CanFrame?>(null)
    /**
     * v0.4.3 — the last MCU_MSG_CAN_ALL_INFO / MCU_CAR_CAN_INFO broadcast, with every extra
     * captured and any byte[] payload kept undecoded. Null until a frame arrives.
     */
    val canRaw: StateFlow<CanFrame?> = _canRaw.asStateFlow()

    // --- Wheel-key gestures off the 0x11 frames --------------------------------
    private val _wheelGestures = MutableSharedFlow<WheelGesture>(extraBufferCapacity = 16)
    /**
     * Hold / double-press of the RAV4 wheel keys, decoded from the raw 0x11 frames in
     * [MCU_MSG_CAN_ALL_INFO] ([WheelGestures]). The vendor path reports a key once, on release,
     * so this is the only place a hold is visible. Silent without the raw-frame broadcast.
     */
    val wheelGestures: SharedFlow<WheelGesture> = _wheelGestures.asSharedFlow()

    /**
     * Long presses arm this so the vendor's plain key for the same button — the release the CAN
     * app reports after the hold — is dropped on both delivery paths ([WheelKeySwallow]). The
     * broadcast path is filtered here; MainActivity asks [swallowKeyEvent] for the injected one.
     */
    private val keySwallow = WheelKeySwallow()

    private val gestures = WheelGestures { gesture ->
        if (gesture is WheelGesture.LongPress) {
            keySwallow.arm(gesture.key, SystemClock.elapsedRealtime())
        }
        _wheelGestures.tryEmit(gesture)
    }

    /** Fires at [WheelGestures.nextDeadlineMs]: a frame gap is a release, a long hold a gesture. */
    private val gestureTick = Runnable {
        gestures.onTick(SystemClock.elapsedRealtime())
        scheduleGestureTick()
    }

    /**
     * True when an injected KeyEvent [edge] for [key] is the vendor echo of a long press we
     * already acted on; the caller drops it. Main thread only, like the receiver.
     */
    fun swallowKeyEvent(key: WheelKey, edge: WheelKeySwallow.Edge): Boolean =
        keySwallow.swallowKeyEvent(key, edge, SystemClock.elapsedRealtime())

    // --- Zlink (CarPlay / Android Auto) session -------------------------------
    private val _zlinkConnected = MutableStateFlow(false)
    /**
     * True between a `CONNECTED` and a `DISCONNECT` / `EXIT` on Zlink's own status broadcast
     * (`Zlink.ACTION_MESSAGE`, `ZlinkManage.java:205-300`). UNVERIFIED: the receiver's DEX is
     * packed, so the vocabulary is the gateway's reading of it. False until the first status.
     */
    val zlinkConnected: StateFlow<Boolean> = _zlinkConnected.asStateFlow()

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
     * undecoded. Empty until a frame of a given action arrives.
     */
    val vehicleSniff: StateFlow<Map<String, CanFrame>> = _vehicleSniff.asStateFlow()

    /**
     * Numeric speed in km/h, or [GpsSpeedSource.SPEED_UNKNOWN] when it cannot be read.
     *
     * Two sources feed it through [pickSpeed]: canbus2's [MCU_CAR_CAN_INFO] digest (available at
     * power-on and indoors, where GPS is not) and [GpsSpeedSource]. CAN outranks GPS while fresh
     * and [CAN_SPEED_TRUSTED] — which it is not yet, see that constant. [speedSource] says which
     * one is showing.
     */
    private val _speedKmh = MutableStateFlow(GpsSpeedSource.SPEED_UNKNOWN)
    val speedKmh: StateFlow<Int> = _speedKmh.asStateFlow()

    private val _speedSource = MutableStateFlow(SpeedSource.NONE)
    /** Which source [speedKmh] currently reflects. */
    val speedSource: StateFlow<SpeedSource> = _speedSource.asStateFlow()

    private val _canSpeedKmh = MutableStateFlow(GpsSpeedSource.SPEED_UNKNOWN)
    /**
     * The raw CAN digest speed (byte[0] of [MCU_CAR_CAN_INFO]), stale-cleared after
     * [CAN_SPEED_STALE_MS]. Published regardless of [CAN_SPEED_TRUSTED] so a drive can compare
     * it against GPS.
     */
    val canSpeedKmh: StateFlow<Int> = _canSpeedKmh.asStateFlow()

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
     * Outside temperature as the car renders it (e.g. "23℃"), or null until a frame arrives.
     * A String on purpose: canbus2 formats it with the car's own unit, so the dashboard always
     * agrees with the cluster.
     */
    val outsideTemp: StateFlow<String?> = _outsideTemp.asStateFlow()

    private val _steeringAngle = MutableStateFlow<SteeringReading?>(null)
    /**
     * Steering on the OEM raw/14 scale, or null until a frame arrives. Fed by the 0x11 Basic
     * Status decode of [MCU_MSG_CAN_ALL_INFO] (full precision) and, while that is stale, by the
     * truncated [ZXW_CAN_WHEEL_TRACK_EVT] int. Which side a negative value means is UNVERIFIED —
     * turn lock to lock on-device to settle it. Consumers must apply [SteeringReading.isStale]
     * so a dead feed never reads as a held wheel.
     */
    val steeringAngle: StateFlow<SteeringReading?> = _steeringAngle.asStateFlow()

    /** Latest GPS speed, kept so a CAN dropout can fall back to it. */
    private var gpsKmh = GpsSpeedSource.SPEED_UNKNOWN

    /** `System.currentTimeMillis()` of the last CAN digest; 0 = none yet. */
    private var canSpeedAtMs = 0L

    private val handler = Handler(Looper.getMainLooper())

    /** Fires when CAN digests stop arriving: the CAN reading is unknown, not frozen. */
    private val canSpeedStale = Runnable {
        _canSpeedKmh.value = GpsSpeedSource.SPEED_UNKNOWN
        republishSpeed()
    }

    /**
     * v2.5 — GPS speed. Owned here so [speedKmh] has one front door regardless of which
     * underlying source fills it, matching how [reverse] and [dayNight] hide their broadcasts.
     */
    private val gpsSource = GpsSpeedSource(appContext) { kmh ->
        gpsKmh = kmh
        republishSpeed()
    }

    /** Headlamp state lives in a SysVar; [LAMP_STATUS] only says it changed. */
    private val sysVar = SysVar(appContext)

    /**
     * v0.4.3.8 — one arrival slot both protected-event paths pass through, so a frame that really
     * is delivered twice is applied once. See [ProtectedEventDedupe] for why the source flag it
     * replaces could not do this.
     */
    private val protectedDedupe = ProtectedEventDedupe()

    /**
     * v2.9 — root capture of the `signature`-guarded broadcasts. Owned here for the same reason
     * [gpsSource] is: consumers keep one front door and never learn which source filled a flow.
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
         * (e.g. `com.szchoiceway.canbus.CarAirState`) to `carairstruct`, and those classes are
         * deliberately not bundled into this APK, so the lazy
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
                // Vendor BT module status (HBCP_ACTIONS); decoded by VendorBtDecode.
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
                    val on = intent.getIntExtra(EXTRA_ACC_STATUS, ACC_STATUS_ON) == ACC_STATUS_ON
                    _accOn.value = on
                    listeners.forEach { it.onAcc(on) }
                }

                ACTION_ACC_SLEEP_STATUS_EVT -> {
                    val raw = intent.getIntExtra(EXTRA_ACC_STATUS, VALUE_UNKNOWN)
                    _accSleep.value = AccSleep(
                        rawStatus = raw.takeIf { it != VALUE_UNKNOWN },
                        atMs = System.currentTimeMillis(),
                    )
                }

                // v0.4.9: the gateway's "open the app drawer" request (CUSTOMERUI_NOTES §6).
                // The payload is zxw_Launcher="AppList"; a missing extra still opens (the
                // action itself is the request), but an extra naming something OTHER than the
                // app list is a request we don't understand — ignore it.
                ZXW_ACTION_LAUNCHER_ALLAPPS_START_EVT -> {
                    val what = intent.getStringExtra(EXTRA_LAUNCHER)
                    if (what == null || what == LAUNCHER_EXTRA_APPLIST) {
                        _openAppList.tryEmit(Unit)
                    }
                }

                // v3.0: outside temperature, preformatted by canbus2 with the car's own unit.
                CAN_CAR_OUT_SIDE_TEMP_EVT -> {
                    val text = intent.getStringExtra(EXTRA_OUT_SIDE_TEMP_STR)
                    if (!text.isNullOrBlank()) _outsideTemp.value = text
                }

                // Truncated steering from canbus2; only stands in while the 0x11 decode is stale.
                ZXW_CAN_WHEEL_TRACK_EVT -> {
                    val raw = intExtra(intent, EXTRA_WHEEL_TRACK) ?: return
                    val now = System.currentTimeMillis()
                    if (_steeringAngle.value?.isStale(now) != false) {
                        _steeringAngle.value = SteeringReading.fromWheelTrack(raw, now)
                    }
                }

                // Headlamps changed; the state itself is in the SysVar the gateway wrote first.
                LAMP_STATUS -> {
                    val on = sysVar.getString(SYSVAR_LAMP_STATUS) == LAMP_ON
                    updateDayNight(if (on) DayNight.NIGHT else DayNight.DAY)
                }

                // The gateway's applied system night mode. Recorded, not themed from (see
                // gatewayNight): it echoes what GatewayHandshake told it.
                UI_MODE_NIGHT_CHANGED -> {
                    _gatewayNight.value = intent.getBooleanExtra(EXTRA_UI_MODE_NIGHT, false)
                }

                ACCORD_DOOR_INFO -> {
                    val raw = intExtra(intent, EXTRA_CAR_DOOR_DATA) ?: return
                    _doors.value = DoorState.fromByte(raw and 0xFF, System.currentTimeMillis())
                }

                MCU_MSG_MAIL_VOL -> {
                    val raw = intExtra(intent, EXTRA_MAIL_VOL_VAL) ?: return
                    _volume.value = VolumeReading.fromMailVol(
                        raw,
                        intent.getBooleanExtra(EXTRA_SHOW_VOL_WND, false),
                        System.currentTimeMillis(),
                    )
                }

                // v2.9: the protected set. Still filtered for here, because a privileged/system
                // install DOES receive them directly and must not need the root helper. On an
                // install where both paths deliver, the same event arrives twice and a duplicate
                // SwcKey is a second key press as far as the focus ring is concerned.
                // v0.4.3.8: that duplicate is now rejected on the *event*, not on the source flag —
                // reading _rootCapture here was a check-then-act across two threads (the flag is
                // published from the root reader thread while this test runs on the main thread),
                // so a frame arriving in that window was handled twice anyway.
                ACTION_BACKCAR_START, ACTION_BACKCAR_END, STEER_WHEEL_INFOR -> {
                    val ints = swcIntExtras(intent)
                    // v0.4.9: deduped on the stable subset so the unprotected fallback
                    // carriers (no voltage extra) match too — see swcDedupeInts.
                    val key = swcDedupeInts(action, ints)
                    if (protectedDedupe.accept(action, key, System.currentTimeMillis())) {
                        handleProtected(action, ints)
                    }
                }

                // v0.4.9: the UNPROTECTED key path (CAR_API §4 path 2) — the only wheel input a
                // non-root install gets. Decoded by SwcFallback, normalised to the canonical
                // STEER_WHEEL_INFOR form, and pushed through the SAME dedupe: on a rooted unit
                // the protected capture delivers the same press, and exactly one of the
                // co-arriving copies may reach handleProtected.
                MCU_KEY_INFOR_ACTION -> {
                    // The echo of a long press we already acted on is dropped here, before the
                    // tap is synthesised (see keySwallow).
                    val code = intExtra(intent, EXTRA_MCU_KEY_VALUE)
                    val wheelKey = WheelKey.fromMcuKey(code)
                    if (wheelKey != null &&
                        keySwallow.swallowBroadcast(wheelKey, SystemClock.elapsedRealtime())
                    ) {
                        return
                    }

                    // No press state on this path: one broadcast is one complete tap, so
                    // synthesise the down and up edges back to back. KeyPump treats that as a
                    // normal short press on every key class.
                    val index = SwcFallback.mcuKey(code)
                    if (index != null) {
                        applyFallbackEdge(SwcFallback.Edge(index, down = true))
                        applyFallbackEdge(SwcFallback.Edge(index, down = false))
                    }
                }

                // v0.7: parking-radar frame → distance codes → proximity bands (RadarState).
                MCU_CAR_CAN_RADAR_INFO -> {
                    val bytes = intent.getByteArrayExtra(EXTRA_CAR_CAN_DATA)
                    // v2.8: keep the payload before it is interpreted (see radarRaw).
                    if (bytes != null) {
                        _radarRaw.value = RadarFrame(bytes, System.currentTimeMillis())
                    }
                    val rs = RadarState.fromRadarData(bytes)
                    if (rs.valid) _radar.value = rs
                }

                // v0.4.3: raw MCU passthrough — capture every extra + payload, undecoded.
                MCU_MSG_CAN_ALL_INFO -> {
                    val frame = CanFrame.from(intent, System.currentTimeMillis())
                    _canRaw.value = frame

                    // RAV4-38: the dashboard's steering is this frame's 0x11 decode, the one
                    // the capture screen already shows. Other opcodes leave it untouched.
                    // The same decode carries the wheel key byte the gesture engine reads.
                    val basic = frame.bytes
                        ?.let { HiworldCanDecoder.decodeFrame(it) as? CanSignal.BasicStatus }
                        ?: return
                    _steeringAngle.value = SteeringReading(basic.steerAngleDeg, frame.atMs)
                    gestures.onSample(basic.swcButtonId, basic.swcPressed, SystemClock.elapsedRealtime())
                    scheduleGestureTick()
                }

                // Zlink's own session status; only the connect/disconnect words are read.
                Zlink.ACTION_MESSAGE -> {
                    when (intent.getStringExtra(Zlink.EXTRA_STATUS)) {
                        Zlink.STATUS_CONNECTED -> _zlinkConnected.value = true
                        Zlink.STATUS_DISCONNECT, Zlink.STATUS_EXIT -> _zlinkConnected.value = false
                    }
                }

                // canbus2's 3-byte digest: [speed km/h, rpmH, rpmL].
                MCU_CAR_CAN_INFO -> {
                    val frame = CanFrame.from(intent, System.currentTimeMillis())
                    _canRaw.value = frame
                    val bytes = frame.bytes ?: return
                    if (bytes.size < CAN_INFO_LEN) {
                        return
                    }
                    updateCanSpeed(bytes[CAN_INFO_SPEED].toInt() and 0xFF, frame.atMs)
                }

                // v0.4.3: radio info sniff - capture every extra of either radio broadcast.
                ZXW_RADIO_INFO_EVT, RADIO_FREQUENCY_EVENT -> {
                    _radioInfoRaw.value = CanFrame.from(intent, System.currentTimeMillis())
                }

                CAR_AIR_STATE_ACTION -> {
                    // The extra unparcels into our CarAirState mirror because the class name
                    // and field order match the vendor's. A missing extra leaves the flow as-is.
                    intent.getParcelableExtra(EXTRA_CAR_AIR_STATE, CarAirState::class.java)
                        ?.let { _climate.value = ClimateState.from(it) }
                }

                // RAV4-52: Zlink session status. The same action also carries gateway → zlink
                // commands (no `status` extra, our own REQ_SPEC_FUNC_CMD included): skipped.
                Zlink.ACTION_MESSAGE -> {
                    val status = intent.getStringExtra(Zlink.EXTRA_STATUS) ?: return
                    _carPlay.value = CarPlayDecode.applyStatus(
                        _carPlay.value,
                        status,
                        intent.getStringExtra(Zlink.EXTRA_PHONE_MODE),
                        System.currentTimeMillis(),
                    )
                }

                Zlink.ACTION_TELEPHONE_STATUS -> {
                    val raw = intExtra(intent, Zlink.EXTRA_TELEPHONE_STATUS) ?: return
                    _carPlay.value = CarPlayDecode.applyTelephone(
                        _carPlay.value, raw, System.currentTimeMillis(),
                    )
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
    private fun handleProtected(
        action: String,
        ints: Map<String, Int>,
        space: KeySpace = KeySpace.LEARNED_SLOT,
    ) {
        when (action) {
            ACTION_BACKCAR_START -> updateReverse(true)
            ACTION_BACKCAR_END -> updateReverse(false)

            STEER_WHEEL_INFOR -> {
                val key = SwcKey(
                    keyIndex = ints[EXTRA_SWC_LPARAM] ?: 0,
                    down = (ints[EXTRA_SWC_WPARAM] ?: 0) == SWC_STATE_DOWN,
                    voltage = ints[EXTRA_SWC_VOLTAGE] ?: 0,
                    space = space,
                )
                _swcKeys.tryEmit(key)
                listeners.forEach { it.onSwcKey(key) }
            }
        }
    }

    /** Re-arm the one gesture timer for the engine's next deadline, or clear it when idle. */
    private fun scheduleGestureTick() {
        handler.removeCallbacks(gestureTick)
        val deadline = gestures.nextDeadlineMs() ?: return
        handler.postDelayed(gestureTick, (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
    }

    /**
     * v0.4.9 — deliver one unprotected-fallback key edge through the protected pipeline: same
     * canonical action, same dedupe, same [handleProtected], so the two sources cannot drift
     * apart in how a press is applied (and a rooted unit's duplicate copy is dropped).
     */
    private fun applyFallbackEdge(edge: SwcFallback.Edge) {
        val ints = SwcFallback.canonicalInts(edge)
        if (protectedDedupe.accept(STEER_WHEEL_INFOR, ints, System.currentTimeMillis())) {
            // The fallbacks carry CAR_KEY codes, not learned slots (see KeySpace).
            handleProtected(STEER_WHEEL_INFOR, ints, KeySpace.CAR_KEY)
        }
    }

    /**
     * v0.4.9 — an int extra whatever its carrier type. `CAR_DOOR_DATA` is a BYTE, and
     * `Intent.getIntExtra` returns the default for a Byte value, which would silently drop
     * every event. Null when absent or non-numeric.
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

    /** One CAN digest arrived: record it, re-arm the staleness timer, re-arbitrate. */
    private fun updateCanSpeed(kmh: Int, atMs: Long) {
        _canSpeedKmh.value = kmh
        canSpeedAtMs = atMs
        handler.removeCallbacks(canSpeedStale)
        handler.postDelayed(canSpeedStale, CAN_SPEED_STALE_MS)
        republishSpeed()
    }

    /** v2.5 — pick the speed source, publish it and re-derive [motion] from it. */
    private fun republishSpeed() {
        val canAge = System.currentTimeMillis() - canSpeedAtMs
        val (kmh, source) = pickSpeed(_canSpeedKmh.value, canAge, gpsKmh)
        _speedSource.value = source
        _speedKmh.value = kmh
        _motion.value = nextMotion(_motion.value, kmh)
    }

    /**
     * Register the receiver. Safe to call once; a second call is a no-op.
     *
     * We register for BOTH the protected (`ACTION_BACKCAR_*`, `STEER_WHEEL_INFOR`) and the
     * unprotected (`MCU_MSG_BACKCAR_*`, ACC, headlamps, keys, CAN) actions in a single
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
            addAction(MCU_KEY_INFOR_ACTION)
            addAction(LAMP_STATUS)
            addAction(UI_MODE_NIGHT_CHANGED)
            addAction(ACCORD_DOOR_INFO)
            addAction(MCU_MSG_MAIL_VOL)
            addAction(MCU_CAR_CAN_RADAR_INFO)
            addAction(MCU_CAR_CAN_INFO) // canbus2 speed/RPM digest
            addAction(MCU_MSG_CAN_ALL_INFO) // v0.4.3 raw MCU passthrough (steering decode)
            addAction(ZXW_RADIO_INFO_EVT) // v0.4.3 radio sniffer
            addAction(RADIO_FREQUENCY_EVENT) // v0.4.3
            addAction(CAR_AIR_STATE_ACTION)
            addAction(CAN_CAR_OUT_SIDE_TEMP_EVT) // v3.0
            addAction(ZXW_CAN_WHEEL_TRACK_EVT) // v3.0
            addAction(ZXW_ACTION_LAUNCHER_ALLAPPS_START_EVT) // v0.4.9 drawer request
            addAction(Zlink.ACTION_MESSAGE) // CarPlay session status (wheel NAV gesture)
            HBCP_ACTIONS.forEach { addAction(it) } // vendor BT status
            addAction(Zlink.ACTION_MESSAGE) // RAV4-52 CarPlay session status
            addAction(Zlink.ACTION_TELEPHONE_STATUS)
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
        gpsSource.start()
        rootBridge.start() // v2.9
        Log.i(TAG, "CarEvents registered")
    }

    fun unregister() {
        if (!registered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        gpsSource.stop() // v2.5
        handler.removeCallbacks(canSpeedStale)
        rootBridge.stop() // v2.9
        registered = false
    }

    /**
     * v2.5 — retry the GPS speed source. Idempotent, and needed because [register] runs before
     * the user has answered the location prompt: the first attempt finds no permission and gives
     * up, so the grant callback calls this to actually start listening.
     */
    fun startSpeedSource() = gpsSource.start()

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
