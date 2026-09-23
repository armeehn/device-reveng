package com.ripostelabs.carlauncher.carlib

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.IBinder
import android.util.Log
import java.time.LocalDateTime
import com.szchoiceway.eventcenter.ICallbackfn
import com.szchoiceway.eventcenter.ICommunication
import com.szchoiceway.eventcenter.IEventService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
        // Recovered from the vendor radio app (decompiled com.szchoiceway.radio,
        // MainActivity.OnKeyEvent and the preset handlers). The gateway forwards the value
        // untouched as MCU frame {0x02, key}, so these are the MCU's own opcodes.
        // 1..6 recall preset N, 7..12 store preset N; the transport keys are below.
        // The old guesses 0 / 1 / 2 meant nothing / preset 1 / preset 2 (CAR_API §3.2).
        const val RADIO_KEY_SCAN = 13
        const val RADIO_KEY_STEP_DOWN = 14
        const val RADIO_KEY_STEP_UP = 15
        const val RADIO_KEY_SEEK_DOWN = 16
        const val RADIO_KEY_SEEK_UP = 17
        const val RADIO_KEY_AUTO_STORE = 18
        const val RADIO_KEY_ST_MONO = 19
        const val RADIO_KEY_DX_LOC = 20
        const val RADIO_KEY_AF = 21
        const val RADIO_KEY_PTY_SEEK = 22
        const val RADIO_KEY_TA = 23
        const val RADIO_KEY_BAND_CYCLE = 24
        const val RADIO_KEY_NEXT = 25
        const val RADIO_KEY_PREV = 26
        const val RADIO_KEY_BAND_FM = 30
        const val RADIO_KEY_BAND_AM = 31

        /** Mode-callback event: the gateway's valid mode changed; arg2 is the new eSrcMode int. */
        const val EVT_MODE_CHANGE = 4097
        /** Mode-callback event: a panel/wheel key while we own the mode; arg2 is the MCU key. */
        const val EVT_MODE_KEY = 4098
        /** eSrcMode.SRC_CARPLAY — the one other mode the vendor radio tolerates without exiting. */
        const val SRC_CARPLAY = 32

        /** The tuner as an audio source: EventUtils.eSrcMode.SRC_RADIO, the int sendMode takes. */
        const val SRC_RADIO = 1

        /** Top of the vendor backlight slider; `Set_Day_Light` / `Set_Night_Light` are 0..20. */
        const val BACKLIGHT_MAX = 20

        /** Clamp a backlight target into the MCU's 0..[BACKLIGHT_MAX] band. */
        fun clampBacklight(level: Int): Int = level.coerceIn(0, BACKLIGHT_MAX)

        /** sendMode's boolean: block until the MCU acknowledges the mode byte. */
        private const val WAIT_FOR_MCU_ACK = true

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

        /** Radio band ordinal → true when it's an AM band: `mRadioBndNum > 2 ? "AM" : "FM"` (RadioUIControllerRotate.java:923). */
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

    /** Riposte OS 0.2: our own port owner. While set, commands go to it and the binder is never bound. */
    @Volatile
    private var owner: McuOwner? = null

    /** Riposte OS 0.2: true while [owner] answers instead of the vendor gateway. */
    val ownerAttached: Boolean
        get() = owner != null

    fun attachOwner(mcuOwner: McuOwner) {
        owner = mcuOwner
        _connected.value = true
    }

    /**
     * The tuner cache the owner's listener feeds (wire it with [CarEvents.ownerListener]). While
     * [owner] is set the radio getters answer from here and [radioEvents] ticks on each fold, as
     * the gateway's callback did; the binder path below is untouched.
     */
    val radioState = RadioStateHolder(onUpdate = { _radioEvents.update { it + 1 } })

    /** The volume cache the owner's `79`/`78` frames feed (a FanOut target); the binder otherwise. */
    val volumeState = VolumeStateHolder()

    /**
     * The backlight targets across boots (a FanOut target for the headlamp bit). Every
     * [sendBacklight] writes it, so the boot and wake `2E` replay the last thing the user set,
     * as the vendor's provider rows did (EventService.java:9639-9641).
     */
    val backlight = BacklightMemory(BacklightMemory.Prefs(appContext))

    /** Owner attached: [read] the cache, null until the MCU has reported; otherwise the binder. */
    private inline fun <T> volume(read: (VolumeState) -> T, binder: IEventService.() -> T?): T? {
        if (owner == null) {
            return call(binder)
        }
        return volumeState.state.value?.let(read)
    }

    /** Owner attached: [read] the cache, null until the MCU has reported; otherwise the binder. */
    private inline fun <T> tuner(read: (RadioState) -> T, binder: IEventService.() -> T?): T? {
        if (owner == null) {
            return call(binder)
        }

        val cached = radioState.state.value
        if (cached.updatedAt == 0L) {
            return null
        }
        return read(cached)
    }

    /** Bind the service. Idempotent-ish; returns false if the bind request was rejected. */
    fun bind(): Boolean {
        if (owner != null) {
            return true
        }
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
        owner?.let { it.stop(); owner = null; _connected.value = false; return }
        runCatching { appContext.unbindService(connection) }
        service = null
        _connected.value = false
    }

    // ---- Thin, guarded convenience wrappers --------------------------------
    // Each returns null / false when unbound or on RemoteException. Remember the
    // ordinal caveat above: values are unverified until the AIDL is corrected.

    /** The source mode: the last one the owner set on 0.2, the gateway's answer otherwise. */
    fun getValidMode(): Int? {
        owner?.let { return it.lastMode?.code }
        return call { getValidMode() }
    }
    fun isBackCarConnected(): Boolean = call { IsBackCarConneted() } ?: false
    fun getRadioFreq(): Int? = tuner({ it.freq }) { getRadioFreq() }
    fun getRadioBand(): Int? = tuner({ it.band }) { getRadioBand() }
    fun getMainVolume(): Int? = volume({ it.level }) { getMainVolval().toInt() }
    fun isMuteOn(): Boolean = volume({ it.muted }) { IsMuteOn() } ?: false
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
    fun getValidModeTitle(): String? {
        owner?.let { return SourceTitle.of(it.lastMode) }
        return call { getValidModeTitleInfor() }
    }

    // ---- v2.0: System / About (CAR_API §3.2) -------------------------------
    /** MCU firmware version (getMCUVer, ordinal 32). */
    fun getMcuVersion(): String? = call { getMCUVer() }
    /** CANBOX firmware version (getCanVer, ordinal 105). */
    fun getCanVersion(): String? = call { getCanVer() }
    /** Soft reboot the head unit (sendSoftWareReboot, ordinal 135). */
    fun reboot() { call { sendSoftWareReboot() } }
    /** Vendor factory reset (sendFactorySet, ordinal 76). ⚠ Destructive — confirm before calling. */
    fun factoryReset() { call { sendFactorySet() } }

    fun sendMode(mode: Int, flag: Boolean) {
        owner?.let { o -> McuOwnerProtocol.Mode.entries.firstOrNull { it.code == mode }?.let { o.setMode(it) }; return }
        call { sendMode(mode, flag) }
    }
    fun sendWheelKey(key: Int) { call { sendWheelKey(key) } }

    /** The three calls [CarCommandPort] may make, on whichever path (owner or vendor) is live. */
    fun asCommandTarget(): CarCommandPort.Target = object : CarCommandPort.Target {
        override fun setVolume(level: Int) = this@CarService.setVolume(level)
        override fun setMute(on: Boolean) = this@CarService.setMute(on)
        override fun setMode(mode: McuOwnerProtocol.Mode): Boolean {
            owner?.let { return it.setMode(mode) }
            sendMode(mode.code, true)
            return true
        }
    }

    /** One thread so a press and its release never interleave with another key's, as canbus2's. */
    private val climateExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "car-climate") }

    /**
     * One HVAC button. Owner attached: the box key frame, press then release
     * [ClimateKeys.RELEASE_GAP_MS] later, as canbus2 sends it; otherwise the 0.1 broadcast the
     * CAN app turns into the same frame. UNVERIFIED on the car either way.
     */
    fun pressClimate(button: ClimateButton) {
        val o = owner
        if (o == null) {
            ClimateControl(appContext).press(button)
            return
        }

        val press = ClimateKeys.press(button) ?: return
        climateExecutor.execute {
            o.send(press.down)
            Thread.sleep(ClimateKeys.RELEASE_GAP_MS)
            o.send(press.up)
        }
    }

    /** Owner path only: write the clock into the MCU's RTC (`13` frame). The gateway does its own. */
    fun sendRtc(now: LocalDateTime) {
        owner?.send(McuOwnerProtocol.rtc(now))
    }
    fun setMute(mute: Boolean) {
        owner?.let { it.send(McuOwnerProtocol.mute(mute)); return }
        call { sendMuteState(mute) }
    }

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
        owner?.let { it.send(McuOwnerProtocol.mainVolume(clamped)); return }
        // sendVolState carries the mute flag too, so we must preserve the real mute state.
        // A failed/errored IsMuteOn() read must NOT be coerced to `false` — that would silently
        // unmute the car as a side effect of a volume change. Bail if the state is unknown.
        val muted = call { IsMuteOn() } ?: return
        call { sendVolState(muted, clamped) }
    }

    // ---- Radio control (CAR_API §3.2). All guarded. ---------------------------
    fun sendRadioKey(key: Int) {
        owner?.let { it.send(McuOwnerProtocol.radioKey(key)); return }
        call { sendRadioKey(key) }
    }

    /**
     * Tune to an absolute frequency in the units getRadioFreq() reports. [fm] is the band
     * class: the gateway packs the call as MCU frame {0x0C, hi, lo, fm ? 0 : 1}, so an AM
     * value sent with [fm] = true lands in the FM band at whatever that number means there.
     */
    fun sendUserFreq(freq: Int, fm: Boolean) {
        owner?.let { it.send(McuOwnerProtocol.userFreq(freq, fm)); return }
        call { sendUserFreq(freq, fm) }
    }

    /** Recall slot 0..41 of the MCU's own station list (`02 64 slot`); owner path only, the gateway has no verb for it. */
    fun radioSelectPreset(slot: Int) {
        owner?.send(McuOwnerProtocol.radioPresetSelect(slot))
    }

    /** Store the current station into slot 0..41 (`02 65 slot`); owner path only. */
    fun radioStorePreset(slot: Int) {
        owner?.send(McuOwnerProtocol.radioPresetStore(slot))
    }

    fun radioSelectFm() = sendRadioKey(RADIO_KEY_BAND_FM)
    fun radioSelectAm() = sendRadioKey(RADIO_KEY_BAND_AM)
    fun radioSeekDown() = sendRadioKey(RADIO_KEY_SEEK_DOWN)
    fun radioSeekUp() = sendRadioKey(RADIO_KEY_SEEK_UP)
    fun radioToggleTa() = sendRadioKey(RADIO_KEY_TA)
    fun radioToggleAf() = sendRadioKey(RADIO_KEY_AF)

    /**
     * Route tuner audio to the amp, the way the vendor radio app does it
     * (RadioService.sendRadioMode): own SRC_RADIO, take the radio callback, then sendMode.
     * Without this the tuner answers every getter and accepts every key while the cabin stays
     * on whatever source was last selected: a radio screen that seeks but never plays.
     *
     * sendMode's boolean means "wait for the MCU's ACK (frame 0x70) before returning"
     * (`EventService.java:3933-3958`). The vendor passes false and sends twice; one call with
     * true is the same outcome without the guess.
     *
     * kill3rdAPK() on this path is gated by SysVar Sys_SoundManager_Type, which DEFAULTS to
     * "1" = Android-standard audio = no kill (`EventService.java:6581,6750,8294`). Only a unit
     * where it was set to 0 force-stops third-party tasks here.
     *
     * Riposte OS 0.2: with an owner attached there is no gateway to ask, so [radioSource] does
     * the same in our own hand: `01 01` with the MODE_ACK wait. Without it the tuner cache fills
     * and the amplifier stays on the last source.
     *
     * [focus]: the launcher's own RadioScreen takes Android audio focus here; a bound ITuner
     * client (the suite radio, RAV4-97) already holds it as the media citizen, and taking it
     * again 16 ms later knocked that client into pause, which released the tuner it just
     * claimed.
     */
    fun claimRadio(focus: RadioFocus = RadioFocus.TAKE) {
        owner?.let {
            if (!radioSource.claim()) {
                Log.w(TAG, "radio: SRC_RADIO not acknowledged")
            }
            if (focus == RadioFocus.TAKE) takeRadioFocus()
            return
        }
        call { setCurModeCallback(SRC_RADIO, radioCallback) }
        call { setRadioCallback(radioCallback) }
        call { sendMode(SRC_RADIO, WAIT_FOR_MCU_ACK) }
        if (focus == RadioFocus.TAKE) takeRadioFocus()
    }

    /** Who holds Android audio focus for the tuner after [claimRadio]. */
    enum class RadioFocus {
        /** The launcher: its own RadioScreen or a wheel gesture is the player. */
        TAKE,
        /** A bound ITuner client: it is the media citizen and keeps the focus it already has. */
        CLIENT_HOLDS,
    }

    /**
     * Hand the tuner source back (exitCurMode; a no-op in the gateway unless we hold it). The
     * vendor radio does this when another source wins or its audio focus is lost for good.
     * On the owner path [radioSource] sends what exitCurMode would: SRC_NULL, only if held.
     */
    fun releaseRadio() {
        owner?.let {
            radioSource.release()
            dropRadioFocus()
            return
        }
        call { exitCurMode(SRC_RADIO) }
        dropRadioFocus()
    }

    /** The tuner source on the owner path; idle while the binder owns the link. */
    private val radioSource = RadioSource(
        select = { mode -> owner?.setMode(mode) ?: false },
        voice = { on -> owner?.send(McuOwnerProtocol.voiceState(on)) },
        current = { owner?.lastMode },
    )

    /** `mValidMode == SRC_RADIO`: ours on the owner path, the gateway's answer otherwise. */
    fun isRadioClaimed(): Boolean {
        owner?.let { return radioSource.held }
        return getValidMode() == SRC_RADIO
    }

    /** Ticks when the gateway moves the source off the tuner (RAV4-97 onSourceLost). */
    private val _sourceLost = MutableStateFlow(0L)
    val sourceLost: StateFlow<Long> = _sourceLost.asStateFlow()

    private fun dropRadioFocus() {
        radioFocus?.let { appContext.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(it) }
    }

    /**
     * Bumps on every tuner event the gateway pushes (band, tune slot, frequency, PS name, the
     * status bits) and on mode changes, so a screen can re-poll when something happened instead
     * of on a timer. The events' ids: 0 status bits, 1 band, 2 slot, 3 freq, 5 PTY, 6 PS name.
     * With an owner attached, [radioState] bumps it for the same `73` sub-commands.
     */
    private val _radioEvents = MutableStateFlow(0L)
    val radioEvents: StateFlow<Long> = _radioEvents.asStateFlow()

    /** Answers like the vendor's stubs (checkIsActive = false). */
    private val radioCallback = object : ICallbackfn.Stub() {
        override fun notifyEvt(what: Int, arg1: Int, arg2: Int, data: ByteArray?, str: String?) {
            if (what == EVT_MODE_CHANGE && arg2 != SRC_RADIO && arg2 != SRC_CARPLAY) {
                Log.i(TAG, "radio: mode moved to $arg2")
                _sourceLost.update { it + 1 }
            }
            _radioEvents.update { it + 1 }
        }

        override fun checkIsActive(): Boolean = false
    }

    // Android-side audio focus, like the vendor radio: whatever was playing pauses, and a
    // permanent loss (another media app started) hands the MCU source back too.
    private var radioFocus: AudioFocusRequest? = null

    private val radioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i(TAG, "radio: audio focus lost, releasing the source")
                releaseRadio()
            }
            // The vendor radio's AudioManagerUtils (AudioManagerUtils.java:61-88): a transient
            // loss ducks the tuner with `42 01`, a gain re-sends the mode and clears it.
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> radioSource.duck(true)
            AudioManager.AUDIOFOCUS_GAIN -> if (radioSource.held) radioSource.claim()
        }
    }

    private fun takeRadioFocus() {
        val audio = appContext.getSystemService(AudioManager::class.java) ?: return
        val request = radioFocus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(radioFocusListener)
            .build()
            .also { radioFocus = it }
        audio.requestAudioFocus(request)
    }

    // v1.7 — RDS/TA status getters (AIDL ordinals 16 / 21). Read-only: no AIDL setter exists.
    fun getRadioRds(): Boolean? = tuner({ it.rds }) { getRadioRDSState() }
    fun getRadioTa(): Boolean? = tuner({ it.ta }) { getRadioTAState() }

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
    fun getRadioAf(): Boolean? = tuner({ it.af }) { getRadioAFState() }
    /** Traffic Programme icon state (getRadioTPIconState, ordinal 27). */
    fun getRadioTp(): Boolean? = tuner({ it.tp }) { getRadioTPIconState() }
    /** Stereo icon state (getRadioSteroIconState, ordinal 26 — vendor's spelling). */
    fun getRadioStereo(): Boolean? = tuner({ it.stereo }) { getRadioSteroIconState() }
    /**
     * The RDS PS station name (getRadioPTYName, ordinal 19 — misnamed by the vendor: the gateway
     * stores the MCU's PS frame in `mRadioPSName` and returns it here). The genre is
     * `getRadioPTYNum()`, an index into the vendor's 32-entry PTY table. No radio text (RT) exists.
     */
    fun getRadioStationName(): String? = tuner({ it.stationName }) { getRadioPTYName() }

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
     * Push both backlight targets to the MCU (sendBacklight, ordinal 60). The gateway builds
     * frame `{0x2E, day, night, fineLow, fineHi, 0}` (`EventService.java:9643-9648`); which one
     * the panel shows follows its headlamp input. Each is 0..[BACKLIGHT_MAX], the range of the
     * vendor slider (`DataManage.java:256`). Pass the current `Set_Day_Light` / `Set_Night_Light`
     * values for the side you are not changing: a provider write of those keys alone only fires a
     * broadcast and never reaches the MCU (`EventService.java:4847-4854`).
     */
    fun sendBacklight(day: Int, night: Int) {
        val clampedDay = clampBacklight(day)
        val clampedNight = clampBacklight(night)
        backlight.remember(clampedDay, clampedNight)

        // Owner path: the `2E` frame straight to the MCU; the binder otherwise. Without this branch
        // the Display slider moved and the panel stayed put on Riposte OS 0.2.
        owner?.let { it.send(McuOwnerProtocol.backlight(clampedDay, clampedNight)); return }
        call { sendBacklight(clampedDay.toByte(), clampedNight.toByte()) }
    }

    /**
     * The `1F` the vendor sends around its black-screen overlay (sendBlackScreen,
     * EventService.java:9679-9685). Owner path only: the AIDL has no equivalent, the gateway
     * drives it from its own overlay.
     */
    fun sendBlackScreen(screen: McuOwnerProtocol.Screen) {
        val mcuOwner = owner
        if (mcuOwner == null) {
            Log.w(TAG, "sendBlackScreen: no port owner")
            return
        }

        mcuOwner.send(McuOwnerProtocol.blackScreen(screen))
    }

    /**
     * Write a SysVar the way the vendor settings app does (changeSetup, ordinal 78): the gateway
     * persists the row and reacts to it (`EventService.java:4635`, e.g. re-running the nav-bar
     * geometry for `Sys_Customer_NaviBar_Height_Key`). Returns false when unbound or on a binder
     * failure, so the caller can fall back to the provider.
     */
    fun changeSetup(key: String, value: String): Boolean =
        call { changeSetup(key, value); true } ?: false

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
