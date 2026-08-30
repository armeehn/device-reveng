package com.ripostelabs.carlauncher.media

/**
 * v4.1 — "is this session video?" for the home media card and the video mini screen.
 *
 * A MediaSession carries no media-type field, and the metadata keys that could hint at one
 * (MEDIA_URI, DISPLAY_*) are set inconsistently even by well-behaved players. What every session
 * does carry is its owning package, so the classification is a package list: the Jellyfin
 * clients this unit actually uses (via [JellyfinApp]) plus the video players people side-load
 * on Android head units. A miss is cheap — an unlisted video app still gets the full media
 * card, it just doesn't get the mini screen.
 */
object VideoApps {

    private val VIDEO_PACKAGES = setOf(
        "org.videolan.vlc",
        "is.xyz.mpv",
        "com.mxtech.videoplayer.ad",
        "com.mxtech.videoplayer.pro",
        "com.google.android.youtube",
        "app.revanced.android.youtube",
        "org.schabi.newpipe",
        "com.teamsmart.videomanager.tv", // SmartTube
        "com.liskovsoft.smarttubetv.beta",
        "com.plexapp.android",
        "com.mb.android", // Emby
        "org.xbmc.kodi",
        "com.netflix.mediaclient",
        "com.amazon.avod.thirdpartyclient", // Prime Video
    )

    /** True when [pkg] is a known video app (Jellyfin included). */
    fun isVideo(pkg: String?): Boolean =
        pkg != null && (JellyfinApp.isJellyfin(pkg) || pkg in VIDEO_PACKAGES)
}
