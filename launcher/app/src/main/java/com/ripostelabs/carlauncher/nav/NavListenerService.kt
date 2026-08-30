package com.ripostelabs.carlauncher.nav

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * NavListenerService — reads Google Maps' ongoing **navigation notification** and feeds the
 * parsed turn-by-turn / ETA into [NavRepository].
 *
 * This mirrors [com.ripostelabs.carlauncher.media.MediaListenerService]: it is a declared
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

    /**
     * v0.4.3.8: guarded like the [onListenerConnected] scan is. Reading a notification's extras
     * unparcels a Bundle another app built, which throws if it carries a Parcelable class this APK
     * does not hold. An exception out of this callback kills the launcher process, which for the
     * HOME app is a black screen in a moving car.
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        runCatching { handle(sbn) }
            .onFailure { Log.w(TAG, "dropped notification from ${sbn.packageName}: ${it.message}") }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Only the ongoing navigation notification ending should clear the card. Dismissing a
        // non-nav Maps notification (commute suggestion, "rate this place", share, etc.) while a
        // trip is running must NOT blank the nav card. Also re-scan for another still-posted
        // ongoing Maps notification before clearing, to survive a transient replace/remove.
        if (sbn == null || !isOngoingMaps(sbn)) return
        val stillNavigating = runCatching {
            activeNotifications?.any { it.key != sbn.key && isOngoingMaps(it) } == true
        }.getOrDefault(false)
        if (!stillNavigating) NavRepository.clear()
    }

    /** True only for Google Maps' ongoing (persistent) navigation notification. */
    private fun isOngoingMaps(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName != NavRepository.MAPS_PACKAGE) return false
        val n = sbn.notification ?: return false
        // GUESS — verify on-device: search / "commute" notifications are dismissible, not ongoing.
        return sbn.isOngoing || (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
    }

    private fun handle(sbn: StatusBarNotification) {
        if (!isOngoingMaps(sbn)) return
        val extras = sbn.notification?.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)
        val sub = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)

        val nav = NavRepository.parse(title, text, sub)
        if (nav != null) NavRepository.publish(nav)
    }
}
