package com.reveng.carlauncher.notif

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * v2.7 — the notification-shelf listener.
 *
 * A third NotificationListenerService rather than a fourth job for an existing one. The media
 * listener is deliberately empty (it exists only to make `getActiveSessions()` legal) and the nav
 * listener reads exactly one package. Teaching either of them to also collect everything would
 * couple two unrelated features to one component the OS can enable and disable independently —
 * and each listener is enabled separately anyway, so the split costs nothing at runtime.
 *
 * Enabled on this rooted unit by [NotificationRepository.ensureListenerEnabled] via
 * `cmd notification allow_listener`, same as the other two.
 */
class ShelfListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "ShelfListener"
    }

    override fun onListenerConnected() {
        NotificationRepository.attach(this)
        // Pick up what is already posted: the shelf should be populated the first time it opens,
        // not from the next notification onwards.
        runCatching {
            activeNotifications?.forEach { handle(it) }
        }.onFailure { Log.w(TAG, "initial scan failed: ${it.message}") }
    }

    override fun onListenerDisconnected() {
        NotificationRepository.detach()
    }

    /**
     * v0.4.3.8: guarded like the [onListenerConnected] scan is. This reads the extras Bundle of
     * *every* notification on the device, and a Bundle carrying a Parcelable whose class this APK
     * does not hold throws on unparcel. An exception out of this callback kills the launcher
     * process, which for the HOME app is a black screen in a moving car — so one malformed
     * notification from any app must cost us that notification, not the launcher.
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        runCatching { handle(sbn) }
            .onFailure { Log.w(TAG, "dropped notification from ${sbn.packageName}: ${it.message}") }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        NotificationRepository.remove(sbn.key)
    }

    private fun handle(sbn: StatusBarNotification) {
        val item = toShelfItem(sbn) ?: return
        NotificationRepository.publish(item)
    }

    /**
     * Flatten a posted notification into a shelf row, or null if it does not belong on a shelf.
     *
     * What gets dropped, and why:
     * - **our own** — the launcher talking to itself is not news.
     * - **ongoing** — persistent notifications are status, not events: the media transport (already
     *   the MediaCard), Maps' turn-by-turn (already the NavCard), sync and download bars. They
     *   would sit at the top of the shelf permanently and push the actual messages off it.
     * - **group summaries** — the OS posts a summary *and* its children; showing both duplicates
     *   every threaded conversation.
     * - **empty** — a notification with neither title nor text has nothing to glance at.
     */
    private fun toShelfItem(sbn: StatusBarNotification): ShelfNotification? {
        if (sbn.packageName == packageName) {
            return null
        }
        val n = sbn.notification ?: return null
        if (sbn.isOngoing || (n.flags and Notification.FLAG_ONGOING_EVENT) != 0) {
            return null
        }
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            return null
        }

        val extras = n.extras ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (
            extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            )?.toString()?.trim().orEmpty()

        if (title.isEmpty() && text.isEmpty()) {
            return null
        }

        return ShelfNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel(sbn.packageName),
            title = title,
            text = text,
            postedAtMs = sbn.postTime,
        )
    }

    /** Resolve a package's user-visible label; falls back to its last name segment. */
    private fun appLabel(pkg: String): String {
        val pm = packageManager
        return runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrElse { pkg.substringAfterLast('.') }
    }
}
