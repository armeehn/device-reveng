package com.reveng.carlauncher.notif

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.reveng.carlauncher.carlib.RootShell
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One row on the v2.7 notification shelf, flattened out of a [android.app.Notification]. */
data class ShelfNotification(
    /** The platform's own key — what [ShelfListenerService.cancelNotification] needs to dismiss it. */
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAtMs: Long,
)

/**
 * v2.7 — a car-friendly notification list.
 *
 * We already hold notification-listener access twice over (media sessions need an *enabled*
 * listener; [com.reveng.carlauncher.nav.NavListenerService] reads Maps' turn-by-turn). Both
 * deliberately ignore the notifications themselves. This one is the first that reads them, so the
 * scope is worth stating: it keeps at most [MAX_ITEMS] recent notifications in memory, never on
 * disk, and the process losing them on restart is fine — a shelf is a glance at what is happening
 * now, not an inbox.
 *
 * A process-wide singleton for the same reason [com.reveng.carlauncher.nav.NavRepository] is: the
 * OS instantiates the listener service, not us, so there is nothing to inject it into.
 *
 * **Parked-only.** Reading prose off a screen is the textbook distraction case, so the shelf UI
 * sits behind the v2.5 `ParkedOnly` gate. This layer keeps collecting while moving — stopping and
 * restarting the listener would just make the list stale the moment the car stops.
 */
object NotificationRepository {

    private const val TAG = "NotifShelf"

    /** One screenful and a bit. Beyond that the list stops being glanceable. */
    private const val MAX_ITEMS = 40

    private val _items = MutableStateFlow<List<ShelfNotification>>(emptyList())

    /** Newest first. Filtering by app happens in the UI so un-muting recovers history. */
    val items: StateFlow<List<ShelfNotification>> = _items.asStateFlow()

    /**
     * The connected listener, held so [dismiss] can call through it.
     *
     * Cleared in `onListenerDisconnected`. Holding a service instance in a singleton is normally a
     * leak; here the OS owns the lifecycle and hands us the same instance it will later tear down,
     * and the alternative — rebinding a second listener just to cancel — is worse.
     */
    @Volatile
    private var service: ShelfListenerService? = null

    fun attach(listener: ShelfListenerService) {
        service = listener
    }

    fun detach() {
        service = null
        _items.value = emptyList()
    }

    /** Insert or replace by key (apps update a notification in place rather than reposting). */
    fun publish(item: ShelfNotification) {
        val kept = _items.value.filterNot { it.key == item.key }
        _items.value = (listOf(item) + kept)
            .sortedByDescending { it.postedAtMs }
            .take(MAX_ITEMS)
    }

    fun remove(key: String) {
        _items.value = _items.value.filterNot { it.key == key }
    }

    /**
     * Ask the system to dismiss the real notification. The row disappears from the shelf via the
     * resulting `onNotificationRemoved` callback, not here — so if the cancel is refused (an
     * ongoing notification, a dead listener) the shelf keeps showing what is genuinely still
     * posted instead of lying about it.
     */
    fun dismiss(key: String) {
        val listener = service
        if (listener == null) {
            Log.w(TAG, "dismiss($key) with no connected listener")
            return
        }
        runCatching { listener.cancelNotification(key) }
            .onFailure { Log.w(TAG, "cancelNotification failed: ${it.message}") }
    }

    /** The set of packages that have posted something — the per-app filter list is built from it. */
    fun knownPackages(): List<Pair<String, String>> = _items.value
        .distinctBy { it.packageName }
        .map { it.packageName to it.appLabel }
        .sortedBy { it.second.lowercase() }

    /** Open the app a notification came from. There is no way to re-fire its own content intent
     * from here without the original PendingIntent, and holding those alive is not worth it. */
    fun launchSource(context: Context, packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
        runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "launch $packageName failed: ${it.message}") }
    }

    /** True if our shelf listener component is in the enabled_notification_listeners setting. */
    fun isListenerEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver, "enabled_notification_listeners",
        ) ?: return false
        val me = ComponentName(context, ShelfListenerService::class.java).flattenToString()
        return flat.split(":").any { it.equals(me, ignoreCase = true) }
    }

    /**
     * Root-enable the shelf listener, exactly as the media and nav listeners do. Best-effort: on a
     * unit without root the shelf simply stays empty and says so, and nothing else breaks.
     */
    fun ensureListenerEnabled(context: Context) {
        if (isListenerEnabled(context)) {
            return
        }
        val comp = ComponentName(context, ShelfListenerService::class.java).flattenToString()
        val r = RootShell.exec("cmd notification allow_listener '$comp'")
        Log.i(TAG, "allow_listener $comp -> code=${r.code} ${r.stdout}")
    }
}
