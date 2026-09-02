package com.ripostelabs.carlauncher.carlib

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.szchoiceway.eventcenter.ICommunication
import com.szchoiceway.eventcenter.IEventService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * CarService — binds the vendor control service (CAR_API §3.1) and exposes a thin,
 * null-safe Kotlin wrapper over the [IEventService] AIDL.
 *
 * Bind target (CAR_API §3.1 / §7):
 *   action  = "com.szchoiceway.eventcenter.EventService"
 *   package = "com.szchoiceway.eventcenter"
 *   service is exported=true, so a normal app can bind. Read-only getters are expected
 *   to work; control side-effects "work best as a system app".
 *
 * ⚠ DESCRIPTOR / ORDINAL CAVEAT: our reconstructed IEventService.aidl declares only a
 * subset of methods and its transaction ordinals almost certainly DO NOT match the real
 * service (see the TODO header in IEventService.aidl). Binding + asInterface() succeed
 * regardless, but any transact() may reach the wrong server method until the AIDL is
 * regenerated from the real decompiled IEventService.java (preserving method order).
 * Guard every call and treat results as unverified.
 */
class CarService(private val appContext: Context) {

    companion object {
        private const val TAG = "CarService"
        const val BIND_ACTION = "com.szchoiceway.eventcenter.EventService"
        const val BIND_PACKAGE = "com.szchoiceway.eventcenter"

        // ---- Radio key codes for sendRadioKey(int) --------------------------
        // ⚠ GUESSED: the concrete CAR_RADIO_KEY_* opcodes were NOT recovered from the
        // decompile (CAR_API §3.2 lists sendRadioKey(int) but not its value table). These
        // are best-effort guesses following the common vendor convention
        // (0=band toggle, 1=seek-down, 2=seek-up). Verify on-device.
        const val RADIO_KEY_BAND = 0
        const val RADIO_KEY_SEEK_DOWN = 1
        const val RADIO_KEY_SEEK_UP = 2

        /**
         * Main-volume range for QuickControls. No longer a guess: the vendor's own volume UI
         * (EventCenter `BackcarEvent`, decompiled in mcu-analysis/eventcenter-src) sizes its
         * seek bar as — MCU-reported max if it is > 0, else 15 when the BT launch sound is on,
         * else **40**. We cannot see the MCU-reported value from here, so we take the vendor's
         * own fallback rather than a number nobody chose.
         *
         * This was 30, which was invented. That under-reported the top of the scale by a
         * quarter: the slider could not reach the car's real maximum, and every position it
         * showed mapped to a louder level than it claimed.
         */
        const val MAX_VOLUME = 40

        /** Radio band ordinal → true when it's an AM band (getRadioBand()). GUESSED split. */
        fun isAmBand(band: Int): Boolean = band >= 3
    }

    private val _connected = MutableStateFlow(false)
    /** true while the AIDL binder is live. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile
    private var service: IEventService? = null

    /**
     * v0.4.7 — connection callbacks run here, not on the main thread: onServiceConnected makes a
     * non-oneway transaction to the vendor gateway (addMessageListener), and at boot contention
     * that block on the UI thread is an ANR in HOME.
     */
    private val connectionExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "car-service-conn")
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IEventService.Stub.asInterface(binder)
            _connected.value = service != null
            Log.i(TAG, "EventService connected: $name")
            // Register our callback listener (best-effort; ordinal caveat applies).
            runCatching { service?.addMessageListener(messageListener) }
                .onFailure { Log.w(TAG, "addMessageListener failed", it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            _connected.value = false
            Log.w(TAG, "EventService disconnected: $name")
        }
    }

    /** Callback the gateway pushes text status lines into (CAR_API §3.3 protocol). */
    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()

    private val messageListener = object : ICommunication.Stub() {
        override fun notifyMessage(message: String?) {
            _messages.value = message
            Log.d(TAG, "gateway msg: $message")
        }

        override fun checkIsActive(): Boolean = true
    }

    /** Bind the service. Idempotent-ish; returns false if the bind request was rejected. */
    fun bind(): Boolean {
        val intent = Intent(BIND_ACTION).apply { setPackage(BIND_PACKAGE) }
        return try {
            // 4-arg overload: callbacks land on [connectionExecutor] instead of the main thread.
            val ok = appContext.bindService(
                intent,
                Context.BIND_AUTO_CREATE,
                connectionExecutor,
                connection,
            )
            if (!ok) Log.w(TAG, "bindService returned false (service not found?)")
            ok
        } catch (t: Throwable) {
            Log.e(TAG, "bind failed", t)
            false
        }
    }

    fun unbind() {
        runCatching { appContext.unbindService(connection) }
        service = null
        _connected.value = false
    }

    // ---- Thin, guarded convenience wrappers --------------------------------
    // Each returns null / false when unbound or on RemoteException. Remember the
    // ordinal caveat above: values are unverified until the AIDL is corrected.

    fun getValidMode(): Int? = call { getValidMode() }
    fun isBackCarConnected(): Boolean = call { IsBackCarConneted() } ?: false
    fun getRadioFreq(): Int? = call { getRadioFreq() }
    fun getRadioBand(): Int? = call { getRadioBand() }
    fun getMainVolume(): Int? = call { getMainVolval().toInt() }
    fun isMuteOn(): Boolean = call { IsMuteOn() } ?: false
    fun getMcuVer(): String? = call { getMCUVer() }

    // ---- v2.6: vendor source identity (CAR_API §3.2) -----------------------
    /**
     * The vendor's current source title (getValidModeTitleInfor, ordinal 63) — "Bluetooth",
     * "USB", the built-in player, etc.
     *
     * Read-only on purpose. Switching sources is `sendMode(int, boolean)` (ordinal 1, confirmed),
     * but the *value table* for that int appears nowhere in the decompile, so MediaScreen shows
     * the vendor's source rather than offering to change it: sending an unverified opcode would
     * put the head unit into an unknown mode with no way to predict which.
     */
    fun getValidModeTitle(): String? = call { getValidModeTitleInfor() }

    // ---- v2.0: System / About (CAR_API §3.2) -------------------------------
    /** MCU firmware version (getMCUVer, ordinal 32). */
    fun getMcuVersion(): String? = call { getMCUVer() }
    /** CANBOX firmware version (getCanVer, ordinal 105). */
    fun getCanVersion(): String? = call { getCanVer() }
    /** Soft reboot the head unit (sendSoftWareReboot, ordinal 135). */
    fun reboot() { call { sendSoftWareReboot() } }
    /** Vendor factory reset (sendFactorySet, ordinal 76). ⚠ Destructive — confirm before calling. */
    fun factoryReset() { call { sendFactorySet() } }

    /** Read the raw HVAC frame (CAR_API §5). ⚠ Param semantics GUESSED (0 = query). */
    fun getAirData(): ByteArray? = call { getAirData(0, ByteArray(64)) }

    fun sendMode(mode: Int, flag: Boolean) { call { sendMode(mode, flag) } }
    fun sendWheelKey(key: Int) { call { sendWheelKey(key) } }
    fun setMute(mute: Boolean) { call { sendMuteState(mute) } }

    // ---- v0.6: volume set (QuickControls) ----------------------------------
    /**
     * v0.6 — set the absolute main volume.
     *
     * No longer guessed. Checked against the decompiled vendor service
     * (mcu-analysis/eventcenter-src): `sendVolState(boolean, int)` really is transaction **77**
     * (`IEventService.TRANSACTION_sendVolState`), and the vendor calls it as a setter in exactly
     * this argument order — `eventService.sendVolState(eventService.IsMuteOn(), level)` in
     * `VoiceCtrlModel`, `sendVolState(mMuteState, mSysVolume)` in `BackcarEvent`. Both the
     * ordinal and the (isMuted, level) reading were correct; only the range was wrong, see
     * [MAX_VOLUME]. Still guarded like the rest, so a vendor that disagrees is a no-op.
     */
    fun setVolume(level: Int) {
        val clamped = level.coerceIn(0, MAX_VOLUME)
        // sendVolState carries the mute flag too, so we must preserve the real mute state.
        // A failed/errored IsMuteOn() read must NOT be coerced to `false` — that would silently
        // unmute the car as a side effect of a volume change. Bail if the state is unknown.
        val muted = call { IsMuteOn() } ?: return
        call { sendVolState(muted, clamped) }
    }

    // ---- Radio control (CAR_API §3.2). All guarded; ⚠ key codes GUESSED. -----
    fun sendRadioKey(key: Int) { call { sendRadioKey(key) } }
    /** Tune to an absolute frequency value (units are the same as getRadioFreq()). */
    fun sendUserFreq(freq: Int, direct: Boolean = true) { call { sendUserFreq(freq, direct) } }
    fun radioBandToggle() = sendRadioKey(RADIO_KEY_BAND)
    fun radioSeekDown() = sendRadioKey(RADIO_KEY_SEEK_DOWN)
    fun radioSeekUp() = sendRadioKey(RADIO_KEY_SEEK_UP)

    // v1.7 — RDS/TA status getters (AIDL ordinals 16 / 21). Read-only: no AIDL setter exists.
    fun getRadioRds(): Boolean? = call { getRadioRDSState() }
    fun getRadioTa(): Boolean? = call { getRadioTAState() }

    // ---- v2.6: the rest of the tuner status surface -------------------------
    // All getters. The AIDL has no radio setters beyond sendRadioKey/sendUserFreq, so RadioScreen
    // reports these and cannot toggle them.
    //
    // ⚠ There is NO radio-text API. The roadmap asked for "RDS text", but the 144-method AIDL
    // table contains no PS (station name) or RT (radio text) getter — only the on/off states
    // below plus the programme-TYPE name, which is a genre ("Pop Music"), not a station. The
    // ZXW_RADIO_INFO_EVT broadcast may carry more, but only its action string was recovered from
    // the decompile: no sender was traced and no extra beyond a frequency one is named, so its
    // payload is unknown. RadioScreen therefore shows the real indicator set and not a scroller
    // it cannot fill.
    /** Alternative Frequencies on/off (getRadioAFState, ordinal 20). */
    fun getRadioAf(): Boolean? = call { getRadioAFState() }
    /** Traffic Programme icon state (getRadioTPIconState, ordinal 27). */
    fun getRadioTp(): Boolean? = call { getRadioTPIconState() }
    /** Stereo icon state (getRadioSteroIconState, ordinal 26 — vendor's spelling). */
    fun getRadioStereo(): Boolean? = call { getRadioSteroIconState() }
    /** Programme-type *genre* name (getRadioPTYName, ordinal 19). Not the station name. */
    fun getRadioPtyName(): String? = call { getRadioPTYName() }

    // ---- v1.5: Audio / EQ (CAR_API §3.2; ordinals confirmed in AIDL_ORDINALS.md) --------
    /** Current EQ preset index (getEQMode, ordinal 55). */
    fun getEqMode(): Int? = call { getEQMode() }
    /** Select an EQ preset (sendEQMode, ordinal 5). Preset indices are vendor-defined. */
    fun setEqMode(mode: Int) { call { sendEQMode(mode) } }

    /**
     * Raw amp balance/fader as `[balance, fader]` (getBALFADValue ordinal 54 / sendBalFadValue
     * ordinal 51). Contract verified against the vendor EventService (2026-08-30 decompile):
     *
     *  - Amp domain is **0..14, centre 7**: `mBALVal`/`mFADVal` default to 7, boot-restore falls
     *    back to 7, factory reset writes 7, and boot ships `{0x2F, 7, 7}` = centre. 0 = full left /
     *    full front, 14 = full right / full rear. Order is balance-then-fader (confirmed).
     *  - No vendor clamp, and 0 is a valid extreme, NOT the centre — the launcher's old signed
     *    -8..8/centre-0 model made Centre play hard left. Callers map the centred display domain
     *    via [ampToDisplay]/[displayToAmp]; see AudioSettingsScreen.
     */
    fun getBalanceFader(): IntArray? = call { getBALFADValue() }
    /** Set raw amp balance (0..14) and fader (0..14); centre is 7. */
    fun setBalanceFader(balance: Int, fader: Int) { call { sendBalFadValue(balance, fader) } }

    /** Loudness on/off (getLoudness, ordinal 53). There is no AIDL setter — read-only here. */
    fun getLoudness(): Boolean? = call { getLoudness() }

    /** Subwoofer / software volume (getSndSWVol / sendSndSWVol, ordinals 58 / 57). */
    fun getSubVolume(): Int? = call { getSndSWVol() }
    fun setSubVolume(level: Int) { call { sendSndSWVol(level) } }

    /** Test beep through the audio path (beep, ordinal 7). */
    fun beep() { call { beep() } }

    // ---- Backlight / brightness (CAR_API §3.2) -----------------------------
    /**
     * Push a backlight level to the MCU (sendBacklight, ordinal 60). Param semantics are
     * GUESSED as (level, mode) — many of these units take (0..255 level, 0=day/1=night). Sent
     * as a best-effort second path alongside the Android system brightness; guarded, so a wrong
     * shape is a no-op. Verify on-device.
     */
    fun sendBacklight(level: Int, mode: Int = 0) {
        call { sendBacklight(level.coerceIn(0, 255).toByte(), mode.toByte()) }
    }
    /** Ask the gateway to (re)apply the system brightness it holds (setSystemBrightness, ord 68). */
    fun applySystemBrightness() { call { setSystemBrightness() } }

    /** Typed SysVar passthrough (alternative to the ContentResolver in [SysVar]). */
    fun getSettingString(key: String, def: String): String? =
        call { getSettingString(key, def) }

    private inline fun <T> call(block: IEventService.() -> T): T? {
        val svc = service ?: run {
            Log.d(TAG, "call while unbound")
            return null
        }
        return try {
            svc.block()
        } catch (t: Throwable) {
            Log.w(TAG, "AIDL call failed", t)
            null
        }
    }
}


/** Vendor amp balance/fader domain: 0..14 with centre 7 (see [CarService.getBalanceFader]). */
const val BAL_FAD_CENTRE = 7
const val BAL_FAD_HALF = 7

/** Map a raw amp value (0..14, centre 7) to the centred display domain (-7..7, centre 0). */
fun ampToDisplay(raw: Int): Int = raw.coerceIn(0, 2 * BAL_FAD_HALF) - BAL_FAD_CENTRE

/** Map a centred display value (-7..7) back to the raw amp domain (0..14, centre 7). */
fun displayToAmp(display: Int): Int = (display + BAL_FAD_CENTRE).coerceIn(0, 2 * BAL_FAD_HALF)
