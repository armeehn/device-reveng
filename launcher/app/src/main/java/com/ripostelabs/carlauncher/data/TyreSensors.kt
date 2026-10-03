package com.ripostelabs.carlauncher.data

import android.content.Context
import android.util.Log
import com.ripostelabs.carlauncher.carlib.CarEvents
import com.ripostelabs.carlauncher.carlib.TyreHealth
import com.ripostelabs.carlauncher.carlib.TyreRadioLink
import com.ripostelabs.carlauncher.carlib.TyreRadioParser
import com.ripostelabs.carlauncher.carlib.TyreRadioState
import com.ripostelabs.carlauncher.carlib.TyreReading
import com.ripostelabs.carlauncher.carlib.TyreSensorSet
import com.ripostelabs.carlauncher.carlib.TyreState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Where a sensor sits. Toyota sensors do not say, so the owner assigns it once. */
enum class TyrePosition(val label: String) {
    FRONT_LEFT("Front left"),
    FRONT_RIGHT("Front right"),
    REAR_LEFT("Rear left"),
    REAR_RIGHT("Rear right"),
    SPARE("Spare"),
}

/** One of the car's own sensors as the Tyres page shows it. */
data class TyreSensorView(
    val reading: TyreReading,
    val position: TyrePosition?,
    val state: TyreState,
)

data class TyreRadioView(
    val receiver: TyreRadioState = TyreRadioState.STARTING,
    val sensors: List<TyreSensorView> = emptyList(),
)

/**
 * The car's tyre sensors, heard by the USB radio. The layer between the Tyres page and the
 * [TyreRadioLink] driver.
 *
 *     TyresScreen ◀── view ── TyreSensors ◀── lines ── TyreRadioLink (su rtl_433)
 *                                  │
 *                                  └── learned ids + positions ─▶ SharedPreferences
 *
 * rtl_433 ships inside the APK as an asset and is copied out once per launcher install, so a
 * launcher update over the air also updates the receiver.
 */
class TyreSensors private constructor(context: Context) {

    companion object {
        @Volatile
        private var shared: TyreSensors? = null

        /**
         * One per process: a second rtl_433 cannot open the dongle. MainActivity can be
         * recreated, so each call follows the newest [CarEvents] for the motion signal.
         */
        fun shared(context: Context, carEvents: CarEvents): TyreSensors {
            val sensors = shared ?: synchronized(this) {
                shared ?: TyreSensors(context).also {
                    shared = it
                    it.carEvents = carEvents
                    it.start()
                }
            }
            sensors.carEvents = carEvents
            return sensors
        }

        private const val TAG = "TyreSensors"
        private const val PREFS = "tyre_radio"
        private const val KEY_OWN = "own"
        private const val KEY_INSTALLED = "binary_installed_at"
        private const val POSITION_PREFIX = "pos:"
        private const val ASSET = "tpms/rtl_433"

        /** Last-heard times save at most this often; a set change saves at once. */
        private const val SAVE_EVERY_MS = 60 * 60 * 1000L
    }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val set = TyreSensorSet(loadOwn())
    private val binary = File(app.filesDir, ASSET)
    private val link = TyreRadioLink(binary.path, ::onLine, ::onState)

    @Volatile
    private var carEvents: CarEvents? = null

    @Volatile
    private var savedAtMs = 0L

    private val _view = MutableStateFlow(TyreRadioView())
    val view: StateFlow<TyreRadioView> = _view.asStateFlow()

    private fun start() {
        Thread({
            if (install()) {
                link.start()
            }
        }, "tyre-radio-install").start()
    }

    /** Cycles the sensor through the positions and back to unassigned. */
    fun cyclePosition(id: String) {
        val positions = TyrePosition.entries
        val current = position(id)
        val next = if (current == null) positions.first() else positions.getOrNull(current.ordinal + 1)

        prefs.edit().apply {
            // One sensor per position: the one that had it goes back to unassigned.
            if (next != null) {
                clearHolder(next)
            }
            if (next == null) remove(POSITION_PREFIX + id) else putString(POSITION_PREFIX + id, next.name)
        }.apply()
        publish()
    }

    private fun android.content.SharedPreferences.Editor.clearHolder(p: TyrePosition) {
        prefs.all.filter { it.key.startsWith(POSITION_PREFIX) && it.value == p.name }
            .forEach { remove(it.key) }
    }

    private fun onLine(line: String) {
        val reading = TyreRadioParser.parse(line, System.currentTimeMillis()) ?: return

        val changed = synchronized(set) { set.record(reading, carEvents?.motion?.value ?: CarEvents.Motion.UNKNOWN) }
        if (changed || reading.atMs - savedAtMs > SAVE_EVERY_MS) {
            savedAtMs = reading.atMs
            saveOwn()
        }
        publish()
    }

    private fun onState(state: TyreRadioState) {
        _view.value = _view.value.copy(receiver = state)
    }

    private fun publish() {
        val own = synchronized(set) { set.own() }
        val sensors = own.map { r ->
            TyreSensorView(r, position(r.id), TyreHealth.of(r, own.filter { it.id != r.id && position(it.id) != TyrePosition.SPARE }))
        }.sortedBy { it.position?.ordinal ?: Int.MAX_VALUE }
        _view.value = _view.value.copy(sensors = sensors)
    }

    private fun position(id: String): TyrePosition? =
        prefs.getString(POSITION_PREFIX + id, null)?.let { runCatching { TyrePosition.valueOf(it) }.getOrNull() }

    /** Copies rtl_433 out of the APK when this launcher install has not done it yet. */
    private fun install(): Boolean {
        val installedAt = app.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime
        if (binary.canExecute() && prefs.getLong(KEY_INSTALLED, 0) == installedAt) {
            return true
        }

        return runCatching {
            binary.parentFile!!.mkdirs()
            app.assets.open(ASSET).use { input -> binary.outputStream().use { input.copyTo(it) } }
            binary.setExecutable(true, true)
            prefs.edit().putLong(KEY_INSTALLED, installedAt).apply()
            true
        }.getOrElse {
            Log.w(TAG, "rtl_433 install failed", it)
            false
        }
    }

    /** "id:lastHeardMs" strings. */
    private fun loadOwn(): Map<String, Long> =
        prefs.getStringSet(KEY_OWN, emptySet()).orEmpty().mapNotNull { entry ->
            val id = entry.substringBefore(':')
            entry.substringAfter(':').toLongOrNull()?.let { id to it }
        }.toMap()

    private fun saveOwn() {
        val own = synchronized(set) { set.lastHeard }
        prefs.edit().putStringSet(KEY_OWN, own.map { "${it.key}:${it.value}" }.toSet()).apply()
    }
}
