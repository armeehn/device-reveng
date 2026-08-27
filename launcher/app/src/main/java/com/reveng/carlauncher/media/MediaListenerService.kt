package com.reveng.carlauncher.media

import android.service.notification.NotificationListenerService

/**
 * Empty NotificationListenerService.
 *
 * We don't care about notifications themselves — this service exists only because
 * `MediaSessionManager.getActiveSessions()` requires the caller to be an *enabled*
 * notification listener (or hold the signature perm MEDIA_CONTENT_CONTROL). Being a
 * declared + enabled listener is the normal-app way to read other apps' media sessions
 * (Spotify, mpv, the vendor music app, …).
 *
 * Enabling: the user can toggle it in Settings > Notifications > Device & app notifications,
 * OR — since this unit is rooted — [NowPlayingRepository.ensureListenerEnabled] flips it on
 * automatically via `cmd notification allow_listener`.
 */
class MediaListenerService : NotificationListenerService()
