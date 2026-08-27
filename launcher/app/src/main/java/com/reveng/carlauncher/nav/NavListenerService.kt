package com.reveng.carlauncher.nav

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * NavListenerService — reads Google Maps' ongoing **navigation notification** and feeds the
 * parsed turn-by-turn / ETA into [NavRepository].
 *
 * This mirrors [com.reveng.carlauncher.media.MediaListenerService]: it is a declared
 * NotificationListenerService (manifest, BIND_NOTIFICATION_LISTENER_SERVICE) that must be
 * *enabled*. On this rooted unit [NavRepository.ensureListenerEnabled] flips it on via
 * `cmd notification allow_listener`. Unlike the media one, this listener actually inspects
 * notifications (Maps has no public nav API), but only Maps' package — nothing else is read.
 */
class NavListenerService : NotificationListenerService() {

    companion object { private const val TAG = "NavListener" }

    override fun onListenerConnected() {
        // Re-scan already-posted notifications so we pick up an in-progress trip immediately.
        runCatching {
            activeNotifications?.forEach { handle(it) }
        }.onFailure { Log.w(TAG, "onListenerConnected scan failed: ${it.message}") }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        handle(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName == NavRepository.MAPS_PACKAGE) NavRepository.clear()
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName != NavRepository.MAPS_PACKAGE) return
        val extras = sbn.notification?.extras ?: return

        // Only ongoing (persistent) notifications are the active-navigation card; the search /
        // "commute" style notifications are dismissible. GUESS — verify on-device.
        val ongoing = sbn.isOngoing ||
            (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (!ongoing) return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)

        val nav = NavRepository.parse(title, text, sub)
        if (nav != null) NavRepository.publish(nav)
    }
}
