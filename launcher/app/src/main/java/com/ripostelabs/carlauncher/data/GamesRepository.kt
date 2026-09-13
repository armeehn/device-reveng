package com.ripostelabs.carlauncher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.util.Log
import com.ripostelabs.carlauncher.carlib.GameApp
import com.ripostelabs.carlauncher.carlib.GameApps
import com.ripostelabs.carlauncher.carlib.GameLibrary
import com.ripostelabs.carlauncher.carlib.Rom
import java.io.File

/**
 * Finds and starts the emulator frontends on this unit.
 *
 * The catalogue and the matching rules live in [GameApps] and [GameLibrary], which are testable
 * without a device. This is only the part that has to touch the package manager and the disk.
 */
object GamesRepository {

    /**
     * The ROM tree, `roms/<system>/<Title>.<ext>`, inside RetroArch's own folder so its file
     * browser sees the same files. Not media, so Android 13 hides it from any app without
     * "All files access" ([hasFileAccess]).
     */
    val ROMS_DIR = File(Environment.getExternalStorageDirectory(), "RetroArch/roms")

    private const val TAG = "GamesRepository"

    /** RetroArch's content-launching activity; takes the ROM and core as extras. */
    private const val RETRO_ACTIVITY = "com.retroarch.browser.retroactivity.RetroActivityFuture"

    /** Frontends actually present, in catalogue order. */
    fun installed(context: Context): List<GameApp> {
        val pm = context.packageManager
        val present = GameApps.KNOWN.filter {
            runCatching { pm.getLaunchIntentForPackage(it.packageName) }.getOrNull() != null
        }

        return present
    }

    /**
     * Launch [packageName]. Returns false when it has no launch intent, which is the case for a
     * package that is installed but disabled — worth reporting rather than failing silently.
     */
    fun launch(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "launch $packageName failed", it) }
            .isSuccess
    }

    /** Whether [ROMS_DIR] is readable at all. A one-time grant, like WRITE_SETTINGS for brightness. */
    fun hasFileAccess(): Boolean = Environment.isExternalStorageManager()

    /** Open the system page where the driver grants "All files access" to the launcher. */
    fun requestFileAccess(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "file access settings page failed", it) }
    }

    /** Every playable title under [ROMS_DIR]; empty without file access. Disk I/O: call off main. */
    fun library(): List<Rom> {
        if (!hasFileAccess()) {
            return emptyList()
        }

        val files = ROMS_DIR.listFiles { f -> f.isDirectory }.orEmpty().flatMap { dir ->
            dir.listFiles { f -> f.isFile }.orEmpty().map { "${dir.name}/${it.name}" }
        }

        return GameLibrary.catalogue(files)
    }

    /**
     * Start [rom] in [frontend] with its core already chosen. RetroArch reads the ROM, core and
     * config paths from intent extras — the same contract third-party frontends use. The core
     * must have been downloaded inside RetroArch once; if it is missing RetroArch shows its own
     * error rather than the launcher guessing.
     */
    fun play(context: Context, frontend: GameApp, rom: Rom): Boolean {
        val config = File(
            Environment.getExternalStorageDirectory(),
            "Android/data/${frontend.packageName}/files/retroarch.cfg",
        )
        val intent = Intent().apply {
            component = ComponentName(frontend.packageName, RETRO_ACTIVITY)
            putExtra("ROM", File(ROMS_DIR, rom.relativePath).absolutePath)
            putExtra("LIBRETRO", GameLibrary.corePath(frontend.packageName, rom.system))
            putExtra("CONFIGFILE", config.absolutePath)
            putExtra("QUITFOCUS", "")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "play ${rom.relativePath} failed", it) }
            .isSuccess
    }
}
