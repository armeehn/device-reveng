package com.reveng.carlauncher.nav

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.reveng.carlauncher.carlib.RootShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Immutable snapshot of the current turn-by-turn navigation, parsed from Maps' notification. */
data class NavState(
    /** Next-maneuver instruction, e.g. "Turn right onto Main St" (notification title). */
    val instruction: String,
    /** Distance to the next maneuver, e.g. "200 m" (best-effort split of the text line). */
    val distance: String = "",
    /** ETA / remaining time+distance line, e.g. "17 min · 5.2 km" (notification text). */
    val eta: String = "",
)

/**
 * NavRepository — turn-by-turn / ETA surfaced by reading Google Maps' ongoing navigation
 * **notification** (there is no public nav API; the notification is the only app-agnostic
 * signal). Mirrors the media path (CAR_API §6): a declared [NavListenerService]
 * NotificationListenerService, enabled on this rooted unit via
 * `cmd notification allow_listener` (same mechanism as the media listener).
 *
 * This is a process-wide singleton because the OS — not us — instantiates the listener
 * service; the service pushes parsed state here via [publish], and the UI ([NavCard])
 * collects [state]. Nothing in [MainActivity] needs to change.
 */
object NavRepository {

    private const val TAG = "NavRepository"

    /** Google Maps handles both phone-nav and (projected) Android Auto navigation. */
    const val MAPS_PACKAGE = "com.google.android.apps.maps"

    private val _state = MutableStateFlow<NavState?>(null)
    /** Current navigation, or null when not navigating. */
    val state: StateFlow<NavState?> = _state.asStateFlow()

    /** Called by [NavListenerService] when a Maps nav notification is posted/updated. */
    fun publish(nav: NavState?) { _state.value = nav }

    /** Called by [NavListenerService] when the Maps nav notification is dismissed. */
    fun clear() { _state.value = null }

    /**
     * Launch Google Maps (which also drives Android Auto projection). Falls back to the
     * generic geo: intent chooser if Maps isn't installed under its usual package.
     */
    fun launchMaps(context: Context) {
        val pm = context.packageManager
        val direct = pm.getLaunchIntentForPackage(MAPS_PACKAGE)
        val intent = direct ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "launchMaps failed: ${it.message}") }
    }

    /** True if our nav listener component is in the enabled_notification_listeners setting. */
    fun isListenerEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        val me = ComponentName(context, NavListenerService::class.java).flattenToString()
        return flat.split(":").any { it.equals(me, ignoreCase = true) }
    }

    /**
     * Set once the grant has been settled for this process — either it was already in place, or we
     * made our one attempt at it. [NavCard] calls this from a LaunchedEffect and is disposed and
     * recomposed on every Home ↔ Settings ↔ Media round trip, so without this a unit where the
     * grant does not stick pays a fresh `su` fork plus a Magisk policy check on every Home entry.
     */
    @Volatile
    private var listenerSettled = false

    /**
     * Root-enable the nav notification listener so onNotificationPosted() delivers Maps'
     * navigation notifications. Best-effort — safe to call on a non-rooted unit (it just
     * fails and nav info stays empty; the tap-to-navigate launcher still works).
     *
     * Idempotent and cheap on repeat: at most one shell attempt per process. A failure here needs
     * root or a user repair to change, neither of which a recomposition can supply.
     */
    fun ensureListenerEnabled(context: Context) {
        if (listenerSettled) return
        if (isListenerEnabled(context)) {
            listenerSettled = true
            return
        }

        val comp = ComponentName(context, NavListenerService::class.java).flattenToString()
        val r = RootShell.exec("cmd notification allow_listener ${RootShell.quote(comp)}")
        listenerSettled = true
        Log.i(TAG, "allow_listener $comp -> code=${r.code} ${r.stdout}")
    }

    /**
     * Best-effort parse of a Google Maps navigation notification into a [NavState].
     *
     * ⚠ GUESSED shape — verify on-device. Observed Maps nav notifications carry:
     *   EXTRA_TITLE    → the next-maneuver instruction  ("Turn right onto Main St")
     *   EXTRA_TEXT     → a "<distance> · <road>" or ETA-ish line
     *   EXTRA_SUB_TEXT → the ETA summary ("17 min · 5.2 km · 14:32")
     * Layout drifts across Maps versions and locales, so we keep it forgiving and never throw.
     *
     * Returns null if the notification doesn't look like an active-nav card (no title).
     */
    fun parse(title: CharSequence?, text: CharSequence?, subText: CharSequence?): NavState? {
        val instruction = title?.toString()?.trim().orEmpty()
        if (instruction.isEmpty()) return null

        val textStr = text?.toString()?.trim().orEmpty()
        val subStr = subText?.toString()?.trim().orEmpty()

        // GUESS: the maneuver distance ("200 m", "1.2 km") is usually the leading token of
        // the text line before a separator; the rest / subText is the ETA summary.
        val (distance, restOfText) = splitLeadingDistance(textStr)
        val eta = listOf(restOfText, subStr).firstOrNull { it.isNotEmpty() }.orEmpty()

        return NavState(instruction = instruction, distance = distance, eta = eta)
    }

    /** Pull a leading distance token ("200 m", "1.2 km", "0.3 mi") off a text line. */
    private fun splitLeadingDistance(s: String): Pair<String, String> {
        if (s.isEmpty()) return "" to ""
        val m = Regex("""^\s*([\d.,]+\s?(?:m|km|mi|ft|yd))\b""", RegexOption.IGNORE_CASE)
            .find(s) ?: return "" to s
        val dist = m.groupValues[1].trim()
        val rest = s.removeRange(m.range).trim().trimStart('·', '-', '•', ' ')
        return dist to rest
    }
}
