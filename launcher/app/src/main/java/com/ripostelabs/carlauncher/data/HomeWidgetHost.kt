package com.ripostelabs.carlauncher.data

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where the add-a-widget flow stands. */
enum class BindStage { BIND, GRANT, CONFIGURE, DONE, CANCELLED }

/** How the last step went. */
enum class Outcome { OK, REFUSED }

/** Whether the widget has its own setup screen. */
enum class Setup { NONE, CONFIGURE }

/** One installed widget, as the picker lists it. */
data class WidgetChoice(val pkg: String, val cls: String, val label: String, val app: String)

/**
 * RAV4-199 — the pure steps of adding a home widget.
 *
 * ```
 *   BIND ──OK──▶ CONFIGURE? ──OK──▶ DONE
 *     │REFUSED       ▲  │REFUSED
 *     ▼              │  ▼
 *   GRANT ──OK───────┘ CANCELLED ◀── GRANT REFUSED
 * ```
 * BIND fails silently unless the launcher holds BIND_APPWIDGET (platform-signed on Riposte OS).
 * Otherwise the system grant dialog asks the user once per provider.
 */
object HomeWidgetFlow {

    fun next(stage: BindStage, outcome: Outcome, setup: Setup): BindStage {
        // A finished flow ignores late results.
        if (stage == BindStage.DONE || stage == BindStage.CANCELLED) {
            return stage
        }
        if (outcome == Outcome.REFUSED) {
            return if (stage == BindStage.BIND) BindStage.GRANT else BindStage.CANCELLED
        }
        if (stage == BindStage.CONFIGURE || setup == Setup.NONE) {
            return BindStage.DONE
        }
        return BindStage.CONFIGURE
    }

    /** Picker order: widget label, then app label, case-insensitive. */
    fun order(rows: List<WidgetChoice>): List<WidgetChoice> =
        rows.sortedWith(compareBy({ it.label.lowercase() }, { it.app.lowercase() }))
}

/**
 * RAV4-199 — the one Android AppWidget slot on Home.
 *
 * Wraps [AppWidgetHost] and [AppWidgetManager] so the UI deals in "the home widget", not ids.
 * The bound id lives in its own prefs file, outside the settings backup: an id means nothing
 * on another unit.
 *
 * ```
 *   picker ─▶ allocate() ─▶ bind() ─▶ (grant dialog) ─▶ (configure) ─▶ commit(id)
 *                                                   └─ any refusal ─▶ discard(id)
 *   HomeScreen ◀── widgetId ◀── commit
 * ```
 */
class HomeWidgetHost(context: Context) {

    private val app = context.applicationContext
    private val manager = AppWidgetManager.getInstance(app)
    private val host = AppWidgetHost(app, HOST_ID)
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _widgetId = MutableStateFlow(readId())

    /** The bound widget, or null when the slot is empty. */
    val widgetId: StateFlow<Int?> = _widgetId.asStateFlow()

    /** Installed widgets for the picker. */
    fun choices(): List<WidgetChoice> {
        val pm = app.packageManager
        val rows = manager.installedProviders.map { info ->
            val appLabel = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
            }.getOrDefault(info.provider.packageName)
            WidgetChoice(info.provider.packageName, info.provider.className, info.loadLabel(pm), appLabel)
        }
        return HomeWidgetFlow.order(rows)
    }

    /** Reserve an id for a widget being added. */
    fun allocate(): Int = host.allocateAppWidgetId()

    /** Bind without asking. False when the user has to grant it first. */
    fun bind(id: Int, choice: WidgetChoice): Boolean =
        runCatching { manager.bindAppWidgetIdIfAllowed(id, component(choice)) }.getOrDefault(false)

    /** The system dialog that asks the user to let the launcher bind [choice]. */
    fun grantIntent(id: Int, choice: WidgetChoice): Intent =
        Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, component(choice))

    /** Whether the bound widget wants its own setup screen first. */
    fun setup(id: Int): Setup =
        if (manager.getAppWidgetInfo(id)?.configure != null) Setup.CONFIGURE else Setup.NONE

    /** The widget's own setup screen. */
    fun configureIntent(id: Int): Intent? {
        val configure = manager.getAppWidgetInfo(id)?.configure ?: return null
        return Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            .setComponent(configure)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
    }

    /** Make [id] the home widget, releasing the one it replaces. */
    fun commit(id: Int) {
        val old = _widgetId.value
        if (old != null && old != id) {
            host.deleteAppWidgetId(old)
        }
        prefs.edit().putInt(KEY_ID, id).apply()
        _widgetId.value = id
    }

    /** Give back an id whose flow was cancelled. */
    fun discard(id: Int) {
        host.deleteAppWidgetId(id)
    }

    /** Empty the slot. */
    fun remove() {
        val old = _widgetId.value ?: return
        host.deleteAppWidgetId(old)
        prefs.edit().remove(KEY_ID).apply()
        _widgetId.value = null
    }

    /** The provider info, or null when its app was uninstalled. */
    fun info(id: Int): AppWidgetProviderInfo? = manager.getAppWidgetInfo(id)

    /** The live view for [id], drawn by the provider's RemoteViews. */
    fun createView(context: Context, id: Int, info: AppWidgetProviderInfo): AppWidgetHostView =
        host.createView(context, id, info)

    /** Updates flow only while Home is on screen. */
    fun startListening() = runCatching { host.startListening() }

    fun stopListening() = runCatching { host.stopListening() }

    private fun readId(): Int? = prefs.getInt(KEY_ID, NO_ID).takeIf { it != NO_ID }

    private fun component(choice: WidgetChoice) = ComponentName(choice.pkg, choice.cls)

    companion object {
        /** Any int unique to this app. "RAV4" in ASCII. */
        private const val HOST_ID = 0x52415634
        private const val PREFS = "home_widget"
        private const val KEY_ID = "widget_id"
        private const val NO_ID = AppWidgetManager.INVALID_APPWIDGET_ID
    }
}
